# Reasonix Proot App

在 **Android 上通过 proot 运行 Alpine Linux 环境**，打开应用即自动进入 **Reasonix AI 编码助手**（TUI）的 APK 工程。

无需 root，无需安装 Termux。仅支持 **arm64 (arm64-v8a)** 设备。

## 功能特性

- **一键进入 Reasonix**：打开 APK → 自动解压 Alpine Linux → 直接启动 reasonix 交互会话；退出后落到 Alpine shell。
- **完整 proot 环境**：`proot -0` 免 root 运行 Alpine 3.20（arm64），可 `apk add` 安装任意工具。
- **真实 TTY**：内置自编译 `pty-bridge`（静态 musl）创建 PTY，reasonix 的 TUI 完整可用（含鼠标滚轮滚动）。
- **屏幕自适应**：终端按手机视口自动计算行列并实时同步 PTY（旋转/软键盘自动重排）。
- **触摸滚动**：reasonix TUI 内滑动 → 模拟 SGR 滚轮事件滚动历史输出；shell 主屏滑动 → 滚动终端 scrollback。
- **离线打包**：Alpine rootfs、proot、reasonix、xterm.js 全部内置，首次启动解压后无需网络（reasonix 调用 API 时才需联网）。

## 架构

```
┌──────────────────────────────────────────────────────┐
│ Android App (com.rxproot.app)                        │
│   WebView ── xterm.js 终端模拟器（自适应 + 触摸滚动） │
│        │  JS <-> Java 桥（键盘/尺寸/滚轮）            │
│   proot.so（自编译静态 musl PIE，直接 exec）          │
│     -0 -r <rootfs> -b /dev -b /proc -b /sys          │
│        │  PROOT_LOADER=<nativeLibDir>/loader.so      │
│   Alpine Linux 3.20 (arm64 minirootfs)               │
│     /usr/bin/pty-bridge ── 创建 PTY                  │
│       └── entry.sh ── 自动启动 reasonix              │
└──────────────────────────────────────────────────────┘
```

### 关键组件（全部打包在 APK 内）

| 组件 | 说明 |
| --- | --- |
| `lib/arm64-v8a/proot.so` | 自编译 proot 5.4.0（zig 交叉编译，musl 静态 PIE，2.4MB），内置最小 talloc 兼容层 |
| `lib/arm64-v8a/loader.so` | proot 的 ELF loader（9KB 静态），由 `PROOT_LOADER` 指定，绕过 SELinux execve 限制 |
| `assets/rootfs.tar` | Alpine 3.20.10 arm64 minirootfs（gzip，首次启动用系统 toybox tar 解压） |
| `assets/usr/bin/pty-bridge` | 自编译静态 musl PIE，guest 内创建 PTY（`posix_openpt`+`fork`） |
| `assets/usr/bin/reasonix` | 静态链接 Go 二进制（来自官方 release，未随仓库分发，见下） |
| `assets/web/*` | xterm.js 5.3.0 + fit addon（离线终端渲染） |

## 构建

前置：JDK 17 + Android SDK（`platforms;android-34`、`build-tools;34.0.0`）。

```bat
cd android-app
set JAVA_HOME=C:\path\to\jdk-17
set ANDROID_HOME=C:\path\to\android-sdk
call gradlew.bat assembleDebug
```

产物：`android-app/app/build/outputs/apk/debug/app-debug.apk`。

> `android-app/local.properties` 需指向本机 SDK（未入库）。重编译 proot/pty-bridge 需要 [zig](https://ziglang.org/download)（详见 `android-app/README.md`）。

## 安装与使用

```sh
adb install reasonix-proot.apk
```

1. 首次打开：自动解压 Linux 环境（约 30 秒）→ 进入 reasonix。
2. 首次使用：在终端运行 `reasonix setup` 配置 Provider 与 API key（需联网）。
3. 退出 reasonix 后自动回到 Alpine shell；输入 `exit` 关闭。

## 已知限制

- 仅 arm64 设备；x86 模拟器无法运行。
- DNS 使用内置 `resolv.conf`（8.8.8.8 / 1.1.1.1）。
- 应用私有目录（`files/rootfs`）在卸载时清除。
- TUI 内滑动滚动依赖 reasonix 的 SGR 鼠标追踪（已启用）。

## 许可证

MIT — 见 `android-app/README.md` 顶部说明。第三方组件版权归其各自作者所有：
[Reasonix](https://github.com/esengine/DeepSeek-Reasonix)（MIT）、[proot](https://github.com/proot-me/proot)（GPL-2.0）、Alpine Linux（GPL）、[xterm.js](https://github.com/xtermjs/xterm.js)（MIT）。
