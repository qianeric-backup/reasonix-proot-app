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
# adb-autoconnect / adb-dopair 无条件创建：android-tools 可能在后台安装中，
# 启动瞬间 command -v adb 判断会漏建脚本（修复"更新后 adb 功能失效"），
# 脚本内部自行检查 adb 可用性；密钥生成与自动重连在下方等待 adb 就绪后执行。
    # 3) 端口自动发现工具：扫描 30000-49999 找无线调试连接端口（mDNS 在容器不可用）
    cat > /usr/local/bin/adb-autoconnect <<'SH'
#!/bin/sh
# 自动发现无线调试端口并连接（adb-无线调试持久化总结.md）
# 结果写入 /root/.adb_status 供 Android 侧对话框显示
status() { echo "$1" > /root/.adb_status; }
if ! command -v adb >/dev/null 2>&1; then
    status "no_adb"
    echo "adb 尚未就绪（android-tools 后台安装中），请稍后重试"
    exit 1
fi
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
echo "扫描 $HOST_IP:37000-49999 ...（约 10-60 秒）"
# Android 无线调试端口范围：配对端口 37000-37099，连接端口 37000-49999（mDNS 容器不可用）
PORTS=$(seq 37000 49999 | xargs -P 400 -I{} sh -c 'nc -z -w1 '"$HOST_IP"' {} >/dev/null 2>&1 && echo {}' 2>/dev/null | sort -n)
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
if ! command -v adb >/dev/null 2>&1; then
    echo "adb 尚未就绪（android-tools 后台安装中），请稍后重试"
    echo "no_adb" > /root/.adb_status
    exit 1
fi
HOST_IP=$(cat /root/.adb_ip 2>/dev/null)
[ -n "$HOST_IP" ] || { echo "无 /root/.adb_ip（请重开应用）"; exit 1; }
PP=${1:-}; PC=${2:-}
if [ -z "$PP" ] || [ -z "$PC" ]; then
    echo "用法: adb-dopair <配对端口> <配对码>"
    exit 1
fi
echo "配对 $HOST_IP:$PP ..."
adb pair "$HOST_IP:$PP" "$PC" 2>&1 | head -3
# 配对成功后连接端口需数秒才就绪（adbd 激活），等待后再扫描
sleep 4
echo "扫描连接端口 37000-49999 ..."
PORTS=$(seq 37000 49999 | xargs -P 400 -I{} sh -c 'nc -z -w1 '"$HOST_IP"' {} >/dev/null 2>&1 && echo {}' 2>/dev/null | sort -n)
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
# 5) 等待 adb 就绪（后台安装可能未完成）后：生成持久化密钥 + 自动重连，
#    不阻塞 reasonix 启动；adb 120 秒内未就绪则放弃本次自动重连。
(
    i=0
    while ! command -v adb >/dev/null 2>&1 && [ "$i" -lt 60 ]; do
        sleep 2
        i=$((i+1))
    done
    if command -v adb >/dev/null 2>&1; then
        mkdir -p ~/.android
        # 6) 密钥完整性校验：adbkey 丢失会退回"每次都要配对"，缺失则重新生成。
        #    adbkey 持久化于 /root/.android（rootfs 持久），密钥不变 → 免配对直连。
        if [ ! -f ~/.android/adbkey ]; then
            adb keygen ~/.android/adbkey >/dev/null 2>&1 || true
            echo "[adb] 已生成 adbkey（持久化于 /root/.android）"
        fi
        sleep 4
        adb-autoconnect >/tmp/adb-auto.log 2>&1 || true
    fi
) &

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

    # adb wrapper：root 可用（/root/.root-ok 标记，app 预检授权后写入）时，
    # `adb shell <命令>` 直接经 root 命令桥（app 侧 su 执行）——无需无线调试连接
    # 即可控制手机（打开应用/执行命令）；其余 adb 命令（devices/connect/pair 等）转真实 adb。
    if [ -x /usr/bin/adb ] && [ ! -f /usr/local/bin/adb ]; then
        cat > /usr/local/bin/adb <<'SH'
#!/bin/sh
# adb wrapper: rx-adb —— root 可用时 adb shell 走 root 命令桥（无需无线调试）
if [ "$1" = "shell" ] && [ -n "$2" ] && [ -f /root/.root-ok ]; then
    shift
    exec /usr/local/bin/root "$@"
fi
exec /usr/bin/adb "$@"
SH
        chmod 755 /usr/local/bin/adb
        echo "[adb] 已启用 root 直连模式：adb shell 经 root 桥执行（无需无线调试）"
    fi

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
                    hash -r 2>/dev/null   # 清命令缓存：确保 adb 解析到 /usr/local/bin wrapper（root 直连）
                    timeout 20 sh -c "$CMD" 2>/dev/null || true   # timeout 防 TUI 类命令无 TTY 卡死服务循环
                } > /root/.adb-out 2>&1
                echo "__DONE__" >> /root/.adb-out
            fi
            sleep 0.3
        done
    ) &

# 关闭 reasonix 遥测确认提示（REASONIX_TELEMETRY=0 / DO_NOT_TRACK 等效）
export REASONIX_TELEMETRY=0

# 每次启动全新进入 TUI（alt screen）：清理 reasonix 非正常退出（force-stop/杀进程/更新重启）
# 留下的会话恢复标记（*.recovery.json / *.recovery / *.lease.*）。若不清除，reasonix 会恢复
# 上次会话并渲染历史到普通屏（不进 alt screen），导致终端滑动查看失效。
rm -f /root/.reasonix/projects/*/sessions/*.recovery.json \
      /root/.reasonix/projects/*/sessions/*.recovery \
      /root/.reasonix/projects/*/sessions/*.lease.* 2>/dev/null

# reasonix 启动包装：保证每次启动（含退出后再次运行、更新后重启）都全新进入 TUI（alt screen）。
# 将真实二进制改名为 reasonix.bin，用 wrapper 替代 reasonix——无论 entry.sh 首次启动
# 还是用户在 shell 里再次输入 reasonix，都会先清理会话恢复标记再启动。

# 环境说明（reasonix 项目记忆 AGENTS.md，启动自动加载）：告知 AI 本环境是 Android 手机
# proot 容器，adb shell 经 root 命令桥直连手机（无需无线调试），可打开应用/模拟操作。
cat > /root/AGENTS.md <<'MD'
# RSXM 环境说明

本环境是运行在 **Android 手机** 上的 Alpine Linux proot 容器（不是服务器，也不是远程机器）。
用户要求"打开/操作手机上的应用"时，直接用下面的 adb shell 命令即可完成。

## 控制宿主手机（重要）
- `adb shell <命令>` 会直接通过**手机的 root 权限**（root 命令桥）执行，**无需无线调试连接**。
  `adb devices` 显示 `no devices/emulators found` 是正常现象（命令实际走 root 桥执行）。
- 打开应用：`adb shell am start -n <包名>/<Activity>`，例如打开微信：
  `adb shell am start -n com.tencent.mm/.ui.LauncherUI`
- 常用手机操作（root 直连）：
  - 模拟点击/输入：`adb shell input tap X Y`、`adb shell input text 内容`、`adb shell input keyevent 4`
  - 包管理：`adb shell pm list packages`、`adb shell pm disable-user --user 0 <包名>`
  - 系统设置：`adb shell settings put global ...`
- 若手机没有 root（无 su），`adb shell` 会回退到无线调试（需要先配对连接）。
MD
# 幂等/更新安全：reasonix 更新会覆盖 wrapper 位置（写入新二进制），entry.sh 检测到
# reasonix 不是 wrapper（首行无标记）时，把新二进制备份为 reasonix.bin 并重建 wrapper；
# restartEnvironment 必走 entry.sh，因此更新后自动重新包装。
if [ -x /usr/local/bin/reasonix ]; then
    # 检测 reasonix 是否已是 wrapper（ASCII 标记 rx-wrap；不用 head -1 + 中文，busybox grep 不可靠）
    if ! grep -q "rx-wrap" /usr/local/bin/reasonix 2>/dev/null; then
        mv -f /usr/local/bin/reasonix /usr/local/bin/reasonix.bin 2>/dev/null
        cat > /usr/local/bin/reasonix <<'SH'
#!/bin/sh
# reasonix wrapper: rx-wrap rsxm-project —— 每次启动前清理会话恢复标记，保证全新进入 TUI（alt screen）
rm -f /root/.reasonix/projects/*/sessions/*.recovery.json \
      /root/.reasonix/projects/*/sessions/*.recovery \
      /root/.reasonix/projects/*/sessions/*.lease.* 2>/dev/null
# 项目位置：/root/.rsxm-project 存在时 cd 到该项目目录（reasonix 按 cwd 识别项目）；
# 目录不存在则自动创建（App 侧可能只写标记未建目录）
P=$(cat /root/.rsxm-project 2>/dev/null)
if [ -n "$P" ]; then
    mkdir -p "$P" 2>/dev/null
    cd "$P" 2>/dev/null || true
fi
exec /usr/local/bin/reasonix.bin "$@"
SH
        chmod 755 /usr/local/bin/reasonix
        echo "[reasonix] 已创建启动包装（清理恢复标记，每次全新进入 TUI）"
    elif ! grep -q "rsxm-project" /usr/local/bin/reasonix 2>/dev/null; then
        # 旧版 wrapper（无项目位置功能）：升级重建（reasonix.bin 不动）
        cat > /usr/local/bin/reasonix <<'SH'
#!/bin/sh
# reasonix wrapper: rx-wrap rsxm-project —— 每次启动前清理会话恢复标记，保证全新进入 TUI（alt screen）
rm -f /root/.reasonix/projects/*/sessions/*.recovery.json \
      /root/.reasonix/projects/*/sessions/*.recovery \
      /root/.reasonix/projects/*/sessions/*.lease.* 2>/dev/null
# 项目位置：/root/.rsxm-project 存在时 cd 到该项目目录（reasonix 按 cwd 识别项目）；
# 目录不存在则自动创建（App 侧可能只写标记未建目录）
P=$(cat /root/.rsxm-project 2>/dev/null)
if [ -n "$P" ]; then
    mkdir -p "$P" 2>/dev/null
    cd "$P" 2>/dev/null || true
fi
exec /usr/local/bin/reasonix.bin "$@"
SH
        chmod 755 /usr/local/bin/reasonix
        echo "[reasonix] 已升级启动包装（支持项目位置）"
    fi
fi

# 直接进入 reasonix 交互会话；退出后落到 shell（reasonix 为包装命令，再次运行同样清理）
if command -v reasonix >/dev/null 2>&1; then
  reasonix
  echo ""
  echo "Reasonix 已退出。输入 reasonix 重新启动，或 exit 关闭。"
else
  echo "警告: 未找到 reasonix，已进入 shell。"
fi

exec /bin/sh
