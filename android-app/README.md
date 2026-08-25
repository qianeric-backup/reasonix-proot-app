# Reasonix Proot — Android 上的 proot Linux + Reasonix

一个开箱即用的 Android APK：内置 **Alpine Linux (arm64)** 环境，通过 **proot**（无需 root）运行，
打开应用即自动进入 **Reasonix AI 编码助手**（TUI），退出后回到 Alpine shell。

## 架构

```
┌──────────────────────────────────────────────────────┐
│ Android App (com.rxproot.app)                        │
│   WebView ── xterm.js 终端模拟器                      │
│        │  JS <-> Java 桥（键盘输入 / 进程输出）        │
│   proot.so（自编译静态 musl PIE，直接 exec）           │
│     -0 -r <rootfs> -b /dev -b /proc -b /sys          │
│        │  PROOT_LOADER=<nativeLibDir>/loader.so      │
│   Alpine Linux 3.20 (arm64 minirootfs)               │
│     /usr/bin/pty-bridge ── 创建 PTY                  │
│       └── entry.sh ── 自动启动 reasonix              │
└──────────────────────────────────────────────────────┘
```

关键组件（全部打包在 APK 内）：

| 组件 | 来源 | 说明 |
| --- | --- | --- |
| `lib/arm64-v8a/proot.so` | **自编译** proot 5.4.0（zig 交叉编译，musl 静态 PIE，2.4MB） | 无动态依赖/无 interpreter/无 hash 问题，Android 直接执行；内置最小 talloc 兼容层 |
| `lib/arm64-v8a/loader.so` | 自编译 proot 的 ELF loader（9KB 静态） | 由 `PROOT_LOADER` 指定，proot 用它装载 guest 动态 ELF（不 execve rootfs 文件，绕过 SELinux） |
| `assets/rootfs.tar` | Alpine 3.20.10 aarch64 minirootfs | gzip 压缩的 tar（asset 名避开 `.gz` 后缀，防止 AGP 打包时解压） |
| `assets/usr/bin/pty-bridge` | 本项目（zig 交叉编译，静态 musl PIE） | guest 内创建 PTY（`posix_openpt`+`fork`）供 reasonix 使用 |
| `assets/usr/bin/reasonix` | `../reasonix-linux-arm64/reasonix` | 静态链接 Go 二进制 |
| `assets/root/entry.sh` | 本项目 | 自动进入 reasonix 的启动脚本 |
| `assets/web/*` | xterm.js 5.3.0 | 终端渲染（离线打包） |

首次启动把 rootfs 解压到应用私有目录 `files/rootfs`（系统 toybox tar，自动处理符号链接）。

## 关键工程决策（Android 16 / SELinux 限制下的可行路径）

1. **SELinux 禁止 app exec 自己 data 目录（app_data_file）的 ELF**（`execute_no_trans` denied）。
   唯一允许 exec 的位置是 **APK native libs 目录（apk_data_file）**——所以 proot/loader 打包在
   `jniLibs`（`useLegacyPackaging=true` 解压到 `nativeLibraryDir`）。
2. **termux 预编译 proot 与 Android 16 linker64 不兼容**（`empty/missing DT_HASH/DT_GNU_HASH`），
   UserLAnd 的 proot 则找不到 `libtalloc`。因此**用 zig 静态编译 proot 5.4.0**（musl 静态 PIE），
   无 interpreter/无 NEEDED，彻底绕开 linker；同时自带最小 talloc 兼容实现（samba API 子集）。
3. **guest 内二进制不 execve**：proot 通过 `PROOT_LOADER` 指定的外部 loader 装载动态 ELF，
   静态 ELF（busybox 部分 applet 场景）由 proot 直接处理；不走宿主 execve 所以不被 SELinux 拦。
4. **PTY**：Alpine busybox 与宿主 toybox 都没有 `script`，guest 内用自编译 `pty-bridge`
   （静态 musl PIE，由 proot 装载）创建 PTY，reasonix 获得真实 TTY。

## 构建

前置：JDK 17 + Android SDK（`platforms;android-34`、`build-tools;34.0.0`）；重编译 proot 需要 zig。

1. 配置 `local.properties`（或环境变量 `ANDROID_HOME`）指向你的 SDK。
2. 双击 `build.bat`，或手动执行：
   ```bat
   set JAVA_HOME=C:\path\to\jdk-17
   call gradlew.bat assembleDebug
   ```
3. 产物：`app/build/outputs/apk/debug/app-debug.apk`（已复制到 `../dist/reasonix-proot.apk`）。

> 仅支持 **arm64 (arm64-v8a)** 设备。签名使用 debug keystore，自用安装无碍。

### 重新编译 proot（可选）

```sh
# 需要 zig（https://ziglang.org/download）
zig cc -target aarch64-linux-musl -O2 -static -pie -fPIE \
  -D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I . \
  <proot-5.4.0/src 全部 .c> loader-wrapped.o -o proot.so
```
（源码内附带 `talloc.c/h`（最小 talloc）、`sys/queue.h`（含 CIRCLEQ）、`build.h`；loader 的
`loader-wrapped.o` 由 `loader.elf` 经 ELF object 封装生成，符号 `_binary_loader_elf_start/_end`。）

## 使用体验

- **屏幕自适应**：终端自动按手机视口计算行列（竖屏约 39 列），并把窗口大小实时同步给
  PTY（reasonix 收到 SIGWINCH 自动重排布局）；旋转屏幕/软键盘弹出时自动重新适配。
- **触摸滚动**：上下滑动浏览历史输出。reasonix TUI 内（alt screen）滑动会被转换为
  SGR 鼠标滚轮事件交给 reasonix 滚动；退出到 shell 后滑动直接滚动终端 scrollback。
  侧滑菜单「滑动速度」提供档位 1~10（默认 5）调速：档位越低每页所需滑动像素越多，
  滑动越慢越精细；档位 10 接近原版 8px/页的快速翻动。", "old_string": "- **触摸滚动**：上下滑动浏览历史输出。reasonix TUI 内（alt screen）滑动会被转换为\n  SGR 鼠标滚轮事件交给 reasonix 滚动；退出到 shell 后滑动直接滚动终端 scrollback。", "path": "android-app/README.md"}
- **安装**：`adb install dist/reasonix-proot.apk`，或把 APK 传到手机直接安装。
- 首次打开：自动解压 Linux 环境（约 30 秒），然后进入 reasonix。
- 首次使用 reasonix：先运行 `reasonix setup` 配置 Provider 与模型（需要网络 + API key）。
- 退出 reasonix 后自动落到 Alpine shell；输入 `exit` 关闭会话。
- 断网/强杀应用后重开，环境保留（只解压一次）。

## 已知限制

- 终端仅支持 arm64 设备；x86 模拟器上无法运行。
- DNS 使用内置 `/etc/resolv.conf`（8.8.8.8 / 1.1.1.1），不走 Android 的私有 DNS。
- 应用私有目录（`files/rootfs`）里的改动在卸载应用时一并清除。
- TUI 内滑动滚动依赖 reasonix 的 SGR 鼠标追踪（已启用）；若未来 reasonix 版本关闭
  鼠标追踪，TUI 内将退回终端自身 scrollback（仅主屏 shell 可滚）。
