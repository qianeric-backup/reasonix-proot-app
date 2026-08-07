# ADB 无线调试与持久化连接总结(proot 容器 → 宿主 Android)

> 用途:记录从 `com.rxproot.app`(proot 容器)内通过 adb 无线调试连接宿主手机**两次**完整会话的实测过程,重点总结「持久化无线调试」的实现机制与对初始状态 apk 的改进建议。

## 一、环境背景(第二次会话实测)

| 项 | 值 |
|---|---|
| 宿主设备 | 小米 24117RK2CG(zorn),**Android 16**(较首次会话的 Android 14 已升级),arm64 |
| 容器 | proot(`com.rxproot.app`),无 `/data`,挂载宿主 `/system`、`/dev`、`/sdcard` |
| adb | 1.0.41 (35.0.1-android-tools),容器内自带 |
| 网络 | 与宿主共享,宿主 IP `192.168.71.13`(两次会话未变) |
| 无线调试连接端口 | **45595**(两次会话相同 → 手机未重启、无线调试会话未中断) |

## 二、两次会话对比:从「配对+扫描」到「免配对直连」

### 第一次会话(已记录于 adb-wireless-debug-summary.md)
1. `adb server` 崩溃 → 需 `export ANDROID_ADB_LOG_PATH=/tmp/adb.log`
2. 用户手动开启无线调试并配对:`adb pair IP:配对端口 6位码` → `Successfully paired`
3. 配对端口 ≠ 连接端口,`adb connect` 被拒 → 需扫描 30000–49999 找到真实端口
4. `adb connect IP:45595` 成功

### 第二次会话(本次,重点)
**核心结论:一旦 adbkey 持久化且手机端信任过,之后无需再配对,直接 connect 即可。**

```sh
# 1. 环境准备(与首次相同)
export ANDROID_ADB_LOG_PATH=/tmp/adb.log

# 2. 状态检查:adbkey 已持久化(首次生成于 ~/.android/,会话间保留)
ls ~/.android/   # adbkey adbkey.pub adb_known_hosts.pb adb.5037

# 3. mDNS 在容器内不可用(adb mdns services → error: unknown host service 'mdns:services')
#    → 仍用端口扫描定位连接端口
seq 30000 49999 | xargs -P 100 -I{} sh -c \
  'nc -z -w1 192.168.71.13 {} >/dev/null 2>&1 && echo "OPEN {}"'
# → OPEN 45595

# 4. 免配对直连
adb connect 192.168.71.13:45595   # → connected(未出现配对/授权提示)
adb devices -l                    # → 192.168.71.13:45595 device product:zorn model:24117RK2CG
```

## 三、持久化无线调试的关键机制(供 apk 初始化参考)

| 组件 | 位置 | 作用 | 持久化要求 |
|---|---|---|---|
| `adbkey` / `adbkey.pub` | `~/.android/` | RSA 身份密钥,手机端信任的是**公钥**;密钥不变则免重新配对 | **必须放持久目录**(本次在 proot 的 `/root/.android`,会话间保留) |
| `adb_known_hosts.pb` | `~/.android/` | 记录已连接设备的连接端口指纹 | 随密钥一起持久化 |
| `adb.5037` | `~/.android/` | adb server 端口文件 | 可忽略,自动重建 |

**持久化前提**:
- 密钥首次生成后不能再变(否则手机端不认,会退回 `unauthorized` 或要求重新配对)。
- proot 容器无 `/data`,`/root` 必须映射到应用私有持久目录(如 `/data/data/com.rxproot.app/...` 或 app 自建持久目录),**绝不能**放在随升级重建的路径。
- 手机端信任关系在系统侧保留(开发者选项→无线调试 内可见已配对设备);手机重启或清除信任后需重新配对一次。

## 四、本次会话新增踩坑点

1. **mDNS 不可用**:容器网络不支持 mDNS 服务发现(`adb mdns services` 直接报错),端口扫描是唯一可靠的端口发现手段。
2. **连接端口会随会话变化**:45595 两次相同只是因为手机未重启且无线调试未中断;手机重启/开关无线调试后端口必然变化,必须重新扫描。
3. **Android 16 兼容**:宿主已升级 Android 16,现有流程(配对→扫描→connect)在 Android 16 上验证通过,无需特殊处理。
4. **免配对的前提是「配对过一次」**:全新环境(密钥丢失或手机清过信任)仍必须走一次用户配对,无法完全自动化。

## 五、对初始状态 apk 的改进建议(v2)

按优先级排列,前 4 项首次会话已提出,本次实测确认有效;新增第 5、6 项为本次会话新结论。

1. **【已确认必要】预置 adb 日志路径**:初始化脚本内固定设置 `export ANDROID_ADB_LOG_PATH=/tmp/adb.log`(或 app 数据目录),否则首次 `adb` 必然崩溃且无报错输出。
2. **【已确认必要】预生成并持久化 adbkey**:首次启动时生成 `~/.android/adbkey`+`adbkey.pub` 并写入**持久目录**,后续会话直接复用 → 实现「免配对直连」,这是持久化无线调试的核心。
3. **【已确认必要】端口自动发现**:配对成功后(或每次启动)内置 30000–49999 扫描,自动 `adb connect` 到真实连接端口;不要假设连接端口 = 配对端口。
4. **【已确认必要】首次使用引导**:检测无设备时提示用户开启「无线调试」并索取 IP:配对端口 + 6 位码(交互式完成配对)。
5. **【新增】启动即自动重连**:每次会话启动时执行「扫描端口 → adb connect」,若 `adb devices` 已含 `device` 状态则直接复用,全程无感;仅当 `offline`/`unauthorized`/`no devices` 时才引导用户。
6. **【新增】密钥完整性校验**:启动时检查 `~/.android/adbkey` 存在,不存在则重新生成——避免密钥丢失后静默退回「每次都要配对」的体验。

## 六、附录:本次完整命令序列(可直接复用)

```sh
export ANDROID_ADB_LOG_PATH=/tmp/adb.log
# 若 adbkey 丢失,先配对一次:
#   adb pair 192.168.71.13:<配对端口> <6位码>
seq 30000 49999 | xargs -P 100 -I{} sh -c \
  'nc -z -w1 192.168.71.13 {} >/dev/null 2>&1 && echo "OPEN {}"'
adb connect 192.168.71.13:45595
adb devices -l
# 常用验证:
adb shell getprop ro.product.model                 # 24117RK2CG
adb shell dumpsys battery | grep -E "level|status" # 电池
adb shell am start -n <包名>/<Activity>            # 打开应用
```
