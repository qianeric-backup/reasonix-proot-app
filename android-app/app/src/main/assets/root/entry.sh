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

# 直接进入 reasonix 交互会话；退出后落到 shell
if command -v reasonix >/dev/null 2>&1; then
  reasonix
  echo ""
  echo "Reasonix 已退出。输入 reasonix 重新启动，或 exit 关闭。"
else
  echo "警告: 未找到 reasonix，已进入 shell。"
fi

exec /bin/sh
