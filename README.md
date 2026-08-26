# Reasonix Proot App

在 **Android 上通过 proot 运行 Alpine Linux 环境**，打开应用即自动进入 **Reasonix AI 编码助手**（TUI）的 APK 工程。

无需 root，无需安装 Termux。仅支持 **arm64 (arm64-v8a)** 设备。

## 功能特性

- **一键进入 Reasonix**：打开 APK → 自动解压 Alpine Linux → 直接启动 reasonix 交互会话；退出后落到 Alpine shell。
- **完整 proot 环境**：`proot -0` 免 root 运行 Alpine 3.20（arm64），可 `apk add` 安装任意工具。
- **真实 TTY**：内置自编译 `pty-bridge`（静态 musl）创建 PTY，reasonix 的 TUI 完整可用（含鼠标滚轮滚动）。
- **屏幕自适应**：终端按手机视口自动计算行列并实时同步 PTY（旋转/软键盘自动重排）。
- **触摸滚动**：reasonix TUI 内滑动 → 模拟 SGR 滚轮事件滚动历史输出；shell 主屏滑动 → 滚动终端 scrollback；侧滑菜单内置滑动调速（档位 1~10，默认 5），档位越低滑动越慢越精细，可随时调整。
- **纯黑主题**：全局纯黑界面（标题栏 / 侧滑菜单 / 终端 / 对话框）。
- **左侧侧滑配置菜单**（DrawerLayout）：
  - **ADB 无线调试**：guest 内自动安装 adb（国内镜像 + 国内 DNS），填写配对码/端口后一键发送配对连接命令到终端，或复制命令、直接跳转无线调试设置。
  - **API Key 配置**：随时查看/修改 DeepSeek API Key（写入 `~/.reasonix/.env`，保存后自动重启环境）。
  - **DS2API 网关（内置）**：应用启动时自动在 Linux 环境后台运行内置的 DS2API 服务（上游 [CJackHwang/ds2api](https://github.com/CJackHwang/ds2api) AGPL-3.0，v4.6.1），管理台 `http://127.0.0.1:5001/admin`（初始管理密钥 `rsxm-ds2api-admin`，首次保存配置后持久化到 `/root/ds2api/config.json`）；若旧版 DS2API App 已占用 5001 端口则内置服务不重复启动。
  - **更新 resonix**：从官方 npm 包（`@reasonix/cli-linux-arm64`，npmmirror 国内镜像）下载 tgz 解压更新，或从手机选择新版文件、恢复内置版本。
- **离线打包**：Alpine rootfs、proot、reasonix、xterm.js 与 DS2API 全部内置，首次启动解压后无需网络（reasonix 调用 API / DS2API 连接 DeepSeek 时才需联网）。
- **手机存储访问**：guest 内 `/sdcard` 直接映射手机共享存储；首次启动引导"所有文件访问"授权（授权后自动重启环境生效），并可读写宿主 app 私有数据（`/host-data`）与只读系统分区（`/host/system` 等）。
- **bash 兼容**：Alpine 无 bash，内置 `bash → busybox ash(sh)` 包装，reasonix 的 shell 命令可直接执行；同时关闭 reasonix 的 OS 沙箱（Android 无 bubblewrap）。
- **手机 Root 权限**：检测 KernelSU/Magisk，侧滑菜单可查看/测试授权状态；reasonix（AI）内直接执行 `root <命令>` 即通过 app 以 su 获取手机 root 权限（如 `root id`、`root 'pm list packages'`）。

## 架构

```
┌──────────────────────────────────────────────────────┐
│ Android App (com.rxproot.app)                        │
│   DrawerLayout（侧滑配置菜单）                        │
│   WebView ── xterm.js 终端模拟器（自适应 + 触摸滚动） │
│        │  JS <-> Java 桥（键盘/尺寸/滚轮）            │
│   proot.so（termux fork 5.1.107.89，静态 musl PIE）   │
│     -0 -r <rootfs> -b /dev -b /proc -b /sys          │
│        -b /sdcard -b /host-data -b /host/*           │
│        │  PROOT_LOADER=<nativeLibDir>/loader.so      │
│   Alpine Linux 3.20 (arm64 minirootfs)               │
│     /usr/bin/pty-bridge ── 创建 PTY                  │
│       └── entry.sh ── 自动启动 reasonix              │
└──────────────────────────────────────────────────────┘
```

### 关键组件（全部打包在 APK 内）

| 组件 | 说明 |
| --- | --- |
| `lib/arm64-v8a/proot.so` | termux 维护的 proot 5.1.107.89（zig 交叉编译，musl 静态 PIE），修复 Android app 环境 accept/accept4 被 seccomp 拦截的问题 |
| `lib/arm64-v8a/loader.so` | termux proot 配套 loader（静态，链接脚本固定 `0x2000000000`），由 `PROOT_LOADER` 指定，绕过 SELinux execve 限制 |
| `assets/rootfs.tar` | Alpine 3.20 arm64 minirootfs（gzip，首次启动用系统 toybox tar 解压） |
| `assets/ds2api/ds2api-bundle.tgz` | 内置 DS2API 网关（上游 [CJackHwang/ds2api](https://github.com/CJackHwang/ds2api) v4.6.1，AGPL-3.0：静态 arm64 二进制 + WebUI 管理台 + LICENSE/README），随环境启动自动后台运行（127.0.0.1:5001） |
| `assets/usr/bin/pty-bridge` | 自编译静态 musl PIE，guest 内创建 PTY（`posix_openpt`+`fork`） |
| `assets/usr/bin/reasonix` | 静态链接 Go 二进制（来自官方 npm 平台包 `@reasonix/cli-linux-arm64`，可在应用内一键更新） |
| `assets/web/*` | xterm.js 5.3.0 + fit addon（离线终端渲染） |

## 构建

前置：JDK 17 + Android SDK（`platforms;android-34`、`build-tools;34.0.0`）。

```bat
cd android-app
set JAVA_HOME=C:\path\to\jdk-17
set ANDROID_HOME=C:\path\to\android-sdk
call gradlew.bat assembleRelease
```

产物：`android-app/app/build/outputs/apk/release/app-release.apk`（R8 混淆 + 资源压缩，约 16MB）。

> `android-app/local.properties` 需指向本机 SDK（未入库）。重编译 proot/pty-bridge 需要 [zig](https://ziglang.org/download)（详见 `android-app/README.md`）。

## 安装与使用

```sh
adb install reasonix-proot.apk
```

1. 首次打开：自动解压 Linux 环境（约 30 秒）→ **弹出 API Key 配置对话框**，填入 DeepSeek
   API Key（platform.deepseek.com 获取）点击"保存并启动"，即可直接进入 reasonix 会话。
2. 已配置过则直接进入；随时可在 **侧滑菜单 → API Key 配置** 修改。
3. 退出 reasonix 后自动回到 Alpine shell；输入 `exit` 关闭。

### ADB 无线调试（用 guest 内 adb 调试本手机）

1. 手机：设置 → 开发者选项 → 无线调试 → 打开，记下配对码与配对/连接端口。
2. 侧滑菜单 → **ADB 无线调试** → 填入配对码/端口 → 点「配对并连接」或「自动连接」
   （由应用直接驱动容器内 adb 执行并回显结果，**不依赖 reasonix 会话**）。
3. 连接成功（状态变「已连接」）后，在 reasonix 终端里即可 `adb shell` / `adb install`。

### 更新 resonix

侧滑菜单 → **更新 resonix** → 网络更新（默认官方 npm 源，可改 URL 指定版本）或从手机选择新版文件。

## 已知限制

- 仅 arm64 设备；x86 模拟器无法运行。
- DNS 使用国内公共 DNS（223.5.5.5 / 119.29.29.29）。
- 应用私有目录（`files/rootfs`）在卸载时清除。
- TUI 内滑动滚动依赖 reasonix 的 SGR 鼠标追踪（已启用）。
- Android 11+ 无法访问其他应用的 `Android/data` 目录（系统硬限制）。

## 许可证

MIT。第三方组件版权归其各自作者所有：
[Reasonix](https://github.com/esengine/DeepSeek-Reasonix)（MIT）、[proot](https://github.com/termux/proot)（GPL-2.0）、Alpine Linux（GPL）、[xterm.js](https://github.com/xtermjs/xterm.js)（MIT）。
