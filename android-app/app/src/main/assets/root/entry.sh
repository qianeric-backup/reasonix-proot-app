#!/bin/sh
# Reasonix 启动入口 —— 在 proot 的 Alpine Linux 环境内执行。
# 由 Android 端通过 busybox script 提供 PTY 后运行。
export HOME=/root
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
export TERM=xterm-256color
export LANG=C.UTF-8
# 重置宿主路径临时目录：proot 启动进程继承 Android 的 TMPDIR（/data/app/.../lib/arm64，
# app 侧为 proot 自身设的宿主路径），guest 内 reasonix/工具按此创建临时目录会落到
# 不存在的宿主路径（sessiontemp: list temp root ... no such file）或 rootfs 里错误的 /data/app。
# 统一改到 guest 内可写 /tmp。
export TMPDIR=/tmp
export TMP=/tmp

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
    # 防御性强制 Alpine 环境：proot 启动进程继承 Android 宿主环境（HOME=/、PATH=Android），
    # 若外层 export 未作用到此子 shell，执行的命令会解析到宿主 sh/bash（如 /system_ext/bin/bash）
    # 且找不到 apk/python3 等 Alpine 工具 → 面板命令/shell 环境异常。此处显式重置。
    (
        export HOME=/root
        export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
        export TERM=xterm-256color
        export LANG=C.UTF-8
        export TMPDIR=/tmp
        export TMP=/tmp
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

# --- DS2API 网关（内置上游 AGPL-3.0 服务端，assets/ds2api/ds2api-bundle.tgz）---
# 端口由 PORT 环境变量控制（上游默认 5001，见 cmd/ds2api/main.go）。
# 管理台：http://127.0.0.1:5001/admin（App 侧滑栏 DS2API 网关面板内嵌 WebView 打开）。
# 管理密钥：默认 rsxm-ds2api-admin（DS2API_ADMIN_KEY，config 未保存前生效，
# 首次在管理台保存配置后会写入 /root/ds2api/config.json 并持久化）。
# 若用户沿用旧版 DS2API App 的 5001 端口，本内置服务不会启动（检测端口占用），
# 面板仍可访问旧服务；移除/停止旧 App 后重启本应用即自动接管。
DS2API_DIR=/usr/local/ds2api
# 解压到 proot/chroot 后 exec 位可能丢失，显式补回
chmod +x "$DS2API_DIR/ds2api" 2>/dev/null
if [ -x "$DS2API_DIR/ds2api" ]; then
    echo "[ds2api] 检测到内置 DS2API 网关 v4.6.1，准备后台启动 ..."
    mkdir -p /root/ds2api
    if nc -z 127.0.0.1 5001 >/dev/null 2>&1; then
        echo "[ds2api] 127.0.0.1:5001 已被占用（可能为旧版 DS2API App），跳过内置服务启动"
    else
        (
            export HOME=/root
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export TERM=xterm-256color
            export LANG=C.UTF-8
            export TMPDIR=/tmp
            export TMP=/tmp
            export NO_PROXY=127.0.0.1,localhost
            export no_proxy=127.0.0.1,localhost
            export PORT=5001
            export DS2API_ADMIN_KEY=rsxm-ds2api-admin
            cd /root/ds2api
            # 幂等：已在运行则跳过（pgrep -x 精确进程名，避免 -f 全匹配自匹配）
            if ! pgrep -x ds2api >/dev/null 2>&1; then
                /usr/local/ds2api/ds2api >/root/ds2api/ds2api.log 2>&1 &
                echo "[ds2api] 已后台启动（管理台 http://127.0.0.1:5001/admin，密钥 rsxm-ds2api-admin）"
            fi
        ) &
    fi
fi

# 每次启动全新进入 TUI（alt screen）：清理 reasonix 非正常退出（force-stop/杀进程/更新重启）
# 留下的会话恢复标记（*.recovery.json / *.recovery / *.lease.*）。若不清除，reasonix 会恢复
# 上次会话并渲染历史到普通屏（不进 alt screen），导致终端滑动查看失效。
rm -f /root/.reasonix/projects/*/sessions/*.recovery.json \
      /root/.reasonix/projects/*/sessions/*.recovery \
      /root/.reasonix/projects/*/sessions/*.lease.* 2>/dev/null

# 项目元数据目录权限归一：proot（guest root = app uid）与 chroot（真实 root）运行模式
# 切换后，/root/.reasonix/projects/ 下项目目录 owner 不一致（700 且属不同 uid），
# reasonix 在当前模式下创建 sessions 会 permission denied（controller: open session inbox）。
# 统一放开读写权限（目录位于 app 私有 rootfs 内，无外部访问风险）。
chmod -R a+rwx /root/.reasonix/projects/ 2>/dev/null

# reasonix 启动包装：保证每次启动（含退出后再次运行、更新后重启）都全新进入 TUI（alt screen）。
# 将真实二进制改名为 reasonix.bin，用 wrapper 替代 reasonix——无论 entry.sh 首次启动
# 还是用户在 shell 里再次输入 reasonix，都会先清理会话恢复标记再启动。

# 环境说明（adb / root 桥）：告知 AI 本环境是 Android 手机 proot/chroot 容器（双运行模式），
# adb shell 经 root 命令桥直连手机（无需无线调试），可打开应用/模拟操作；内置 root 命令桥（root <命令>）。
#
# 关键：reasonix 的指令解析先加载 **Reasonix home（~/.reasonix）下的用户全局指令**，
# 再加载项目目录（workspace root → 目标路径）的 AGENTS.md/REASONIX.md/CLAUDE.md。
# 而 wrapper 会把会话切到 .rsxm-project 指定的任意项目目录 —— 若只写 /root/AGENTS.md（项目级），
# 换项目后 AI 就读不到 adb/root 桥说明。因此必须写入 home 根目录的全局指令，
# 保证任何 cwd / 任何项目下都自动注入；再以 cp 同步一份到 /root/AGENTS.md（项目目录恰为 /root 时也生效）。
mkdir -p /root/.reasonix
cat > /root/.reasonix/AGENTS.md <<'MD'
# RSXM 环境能力说明（全局，所有项目生效）

本环境是运行在 **Android 手机** 上的 Alpine Linux 容器（proot/chroot 两种运行模式，不是服务器，也不是远程机器）。
本说明位于 Reasonix home 的用户全局指令中，**无论当前项目目录在哪，以下能力始终可用**。
用户要求"打开/操作手机上的应用"时，直接用下面的 adb shell 命令即可完成。

## 控制宿主手机（重要）
- `adb shell <命令>` 会直接通过**手机的 root 权限**（root 命令桥）执行，**无需无线调试连接**。
  `adb devices` 走真实 adb（未连接无线调试时显示 `no devices/emulators found` 属正常，不影响 `adb shell` 经 root 桥执行）。
- 打开应用：`adb shell am start -n <包名>/<Activity>`，例如打开微信：
  `adb shell am start -n com.tencent.mm/.ui.LauncherUI`
- 常用手机操作（root 直连）：
  - 模拟点击/输入：`adb shell input tap X Y`、`adb shell input text 内容`、`adb shell input keyevent 4`
  - 包管理：`adb shell pm list packages`、`adb shell pm disable-user --user 0 <包名>`
  - 系统设置：`adb shell settings put global ...`
- 若手机没有 root（无 su），`adb shell` 会回退到无线调试（需要先配对连接）。

## 内置 root 命令桥（root 权限）
- 直接执行 `root <命令>` 以 root 权限运行宿主手机命令，例如：
  `root id`、`root 'pm list packages'`、`root 'settings put global ...'`

详细操作手册见全局 skill：`rsxm-android-bridge`（`/rsxm-android-bridge` 或 run_skill 调用）。
MD
# 同步一份到项目级 AGENTS.md（项目目录恰为 /root 时同样生效；两种模式共享同一 rootfs）
cp -f /root/.reasonix/AGENTS.md /root/AGENTS.md

# 全局 skill（Reasonix home/skills，跨项目可见，AI 可按需 /rsxm-android-bridge 调用）
mkdir -p /root/.reasonix/skills/rsxm-android-bridge
cat > /root/.reasonix/skills/rsxm-android-bridge/SKILL.md <<'MD'
---
name: rsxm-android-bridge
description: RSXM 环境能力手册 —— adb shell 经 root 命令桥直连宿主 Android 手机（无需无线调试），root <命令> 以 su 执行宿主命令。需要操作手机（打开应用 / 模拟点击输入 / 查包 / 改系统设置）时查阅。
---
# RSXM adb / root 桥能力手册

本环境是运行在 Android 手机上的 Alpine Linux 容器（proot/chroot 双模式）。
通过以下两个桥可直接控制**宿主手机**（即安装本 App 的那台手机）。

## adb 桥（root 直连）
- `adb shell <命令>` 走 **root 命令桥**（App 侧 su 执行），**无需无线调试连接**。
  `adb devices` 走真实 adb（未连接无线调试时显示 `no devices/emulators found` 属正常，不影响 `adb shell` 经 root 桥执行）。
- 无 root（无 su）时自动回退无线调试，需先配对连接。
- 示例：
  - 打开微信：`adb shell am start -n com.tencent.mm/.ui.LauncherUI`
  - 模拟点击：`adb shell input tap X Y`；模拟输入：`adb shell input text 内容`
  - 查包：`adb shell pm list packages`；禁用应用：`adb shell pm disable-user --user 0 <包名>`
  - 改系统设置：`adb shell settings put global <键> <值>`

## root 命令桥
- `root <命令>` 直接以 root（su）执行宿主手机命令，例如：
  `root id`、`root 'pm list packages'`、`root 'settings put global ...'`
- 执行结果由 App 回写；超时说明 Root 授权未通过，请先在侧滑菜单 ROOT 面板检查授权。

## 注意
- 容器内命令（apk/文件操作等）直接执行即可，无需前缀。
- 只有"操作宿主手机"的命令才需要 adb / root 桥。
MD
# 幂等/更新安全：reasonix 更新会覆盖 wrapper 位置（写入新二进制），entry.sh 检测到
# reasonix 不是 wrapper（首行无标记）时，把新二进制备份为 reasonix.bin；
# 无论是否 wrapper 都强制重写 wrapper（幂等），保证 reasonix 更新/升级后包装参数
# （含 YOLO 审批模式开关）始终为最新；restartEnvironment 必走 entry.sh，因此更新后自动重新包装。
if [ -x /usr/local/bin/reasonix ]; then
    # 检测 reasonix 是否已是 wrapper（ASCII 标记 rx-wrap；不用 head -1 + 中文，busybox grep 不可靠）
    if ! grep -q "rx-wrap" /usr/local/bin/reasonix 2>/dev/null; then
        mv -f /usr/local/bin/reasonix /usr/local/bin/reasonix.bin 2>/dev/null
        echo "[reasonix] 检测到新版本二进制，已备份为 reasonix.bin"
    fi
    cat > /usr/local/bin/reasonix <<'SH'
#!/bin/sh
# reasonix wrapper: rx-wrap rsxm-yolo —— 清理会话恢复标记 + 项目位置 + 审批模式 + 会话恢复
rm -f /root/.reasonix/projects/*/sessions/*.recovery.json \
      /root/.reasonix/projects/*/sessions/*.recovery \
      /root/.reasonix/projects/*/sessions/*.lease.* 2>/dev/null
# 会话恢复：App 侧滑「会话」面板把会话 jsonl 绝对路径写入 /root/.rsxm-resume，
# 存在则 --resume 继续该会话（聊天记录保留）。一次性：读取后立即删除标记，
# 下次启动仍为全新会话。
RESUME=""
if [ -f /root/.rsxm-resume ]; then
    RESUME=$(head -1 /root/.rsxm-resume 2>/dev/null | tr -d '\r\n')
    rm -f /root/.rsxm-resume
    [ -n "$RESUME" ] && [ -e "$RESUME" ] || RESUME=""
fi
# 项目位置：/root/.rsxm-project 存在时 cd 到该项目目录（reasonix 按 cwd 识别项目）；
# 目录不存在则自动创建（App 侧可能只写标记未建目录）
P=$(cat /root/.rsxm-project 2>/dev/null)
if [ -n "$P" ]; then
    mkdir -p "$P" 2>/dev/null
    cd "$P" 2>/dev/null || true
fi
# 审批模式（App 侧滑栏「YOLO 免审批模式」开关写 /root/.rsxm-yolo 标记）：
#   标记存在 → bypassPermissions（完全跳过工具审批）；否则 → auto（自动批准普通工具，保留安全规则）
if [ -f /root/.rsxm-yolo ]; then
    if [ -n "$RESUME" ]; then
        exec /usr/local/bin/reasonix.bin --permission-mode bypassPermissions --resume "$RESUME" "$@"
    else
        exec /usr/local/bin/reasonix.bin --permission-mode bypassPermissions "$@"
    fi
else
    if [ -n "$RESUME" ]; then
        exec /usr/local/bin/reasonix.bin --permission-mode auto --resume "$RESUME" "$@"
    else
        exec /usr/local/bin/reasonix.bin --permission-mode auto "$@"
    fi
fi
SH
    chmod 755 /usr/local/bin/reasonix
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
