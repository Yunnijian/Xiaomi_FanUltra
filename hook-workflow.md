# xposed-fan-mode-hook Hook 要点与执行流程

本文档按当前已验证版本重新整理 `xposed-fan-mode-hook` 的 Hook 目标、关键流程、状态语义、验证方式和维护注意事项。

> 当前版本结论：
> - Settings 侧只负责显示和写入 `fan_mode=4`。
> - SystemUI 侧只负责控制中心显示和写入 `fan_mode=4`。
> - PowerKeeper 侧负责真正下发 `target_level=4`，并负责解决“狂暴模式切回智能调频后 `target_level=4` 残留”的问题。
> - 旧的 Settings 侧 `4 -> 2 -> -1` 中转逻辑已删除，不再使用。

---

## 1. 项目与模块信息

项目目录：

```txt
/storage/emulated/0/OPwork/mifan/xposed-fan-mode-hook
```

核心源码：

```txt
app/src/main/java/com/mifan/kt/HookEntry.kt
```

模块包名 / namespace / applicationId：

```txt
com.mifan.kt
```

LSPosed 入口类：

```txt
com.mifan.kt.HookEntry
```

入口声明文件：

```txt
app/src/main/resources/META-INF/xposed/java_init.list
```

内容应为：

```txt
com.mifan.kt.HookEntry
```

模块作用域：

```txt
com.android.settings
com.miui.powerkeeper
com.android.systemui
```

作用分工：

| 作用域 | 职责 |
|---|---|
| `com.android.settings` | 系统设置散热风扇页面新增“狂暴模式” |
| `com.miui.powerkeeper` | 将 `fan_mode=4` 转换为实际 `target_level=4`，并处理狂暴退出收尾 |
| `com.android.systemui` | 控制中心散热风扇磁贴二级页新增“狂暴模式” |

---

## 2. 当前风扇模式语义

当前版本必须按以下语义处理：

| `fan_mode` | 含义 | 归属 |
|---:|---|---|
| `-1` | 智能调频 | 官方 |
| `1` | 静谧模式 | 官方 |
| `2` | 高速强冷 | 官方 |
| `4` | 狂暴模式 | 模块新增 |

注意：

1. `fan_mode=-1` 才是智能调频，不是 `0`。
2. `target_level=-1` 不是有效退出语义，不应写入。
3. 智能调频下 `target_level` 由 PowerKeeper 动态控制，可能是 `0/1/2`，不要求固定为 `0`。
4. 模块只对 `fan_mode=4` 和“狂暴 -> 智能调频”特殊路径介入，其他官方档位应尽量交给官方逻辑。

---

## 3. 关键系统设置与节点

### 3.1 Settings.System 键

```txt
fan_mode
cooling_fan_enable
fan_mode_range
fan_mode_scene_gaming
fan_mode_scene_rapid_charge
fan_mode_scene_navigation
fan_smart_stop_on_recording
```

其中：

- `fan_mode`：当前风扇模式。
- `cooling_fan_enable`：散热风扇总开关。
- `fan_mode_range` 与各 `fan_mode_scene_*`：官方使用范围和场景限制。

### 3.2 底层运行节点

```txt
/sys/devices/platform/soc/soc:xiaomi_fan/target_level
/sys/devices/platform/soc/soc:xiaomi_fan/real_speed
/sys/devices/platform/soc/soc:xiaomi_fan/pwm_duty
```

当前稳定版本只通过 PowerKeeper 自身方法写 `target_level`，不在 Settings/SystemUI 进程中直接写 sysfs。

### 3.3 设备树配置目录

```txt
/sys/devices/platform/soc/soc:xiaomi_fan/of_node/
```

该路径实际指向：

```txt
/sys/firmware/devicetree/base/soc/xiaomi_fan
```

它是只读设备树配置，不是运行时控制接口。此前分析得到：

```txt
compatible = xm,xm-pwm-fan
duty_speed_map = 0->0, 51->12000, 65->14000, 73->16000, 98->19800
speed_adjust_cfg 包含范围 0-99
```

这说明驱动层支持 PWM duty 配置，但当前稳定模块不做自定义 `pwm_duty` 调节。

---

## 4. 总体执行链路

### 4.1 选择狂暴模式

用户在系统设置页或控制中心选择“狂暴模式”：

```txt
用户选择“狂暴模式”
        ↓
Settings 或 SystemUI 写入 Settings.System["fan_mode"] = 4
        ↓
PowerKeeper 监听设置变化并进入 FanStateHandler
        ↓
模块 Hook PowerKeeper 判断官方总开关、使用范围、场景限制
        ↓
允许时通过 PowerKeeper 自身 J("target_level", "4") 下发
        ↓
/sys/devices/platform/soc/soc:xiaomi_fan/target_level = 4
```

### 4.2 狂暴模式切回智能调频

当前已验证有效的退出链路位于 PowerKeeper 侧：

```txt
用户从 fan_mode=4 切到 fan_mode=-1
        ↓
PowerKeeper 收到 fan_mode=-1
        ↓
模块检测到上一状态是狂暴模式
        ↓
PowerKeeper 侧主动执行：
    J("target_level", "2")
    Settings.System["fan_mode"] = 2
    延迟 800ms
    Settings.System["fan_mode"] = -1
        ↓
最终回到官方智能调频，target_level 不再残留 4
```

该流程已由真机验证解决：

```txt
fan_mode=-1 但 target_level=4 残留
```

的问题。

---

## 5. Hook 入口分发

入口方法：

```kotlin
override fun onPackageReady(param: PackageReadyParam)
```

按包名分发：

```kotlin
when (param.packageName) {
    "com.android.settings" -> installFanModeHooks(param.classLoader)
    "com.miui.powerkeeper" -> installPowerKeeperHooks(param.classLoader)
    "com.android.systemui" -> installSystemUiHooks(param.classLoader)
}
```

---

## 6. Settings Hook 要点

### 6.1 目标包与类

```txt
包名：com.android.settings
类名：com.android.settings.coolingfan.FanModeController
```

该类负责系统设置散热风扇页面中的风扇模式 Preference。

### 6.2 Hook 方法

```txt
displayPreference(androidx.preference.PreferenceScreen)
updateState(androidx.preference.Preference)
onPreferenceChange(androidx.preference.Preference, Object)
```

### 6.3 当前职责

Settings 侧只做两件事：

1. 在风扇模式下拉框追加“狂暴模式”。
2. 允许原生 Settings 逻辑自然写入 `fan_mode=4`。

追加内容：

```txt
标题：狂暴模式
值：4
描述：疾速冷却，最大幅度提升散热能力
```

### 6.4 displayPreference / updateState

原方法执行后调用：

```kotlin
ensureExtremeMode(controller, preference)
```

主要逻辑：

```txt
找到 key=fan_mode 的 DropDownPreference
        ↓
追加 entries：狂暴模式
        ↓
追加 entryValues：4
        ↓
追加 summaries：疾速冷却，最大幅度提升散热能力
        ↓
必要时回写 FanModeController 内部 Preference 引用
```

相关辅助函数：

```kotlin
ensureExtremeMode(...)
appendEntries(...)
appendEntryValues(...)
appendSummaries(...)
writePreferenceRef(...)
```

### 6.5 onPreferenceChange

当前逻辑：

```kotlin
val value = chain.getArg(1)?.toString()
if (value == MODE_EXTREME_VALUE) {
    logInfo("Extreme fan mode selected; let Settings write fan_mode=4")
}
chain.proceed()
```

也就是说：

- 选择 `4` 时只记录日志；
- 不拦截；
- 不手动写 `target_level`；
- 不做 `4 -> 2 -> -1` 中转；
- 交给 Settings 原生逻辑写 `Settings.System["fan_mode"]`。

### 6.6 已删除的旧逻辑

以下旧逻辑已确认无用并删除：

```txt
handleExtremeToSmartTransition(...)
EXTREME_TO_SMART_DELAY_MS = 500L
```

原因：Settings 侧做 `fan_mode=2 -> fan_mode=-1` 中转时，PowerKeeper 可能没有消费中间状态，真机测试仍出现：

```txt
fan_mode=-1
target_level=4
```

---

## 7. PowerKeeper Hook 要点

### 7.1 目标包与类

```txt
包名：com.miui.powerkeeper
类名：com.miui.powerkeeper.unionpower.corehandler.FanStateHandler
```

该类是散热风扇策略判断和底层下发核心。

### 7.2 Hook 方法

```txt
J(String path, String value)
handleMessage(android.os.Message)
o()
```

含义：

| 方法 | 作用 |
|---|---|
| `J(String,String)` | PowerKeeper 内部写底层路径的方法，最终通过小米电源/充电 HAL 写节点 |
| `handleMessage(Message)` | 处理设置变化、场景变化等消息 |
| `o()` | 初始化或重新读取系统设置 |

### 7.3 J(String,String) Hook

核心逻辑：

```txt
如果 path == "target_level"
并且当前 fan_mode == 4
并且 shouldForceExtremeLevel() 为 true
并且原本要写入的值不是 4
则把写入参数改成 target_level=4
否则放行官方逻辑
```

对应意图：

- PowerKeeper 官方策略原本只认识官方档位；
- 当前用户选择 `fan_mode=4` 且官方场景允许时，将官方准备写入的普通 level 提升为 `4`；
- 不满足条件时不强行覆盖，避免影响官方策略。

### 7.4 handleMessage / o Hook

原方法执行后调用：

```kotlin
applyExtremePolicy(handler, writePathMethod)
```

作用：

1. 在 PowerKeeper 处理设置变化后补充 `fan_mode=4` 的实际下发。
2. 在 PowerKeeper 重启或重读设置后同步狂暴模式状态。
3. 检测“狂暴 -> 智能调频”并执行 PowerKeeper 侧桥接。

---

## 8. PowerKeeper 策略判断：shouldForceExtremeLevel

核心函数：

```kotlin
shouldForceExtremeLevel(handler)
```

判断条件：

1. 当前 `Settings.System["fan_mode"] == 4`。
2. `cooling_fan_enable == 1`。
3. 读取并遵守 `fan_mode_range`。
4. 读取并遵守：
   - `fan_mode_scene_gaming`
   - `fan_mode_scene_rapid_charge`
   - `fan_mode_scene_navigation`
   - `fan_smart_stop_on_recording`
5. 参考 PowerKeeper 内部状态，例如：
   - 锁屏状态
   - 快充状态
   - 录音/麦克风状态
   - 通话/听筒状态
   - thermal 场景
   - 前台包名
   - 场景集合/映射
6. 遇到官方高优先级停止/降噪场景时不强制运行。

结果：

| 条件 | 动作 |
|---|---|
| `fan_mode=4` 且官方策略允许 | 写 `target_level=4` |
| `fan_mode=4` 但官方策略不允许 | 写 `target_level=0`，避免狂暴全局常驻 |
| 非 `fan_mode=4` | 默认放行官方逻辑；仅特殊处理“狂暴 -> 智能调频” |

---

## 9. 狂暴切回智能调频桥接

### 9.1 问题背景

真机曾出现：

```txt
settings get system fan_mode -> -1
cat /sys/devices/platform/soc/soc:xiaomi_fan/target_level -> 4
```

说明：

- 设置值已经回到智能调频；
- 但底层 `target_level` 仍残留狂暴档；
- 官方 PowerKeeper 没有及时把 `target_level=4` 拉回官方档位。

### 9.2 无效方案

Settings 侧曾尝试：

```txt
fan_mode=2
延迟 500ms
fan_mode=-1
```

真机验证无效，已删除。

### 9.3 当前有效方案

在 PowerKeeper 侧保留：

```kotlin
bridgeExtremeToSmartInPowerKeeper(handler, writePathMethod, resolver)
```

触发条件：

```txt
当前 mode == -1
并且 wasExtremeModeActive == true
或 lastFanModeSeen == 4
```

执行流程：

```txt
extremeToSmartBridgeInProgress 防重入
        ↓
J("target_level", "2")
        ↓
Settings.System["fan_mode"] = 2
        ↓
Thread.sleep(800ms)
        ↓
Settings.System["fan_mode"] = -1
        ↓
释放防重入标志
```

保留常量：

```kotlin
private const val EXTREME_TO_SMART_PK_DELAY_MS = 800L
```

这个方案已经真机确认解决残留问题。

---

## 10. SystemUI Hook 要点

### 10.1 目标包与类

```txt
包名：com.android.systemui
类名：com.android.systemui.controlcenter.policy.CoolingFanController
类名：com.android.systemui.qs.tiles.CoolingFanTile$CoolingFanDetailAdapter
```

### 10.2 目标链路

控制中心散热风扇二级页使用 `_secondaryItems` 作为模式列表。点击条目时，会将模型中的 `SelectableItem.identity` 写入：

```txt
Settings.System["fan_mode"]
```

因此只要新增一个：

```txt
identity = 4
```

即可让控制中心选择狂暴模式。

### 10.3 CoolingFanController 构造函数 Hook

Hook 所有构造函数，原构造执行后：

```kotlin
ensureSystemUiExtremeItem(controller, classLoader)
```

作用：

- 找到 `_secondaryItems`；
- 如果不存在 `identity=4`，则追加狂暴模式模型；
- 避免后续二级页生成时没有狂暴模式。

### 10.4 CoolingFanDetailAdapter.updateItems Hook

流程：

```txt
updateItems 前：ensureSystemUiExtremeItemFromAdapter(...)
        ↓
执行原生 updateItems()
        ↓
updateItems 后：rebuildSystemUiDetailItems(...)
```

目的：

1. 前置补 `_secondaryItems`，让原生逻辑参与生成条目。
2. 后置重建显示条目，确保 `identity=4` 最终显示为“狂暴模式”。

### 10.5 createDetailView Hook

在创建二级页 View 前补一次：

```kotlin
ensureSystemUiExtremeItemFromAdapter(adapter, classLoader)
```

用于提高首次打开控制中心二级页时的成功率。

### 10.6 fallback 资源

SystemUI 原生模型需要合法 `titleRes`，否则可能崩溃：

```txt
Resources$NotFoundException: String resource ID #0x0
```

当前使用合法 fallback：

```txt
quick_settings_coolingfan_mode_smart -> 0x7f140c70
quick_settings_coolingfan_mode_high  -> 0x7f140c6e
quick_settings_coolingfan_mode_quiet -> 0x7f140c6f
ic_qs_coolingfan_mode_performance    -> 0x7f0812c9
```

由于 fallback 文本可能是“高速强冷”，模块会在最终 detail item 层把 `identity=4` 的标题覆盖为：

```txt
狂暴模式
```

维护原则：不要改写官方原生 identity，避免再次出现多个“高速强冷”或官方档位错乱。

---

## 11. 当前核心常量

```kotlin
private const val TAG = "FanModeHook"

private const val TARGET_PACKAGE = "com.android.settings"
private const val POWERKEEPER_PACKAGE = "com.miui.powerkeeper"
private const val SYSTEMUI_PACKAGE = "com.android.systemui"

private const val FAN_MODE_CONTROLLER = "com.android.settings.coolingfan.FanModeController"
private const val FAN_STATE_HANDLER = "com.miui.powerkeeper.unionpower.corehandler.FanStateHandler"
private const val SYSTEMUI_COOLING_FAN_CONTROLLER = "com.android.systemui.controlcenter.policy.CoolingFanController"
private const val SYSTEMUI_COOLING_FAN_DETAIL_ADAPTER = "com.android.systemui.qs.tiles.CoolingFanTile$CoolingFanDetailAdapter"

private const val KEY_FAN_MODE = "fan_mode"
private const val KEY_COOLING_FAN_ENABLE = "cooling_fan_enable"
private const val KEY_FAN_MODE_RANGE = "fan_mode_range"
private const val KEY_SCENE_GAMING = "fan_mode_scene_gaming"
private const val KEY_SCENE_RAPID_CHARGE = "fan_mode_scene_rapid_charge"
private const val KEY_SCENE_NAVIGATION = "fan_mode_scene_navigation"
private const val KEY_SMART_STOP_RECORDING = "fan_smart_stop_on_recording"

private const val TARGET_LEVEL = "target_level"

private const val MODE_SMART_VALUE = "-1"
private const val MODE_HIGH_VALUE = "2"
private const val MODE_EXTREME_VALUE = "4"
private const val EXTREME_TO_SMART_PK_DELAY_MS = 800L

private const val MODE_EXTREME_TITLE = "狂暴模式"
private const val MODE_EXTREME_SUMMARY = "疾速冷却，最大幅度提升散热能力"
```

---

## 12. 构建方式

当前 release 构建命令：

```sh
cd /storage/emulated/0/OPwork/mifan/xposed-fan-mode-hook
bash ../gradle-8.13/bin/gradle assembleRelease -x :app:checkReleaseAarMetadata --rerun-tasks --stacktrace
mkdir -p dist
cp -f app/build/outputs/apk/release/app-release.apk dist/fan-mode-hook-release.apk
```

当前输出 APK：

```txt
/storage/emulated/0/OPwork/mifan/xposed-fan-mode-hook/dist/fan-mode-hook-release.apk
```

最近一次清理后构建大小：

```txt
46429 bytes
```

常见构建警告：

- Gradle 8.13 即将不被未来 Kotlin 插件支持；
- `android.aapt2FromMavenOverride` 是 experimental；
- Manifest `android:extractNativeLibs` 警告；
- Kotlin `No cast needed` 警告。

只要最终为 `BUILD SUCCESSFUL` 即可。

---

## 13. 安装后重启作用域

覆盖安装新 APK 后，至少重启 Settings 和 PowerKeeper：

```sh
su -c 'pkill -f com.android.settings'
su -c 'pkill -f com.miui.powerkeeper'
```

如果改动涉及控制中心，再重启 SystemUI：

```sh
su -c 'pkill -f com.android.systemui'
```

必要时可直接重启手机。

---

## 14. 验证命令

查看当前模式：

```sh
settings get system fan_mode
```

查看底层目标档位：

```sh
cat /sys/devices/platform/soc/soc:xiaomi_fan/target_level
```

查看实际转速：

```sh
cat /sys/devices/platform/soc/soc:xiaomi_fan/real_speed
```

查看 Hook 相关日志：

```sh
logcat -d -v time | grep -iE 'FanModeHook|FanStateHandler|target_level|fan_mode|CoolingFan'
```

### 14.1 狂暴模式验证

选择狂暴模式后：

```txt
fan_mode=4
```

在官方策略允许的场景下：

```txt
target_level=4
```

如果总开关关闭、场景不允许、锁屏/录音/听筒通话等官方停止场景触发，则不应强制 `target_level=4`。

### 14.2 狂暴切回智能调频验证

从狂暴模式切回智能调频后，最终应为：

```txt
fan_mode=-1
```

并且：

```txt
target_level 不再残留 4
```

智能调频下 `target_level=0/1/2` 都可能是正常值。

---

## 15. 已废弃或不应恢复的方案

以下方案已经验证存在问题，后续不要恢复：

1. 把 `fan_mode=0` 当作智能调频。
2. 写 `target_level=-1` 作为智能调频。
3. 非狂暴模式下统一补写 `target_level=0`。
4. 在 Settings 侧做 `fan_mode=2 -> 延迟 -> fan_mode=-1` 作为退出方案。
5. 改写 SystemUI 原生官方档位 identity。
6. 恢复 `fan_mode=5` 自定义调节滑块到当前稳定主线。
7. 在 PowerKeeper 进程中执行 `su` 写 sysfs。
8. 只要 `fan_mode=4` 就无条件写 `target_level=4`，绕过官方总开关和场景限制。

---

## 16. 自定义 pwm_duty 探索结论

当前稳定版本不包含自定义调节功能。

此前探索结论：

```txt
fan_mode=5 和 fan_custom_pwm_duty 可以保存
但 /sys/devices/platform/soc/soc:xiaomi_fan/pwm_duty 不变化
```

关键失败日志：

```txt
Not Support set node pwm_duty
EACCES
Cannot run program "su": error=13, Permission denied
```

结论：

- PowerKeeper/IMiCharge 对 `pwm_duty` 返回成功不等于底层真实写入成功；
- PowerKeeper 进程无权直接写 sysfs；
- PowerKeeper 进程中也不能可靠执行 `su`；
- 若未来重做自定义调节，应考虑模块自身 APP、独立 root helper 或更底层接口，不应放入当前稳定主线。

---

## 17. 维护原则

后续维护按以下原则执行：

1. Settings 只负责 UI 和 `fan_mode=4` 写入入口。
2. SystemUI 只负责控制中心 UI 和 `fan_mode=4` 写入入口。
3. PowerKeeper 才负责实际 `target_level` 下发和退出收尾。
4. 官方档位 `-1/1/2` 尽量不干预。
5. 狂暴模式必须服从官方总开关、使用范围和场景限制。
6. “狂暴 -> 智能调频”只保留 PowerKeeper 侧桥接，不在 Settings 侧重复实现。
7. 系统更新后若失效，优先重新确认类名、字段名、资源 ID 和 `FanStateHandler.J(String,String)` 是否变化。
