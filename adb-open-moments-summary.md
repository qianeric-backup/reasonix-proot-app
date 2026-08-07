# ADB 打开微信朋友圈过程总结（proot 容器 → 宿主 Android）

> 目的：记录从 `com.rxproot.app`（proot 容器）内使用 adb 无线调试打开宿主手机微信朋友圈的完整过程、踩坑点与最终方案，供改进初始状态 apk 参考。

## 一、环境背景

| 项 | 值 |
|---|---|
| 宿主设备 | 小米 24117RK2CG（zorn），Android 14，arm64 |
| 容器 | `com.rxproot.app`（reasonix Agent / proot），非 root，SELinux 受限 |
| 连接方式 | adb 无线调试，`adb connect 192.168.71.13:45595`（连接端口 ≠ 配对端口，需扫描或看无线调试主界面） |
| 屏幕 | 1440×3200 |
| 目标 | 打开微信（com.tencent.mm）朋友圈 |

## 二、踩坑点与解法

### 坑 1：直接 `am start` 朋友圈 Activity 被拒绝 ❌
```bash
adb shell am start -n com.tencent.mm/com.tencent.mm.plugin.sns.ui.SnsTimeLineUI
# → java.lang.SecurityException: Permission Denial ... not exported from uid 10429
```
新版微信朋友圈 Activity 未导出（not exported），shell（uid 2000）无权直启。
- 新版朋友圈实际 Activity 为 `com.tencent.mm/.plugin.sns.ui.improve.ImproveSnsTimelineUI`（微信 8.0.43+ 新架构），同样未导出。

### 坑 2：深链 `weixin://dl/moments` 只能打开 WebView 页 ⚠️
```bash
adb shell am start -a android.intent.action.VIEW -d "weixin://dl/moments"
# → 前台变成 com.tencent.mm/.plugin.webview.ui.tools.MMWebViewUI（网页页），不是原生朋友圈
```
微信没有公开的原生朋友圈深链，此 scheme 解析为内部 WebView 页面。

### 坑 3：task 栈残留导致 `am start LauncherUI` 无效 ⚠️
深链打开过 `MMWebViewUI` 后，它残留在微信 task（如 t953）栈顶。此时 `am start -n com.tencent.mm/.ui.LauncherUI` 只会把整个 task 带回前台，显示的还是栈顶的网页页。
**解法：先 `am force-stop com.tencent.mm` 再冷启动**，清掉旧 task 栈。

### 坑 4：宿主守护界面 `com.rxproot.app` 不定时抢回前台 ⚠️
reasonix Agent 应用（MainActivity）会周期性把前台抢回，实测微信在前台窗口期约 1~2 秒。影响：
- `uiautomator dump` 需 1~2s，基本赶不上窗口期，dump 到的常是守护界面。
- 分多条命令逐步点击不可行，中间会被抢。
**解法：全部操作放在一条 `adb shell "..."` 命令内串联执行，并减少 sleep 时间**；用 `dumpsys activity activities | grep topResumedActivity`（毫秒级）验证，不用 dump。

### 坑 5：验证手段选择
- ❌ `uiautomator dump`：太慢（1~2s），且微信很多元素无 text 属性，过滤困难。
- ✅ `dumpsys activity activities | grep topResumedActivity`：即时反映前台 Activity，点击成功后应显示 `com.tencent.mm/.plugin.sns.ui.improve.ImproveSnsTimelineUI`。

## 三、最终成功方案 ✅

```bash
adb connect 192.168.71.13:45595

adb shell "am force-stop com.tencent.mm; \
  sleep 1; \
  am start -n com.tencent.mm/.ui.LauncherUI >/dev/null 2>&1; \
  sleep 4; \
  input tap 900 3080; \
  sleep 0.8; \
  input tap 720 400; \
  sleep 0.6; \
  dumpsys activity activities 2>/dev/null | grep topResumedActivity"
```

验证输出：
```
topResumedActivity=ActivityRecord{... com.tencent.mm/.plugin.sns.ui.improve.ImproveSnsTimelineUI t960}
```
→ 说明已进入朋友圈。

### 操作说明（1440×3200 分辨率）
| 步骤 | 命令 | 说明 |
|---|---|---|
| 1 | `am force-stop com.tencent.mm` | 清掉旧 task 栈（关键） |
| 2 | `am start ... .ui.LauncherUI` | 冷启动微信主界面 |
| 3 | `input tap 900 3080` | 点击底部「发现」tab（第 3 个 tab） |
| 4 | `input tap 720 400` | 点击发现页第一项「朋友圈」 |
| 5 | `dumpsys ... grep topResumedActivity` | 验证已进入朋友圈 |

## 四、对初始状态 apk 的改进建议

1. **让 agent 可直启朋友圈**：把 `com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI`（或旧版 `SnsTimeLineUI`）声明为 `android:exported="true"` 并加自定义 scheme 深链（如 `weixin://moments`），agent 即可 `am start` 直达，无需 UI 点击。前提是宿主有权限修改/注入微信，或通过辅助功能（AccessibilityService）模拟点击。
2. **避免守护界面抢前台**：`com.rxproot.app` 增加「让出前台/后台运行」开关（如最小化到悬浮窗、或允许其他应用置顶），给 UI 自动化留出稳定窗口期；或把抢回逻辑改为仅在空闲时执行。
3. **UI 自动化窗口期太短**：实测 1~2s，若保留 UI 点击路线，建议 apk 提供「操作期间暂停抢前台」的 API/广播，或 agent 在启动微信前先通知宿主挂起守护逻辑。
4. **无线调试连接端口 ≠ 配对端口**：配对成功后 adb 会开新监听端口（本机 45595），建议 apk 内置端口扫描或直接读取无线调试主界面「IP 地址和端口」。
5. **坐标写死风险**：本次坐标基于 1440×3200 全屏。若 app 使用分屏/悬浮窗或分辨率变化，建议先用 `wm size` 获取分辨率，再按比例换算 tab 坐标（发现 tab x = 宽×5/8，朋友圈 y ≈ 高×1/8）。

## 五、可复用要点速查

- 连接：`adb connect 192.168.71.13:45595`（密钥已持久化，免配对）
- 直启朋友圈 ❌（未导出）→ 必须 UI 点击
- UI 点击前置条件：`force-stop` + 冷启动，一条命令内完成
- 验证：`dumpsys activity activities | grep topResumedActivity` → `ImproveSnsTimelineUI` = 成功
- 守护界面会在约 2 秒后盖回前台（宿主正常行为，不影响"已打开"结果）
