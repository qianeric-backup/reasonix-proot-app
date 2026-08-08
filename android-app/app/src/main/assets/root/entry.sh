#!/bin/sh
# Reasonix 启动入口 —— 在 proot 的 Alpine Linux 环境内执行。
# 由 Android 端通过 busybox script 提供 PTY 后运行。
export HOME=/root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TERM=xterm-256color
export LANG=C.UTF-8

# 终端尺寸由 Android 端 xterm.js 自适应后通过 pty-bridge 动态设置，
# 这里不再固定 stty（避免覆盖 resize 后的窗口大小）。

cd /root || exit 1

if [ ! -e /root/.reasonix-welcome ]; then
  echo ""
  echo "  ==============================================="
  echo "   Reasonix Linux  (Alpine + proot)"
  echo "   正在启动 Reasonix AI 编码助手..."
  echo ""
  echo "   首次使用请先运行:  reasonix setup"
  echo "  ==============================================="
  echo ""
  touch /root/.reasonix-welcome
fi

# reasonix bash 沙箱兜底：Android 无 bubblewrap（bwrap），enforce 会拒绝所有 shell 命令，
# 每次启动确保 [sandbox] bash = "off"（Java 端 ensureSandboxDisabled 已预置，此处防配置被改）。
CONF="$HOME/.reasonix/config.toml"
if [ -f "$CONF" ] && grep -q 'bash *= *"enforce"' "$CONF" 2>/dev/null; then
    sed -i 's/bash *= *"enforce"/bash = "off"/' "$CONF" 2>/dev/null
fi

# Alpine 无 bash；创建 bash -> busybox ash(sh) 包装，让 reasonix 探测到 bash 时
# 实际用 sh 执行命令（arm64/Alpine 兼容）。幂等：已存在则跳过。
if [ ! -e /usr/local/bin/bash ]; then
    cat > /usr/local/bin/bash <<'SH'
#!/bin/sh
# bash 兼容包装：以 busybox ash 解释执行（POSIX 兼容）
exec /bin/busybox ash "$@"
SH
    chmod 755 /usr/local/bin/bash
fi
[ -e /bin/bash ] || ln -sf /usr/local/bin/bash /bin/bash

# adb 无线调试：guest 内安装 android-tools（用于在 reasonix 中通过 adb
# 无线连接"安装本 app 的手机"）。dl-cdn 源在部分网络不可达，换成国内镜像。
# 后台安装不阻塞启动；首次需联网（镜像源较快）。
# 国内 DNS（8.8.8.8 在国内网络可能不可达）
cat > /etc/resolv.conf <<DNS
nameserver 223.5.5.5
nameserver 119.29.29.29
DNS

if ! command -v adb >/dev/null 2>&1; then
    VER=$(cat /etc/alpine-release 2>/dev/null | cut -d. -f1-2)
    [ -n "$VER" ] || VER=v3.21
    cat > /etc/apk/repositories <<EOF
https://mirrors.aliyun.com/alpine/v$VER/main
https://mirrors.aliyun.com/alpine/v$VER/community
EOF
    (
        echo "[adb] apk update ..."
        apk update 2>&1 | tail -2
        echo "[adb] apk add android-tools ..."
        if apk add --no-cache android-tools 2>&1 | tail -5; then
            echo "[adb 已安装] $(adb version 2>/dev/null | head -1)"
        else
            echo "[adb 安装失败]"
        fi
    ) &
fi

# --- ADB 无线调试持久化（adb-无线调试持久化总结.md v2）---
# 1) 预置 adb 日志路径：否则首次 adb 必然崩溃且无报错输出
export ANDROID_ADB_LOG_PATH=/tmp/adb.log
if command -v adb >/dev/null 2>&1; then
    mkdir -p ~/.android
    # 6) 密钥完整性校验：adbkey 丢失会退回"每次都要配对"，缺失则重新生成。
    #    adbkey 持久化于 /root/.android（rootfs 持久），密钥不变 → 免配对直连。
    if [ ! -f ~/.android/adbkey ]; then
        adb keygen ~/.android/adbkey >/dev/null 2>&1 || true
        echo "[adb] 已生成 adbkey（持久化于 /root/.android）"
    fi
    # 3) 端口自动发现工具：扫描 30000-49999 找无线调试连接端口（mDNS 在容器不可用）
    cat > /usr/local/bin/adb-autoconnect <<'SH'
#!/bin/sh
# 自动发现无线调试端口并连接（adb-无线调试持久化总结.md）
# 结果写入 /root/.adb_status 供 Android 侧对话框显示
status() { echo "$1" > /root/.adb_status; }
adb_state() {
    if adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {f=1} END{exit !f}'; then
        echo "device"
    elif adb devices 2>/dev/null | grep -q "unauthorized"; then
        echo "unauthorized"
    elif adb devices 2>/dev/null | grep -q "offline"; then
        echo "offline"
    else
        echo "none"
    fi
}
[ -f /root/.adb_ip ] || { status "no_ip"; echo "无 /root/.adb_ip（请重开应用）"; exit 1; }
HOST_IP=$(cat /root/.adb_ip)
case "$HOST_IP" in
    *.*.*.*) : ;;
    *) status "bad_ip"; echo "IP 无效: $HOST_IP"; exit 1 ;;
esac
# 已有 device 直接复用（免配对直连）
if [ "$(adb_state)" = "device" ]; then
    ADDR=$(adb devices 2>/dev/null | awk 'NR>1 && $2=="device" {print $1; exit}')
    status "connected $ADDR"
    echo "[adb] 已连接 $ADDR（免配对直连）"
    adb devices -l
    exit 0
fi
echo "扫描 $HOST_IP:30000-49999 ...（约 10-40 秒）"
PORTS=$(seq 30000 49999 | xargs -P 100 -I{} sh -c 'nc -z -w1 '"$HOST_IP"' {} >/dev/null 2>&1 && echo {}' 2>/dev/null | sort -n)
if [ -z "$PORTS" ]; then
    status "no_port"
    echo "未找到开放端口。请确认手机已开启「无线调试」且与本机同 Wi-Fi。"
    exit 1
fi
echo "发现开放端口: $(echo $PORTS | tr '\n' ' ')"
# 逐个试连（扫描到的可能是配对端口或连接端口；配对端口 connect 会 offline/拒绝，
# 连接端口 connect 后 2-5 秒内变为 device）。找到 device 状态即成功。
for P in $PORTS; do
    echo "尝试连接 $HOST_IP:$P ..."
    adb connect "$HOST_IP:$P" >/dev/null 2>&1
    sleep 3
    if adb devices 2>/dev/null | awk -v a="$HOST_IP:$P" '$1==a && $2=="device" {f=1} END{exit !f}'; then
        status "connected $HOST_IP:$P"
        echo "[adb] 连接成功 $HOST_IP:$P（免配对直连）"
        adb devices -l
        exit 0
    fi
done
case "$(adb_state)" in
    unauthorized) status "need_pair"; echo "设备未授权：请在手机无线调试页面完成配对（配对后免配对直连）" ;;
    *) status "need_pair"; echo "未能连接（开放端口 $(echo $PORTS | tr '\n' ' ')）。请先配对：填写配对端口+配对码点「配对并连接」" ;;
esac
adb devices -l
SH
    chmod 755 /usr/local/bin/adb-autoconnect

    # 配对并自动连接工具（app 驱动）：adb-dopair <配对端口> <配对码>
    # 配对成功后自动扫描连接端口并 connect，全程由 app 处理，AI/reasonix 无需关心。
    cat > /usr/local/bin/adb-dopair <<'SH'
#!/bin/sh
# 配对并自动连接无线调试（由 Android 侧 app 调用）
HOST_IP=$(cat /root/.adb_ip 2>/dev/null)
[ -n "$HOST_IP" ] || { echo "无 /root/.adb_ip（请重开应用）"; exit 1; }
PP=${1:-}; PC=${2:-}
if [ -z "$PP" ] || [ -z "$PC" ]; then
    echo "用法: adb-dopair <配对端口> <配对码>"
    exit 1
fi
echo "配对 $HOST_IP:$PP ..."
adb pair "$HOST_IP:$PP" "$PC" 2>&1 | head -3
sleep 1
echo "扫描连接端口 30000-49999 ..."
PORTS=$(seq 30000 49999 | xargs -P 100 -I{} sh -c 'nc -z -w1 '"$HOST_IP"' {} >/dev/null 2>&1 && echo {}' 2>/dev/null | sort -n)
if [ -z "$PORTS" ]; then
    echo "未找到连接端口（请确认无线调试已开启）"
    echo "no_port" > /root/.adb_status
    exit 1
fi
echo "发现开放端口: $(echo $PORTS | tr '\n' ' ')"
# 逐个试连：配对端口 connect 会 offline/拒绝，连接端口 connect 后 2-5 秒变 device
for P in $PORTS; do
    echo "尝试连接 $HOST_IP:$P ..."
    adb connect "$HOST_IP:$P" >/dev/null 2>&1
    sleep 3
    if adb devices 2>/dev/null | awk -v a="$HOST_IP:$P" '$1==a && $2=="device" {f=1} END{exit !f}'; then
        echo "connected $HOST_IP:$P" > /root/.adb_status
        echo "== 配对连接成功！reasonix 内可直接 adb shell / adb install =="
        adb devices -l
        exit 0
    fi
done
adb devices -l
echo "need_pair" > /root/.adb_status
echo "配对完成但连接未就绪（可能是配对码/端口已过期，请重新配对）"
SH
    chmod 755 /usr/local/bin/adb-dopair
    # 5) 启动即自动重连：后台静默执行（不阻塞 reasonix 启动）
    ( sleep 4; adb-autoconnect >/tmp/adb-auto.log 2>&1 || true ) &
fi

# root 命令桥：app 侧以 su 执行本机 root 命令（KernelSU/Magisk）。
# AI/用户在 reasonix 里直接 `root <命令>` 即可获取手机 root 权限。
# 原理：写入 /root/.root-cmd → app 轮询执行 su → 结果写 /root/.root-out。
cat > /usr/local/bin/root <<'SH'
#!/bin/sh
# root 命令桥包装（app 侧 su 执行）
if [ -z "$1" ]; then
    echo "用法: root <命令>  例: root id / root 'pm list packages'"
    exit 1
fi
rm -f /root/.root-out
printf '%s' "$*" > /root/.root-cmd
i=0
while [ ! -f /root/.root-out ] && [ $i -lt 60 ]; do
    sleep 0.5
    i=$((i+1))
done
if [ -f /root/.root-out ]; then
    cat /root/.root-out
    rm -f /root/.root-out
else
    echo "(root 执行超时——请检查 Root 权限对话框中的授权状态)"
fi
SH
chmod 755 /usr/local/bin/root

    # ADB 独立执行服务：app 侧把命令写入 /root/.adb-cmd，此循环执行并把
    # 结果写入 /root/.adb-out（末尾追加 __DONE__ 标记）。使 ADB 无线调试
    # 直接由 app 驱动，不依赖 reasonix（AI）会话。
    (
        touch /root/.adb-service
        while [ -f /root/.adb-service ]; do
            if [ -s /root/.adb-cmd ]; then
                CMD=$(cat /root/.adb-cmd)
                rm -f /root/.adb-cmd
                {
                    eval "$CMD"
                } > /root/.adb-out 2>&1
                echo "__DONE__" >> /root/.adb-out
            fi
            sleep 0.3
        done
    ) &

# 关闭 reasonix 遥测确认提示（REASONIX_TELEMETRY=0 / DO_NOT_TRACK 等效）
export REASONIX_TELEMETRY=0

# 直接进入 reasonix 交互会话；退出后落到 shell
if command -v reasonix >/dev/null 2>&1; then
  reasonix
  echo ""
  echo "Reasonix 已退出。输入 reasonix 重新启动，或 exit 关闭。"
else
  echo "警告: 未找到 reasonix，已进入 shell。"
fi

exec /bin/sh
