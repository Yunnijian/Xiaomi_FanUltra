# Xiaomi_FanUltra

一个面向小米 / HyperOS 设备的轻量级 LSPosed 风扇增强模块，用于在系统散热风扇功能中新增“狂暴模式”，并通过 PowerKeeper 接入官方风扇控制链路，实现比官方高速强冷更高的风扇档位。

> 模块包名：`com.mifan.kt`  
> 当前版本：`1.0`  
> LSPosed API：`102`  
> 入口类：`com.mifan.kt.HookEntry`

## 作者与开源说明

作者酷安主页：[https://www.coolapk.com/u/1404550](https://www.coolapk.com/u/1404550)

本模块开源免费，欢迎其他 Xposed / LSPosed 模块作者将本项目合入其个人项目，也欢迎官改 ROM 作者将此功能内置到系统中。欢迎对本项目进行优化、适配和二次开发。

如进行二次开发、项目合并或 ROM 内置，请注明原作者和项目出处。

---

## 1. 模块作用

Xiaomi_FanUltra 会在官方散热风扇功能中新增一个额外模式：

```txt
狂暴模式
```

模块会在以下官方入口中显示该模式：

```txt
系统设置散热风扇页面
控制中心散热风扇磁贴二级页
```

用户选择“狂暴模式”后，Settings 或 SystemUI 会沿用官方逻辑写入：

```txt
Settings.System["fan_mode"] = 4
```

随后由 PowerKeeper 侧接管策略判断，并在允许风扇运行时下发：

```txt
target_level = 4
```

从而实现比官方高速强冷更强的散热档位。

---

## 2. 特色亮点

### 2.1 新增狂暴模式

在官方“智能调频 / 静谧模式 / 高速强冷”之外，新增“狂暴模式”。用户可以像选择官方档位一样，在系统设置或控制中心中直接选择。

### 2.2 体积小，占用低

模块是轻量级 LSPosed Hook 模块：

- 不重写系统界面；
- 不常驻额外复杂服务；
- Release APK 体积小；
- 运行占用低。

### 2.3 遵循官方风扇调用逻辑

模块不是绕过系统直接粗暴写 sysfs 节点，而是接入官方调用链：

```txt
Settings / SystemUI 写入 fan_mode
        ↓
PowerKeeper 处理风扇策略
        ↓
PowerKeeper 下发 target_level
```

这样整体行为更接近官方风扇档位，也便于跟随官方场景策略。

### 2.4 遵循官方使用范围和场景限制

狂暴模式会参考官方散热风扇设置中的：

```txt
散热风扇总开关
使用范围
游戏场景
极速充电场景
导航场景
录音智能停止
锁屏 / 通话 / thermal 等运行状态
```

只有在官方策略允许风扇运行时，模块才会下发 `target_level=4`。不允许时会回落，避免狂暴模式绕过官方设置全局常驻。

### 2.5 稳定退出狂暴模式

从狂暴模式切回智能调频时，模块在 PowerKeeper 侧执行桥接流程：

```txt
target_level=2
fan_mode=2
延迟 800ms
fan_mode=-1
```

该流程用于解决少数情况下从狂暴模式切回智能调频后，风扇依旧长时间维持最高转速的问题。

---

## 3. 官方风扇控制流程

官方风扇控制流程可以概括为：

```txt
用户在系统设置或控制中心选择风扇模式
        ↓
Settings / SystemUI 写入 Settings.System["fan_mode"]
        ↓
PowerKeeper 监听设置变化
        ↓
FanStateHandler 读取总开关、使用范围、场景限制和设备状态
        ↓
FanStateHandler.J("target_level", value)
        ↓
底层节点 /sys/devices/platform/soc/soc:xiaomi_fan/target_level 改变
        ↓
风扇转速变化
```

当前已确认的模式语义：

| fan_mode | 含义 | 来源 |
|---:|---|---|
| `-1` | 智能调频 | 官方 |
| `1` | 静谧模式 | 官方 |
| `2` | 高速强冷 | 官方 |
| `4` | 狂暴模式 | 本模块新增 |

注意：`fan_mode=-1` 是智能调频；智能调频下 `target_level` 由系统动态调控，可能是 `0/1/2`，不应固定理解为 `0`。

---

## 4. 模块工作流程

### 4.1 选择狂暴模式

```txt
用户在系统设置或控制中心选择“狂暴模式”
        ↓
Settings / SystemUI 写入 fan_mode=4
        ↓
PowerKeeper 监听到 fan_mode 变化
        ↓
模块 Hook FanStateHandler
        ↓
判断官方总开关、使用范围、场景限制和当前设备状态
        ↓
官方策略允许时写入 target_level=4
        ↓
风扇进入狂暴档位
```

### 4.2 从狂暴模式切回智能调频

```txt
用户选择智能调频 fan_mode=-1
        ↓
PowerKeeper 检测到刚从狂暴模式退出
        ↓
先写 target_level=2，并切到 fan_mode=2
        ↓
延迟 800ms 后写回 fan_mode=-1
        ↓
最终回到官方智能调频，避免风扇长时间维持最高转速
```

Settings 侧旧的 `fan_mode=4 -> fan_mode=2 -> fan_mode=-1` 桥接方案已验证无效，当前只保留 PowerKeeper 侧桥接。

---

## 5. 逆向过程简述

### 5.1 Settings 侧

通过分析系统设置 APK，确认散热风扇页面和控制器：

```txt
res/xml/cooling_fan_settings.xml
com.android.settings.coolingfan.MiuiCoolingFanSettings
com.android.settings.coolingfan.FanModeController
```

`FanModeController` 负责风扇模式 Preference 的显示、状态更新和用户选择后写入 `Settings.System["fan_mode"]`。

### 5.2 PowerKeeper 侧

通过分析 PowerKeeper，确认实际风扇策略核心类：

```txt
com.miui.powerkeeper.unionpower.corehandler.FanStateHandler
```

关键私有方法：

```txt
J(String path, String value)
```

该方法最终通过小米电源 / 充电接口下发路径和值，典型目标为：

```txt
target_level
```

对应底层节点：

```txt
/sys/devices/platform/soc/soc:xiaomi_fan/target_level
```

### 5.3 SystemUI 侧

通过分析 SystemUI，确认控制中心散热风扇磁贴相关类：

```txt
com.android.systemui.controlcenter.policy.CoolingFanController
com.android.systemui.qs.tiles.CoolingFanTile
com.android.systemui.qs.tiles.CoolingFanTile$CoolingFanDetailAdapter
```

控制中心二级页使用 `_secondaryItems` 作为模式列表，点击条目后会把 `SelectableItem.identity` 写入 `Settings.System["fan_mode"]`。

### 5.4 底层节点探索

当前设备已确认风扇节点位于：

```txt
/sys/devices/platform/soc/soc:xiaomi_fan
/sys/devices/virtual/xm_power/hw_monitor/pwm_fan
```

常见运行节点：

```txt
fan_support
target_level
pwm_duty
real_speed
```

设备树目录：

```txt
/sys/firmware/devicetree/base/soc/xiaomi_fan
```

设备树中可见 `compatible=xm,xm-pwm-fan`、`duty_speed_map`、`speed_adjust_cfg` 等配置，说明驱动支持 PWM 风扇，但稳定版本只使用官方 `target_level` 链路。

---

## 6. Hook 点说明

### 6.1 Settings Hook

目标包：

```txt
com.android.settings
```

目标类：

```txt
com.android.settings.coolingfan.FanModeController
```

Hook 方法：

```txt
displayPreference(androidx.preference.PreferenceScreen)
updateState(androidx.preference.Preference)
onPreferenceChange(androidx.preference.Preference, Object)
```

作用：

- 在风扇模式下拉列表中追加“狂暴模式”；
- 追加 entryValue：`4`；
- 追加 summary：`疾速冷却，最大幅度提升散热能力`；
- 选择 `4` 时让 Settings 原生逻辑写入 `fan_mode=4`。

Settings 侧不负责实际写 `target_level`，也不负责狂暴退出桥接。

### 6.2 PowerKeeper Hook

目标包：

```txt
com.miui.powerkeeper
```

目标类：

```txt
com.miui.powerkeeper.unionpower.corehandler.FanStateHandler
```

Hook 方法：

```txt
J(String path, String value)
handleMessage(android.os.Message)
o()
```

作用：

- 在 `fan_mode=4` 且官方策略允许时，将 `target_level` 提升为 `4`；
- 在 `fan_mode=4` 但官方策略不允许时，下发 `target_level=0`，避免全局常驻；
- 在“狂暴模式 -> 智能调频”时执行 PowerKeeper 侧桥接，解决偶发的切回智能调频后风扇依旧长时间维持最高转速的问题。

关键保留逻辑：

```txt
bridgeExtremeToSmartInPowerKeeper(...)
extremeToSmartBridgeInProgress
EXTREME_TO_SMART_PK_DELAY_MS = 800L
```

### 6.3 SystemUI Hook

目标包：

```txt
com.android.systemui
```

目标类：

```txt
com.android.systemui.controlcenter.policy.CoolingFanController
com.android.systemui.qs.tiles.CoolingFanTile$CoolingFanDetailAdapter
```

作用：

- 向控制中心散热风扇二级页模式列表追加 `identity=4` 的条目；
- 使用合法 fallback 资源，避免 `Resources$NotFoundException`；
- 最终将该条目显示为“狂暴模式”；
- 用户点击后由 SystemUI 原生逻辑写入 `fan_mode=4`。

SystemUI 侧只新增 `identity=4`，不改写官方原生 identity，避免官方档位错乱。

---

## 7. 使用方法

### 7.1 安装模块

安装 Release APK：

```txt
fan-mode-hook-release.apk
```

当前包名：

```txt
com.mifan.kt
```

### 7.2 LSPosed 启用作用域

在 LSPosed 中启用模块，并勾选：

```txt
com.android.settings
com.miui.powerkeeper
com.android.systemui
```

作用说明：

| 作用域 | 作用 |
|---|---|
| `com.android.settings` | 系统设置中显示“狂暴模式” |
| `com.miui.powerkeeper` | 实际下发狂暴档位，并处理退出逻辑 |
| `com.android.systemui` | 控制中心磁贴中显示“狂暴模式” |

### 7.3 重启相关进程

启用模块后建议重启手机，或至少重启相关进程：

```sh
su -c 'pkill -f com.android.settings'
su -c 'pkill -f com.miui.powerkeeper'
su -c 'pkill -f com.android.systemui'
```

### 7.4 验证

选择狂暴模式后验证：

```sh
settings get system fan_mode
cat /sys/devices/platform/soc/soc:xiaomi_fan/target_level
cat /sys/devices/platform/soc/soc:xiaomi_fan/real_speed
logcat -d -v time | grep -iE 'FanModeHook|FanStateHandler|target_level|fan_mode|CoolingFan'
```

预期：

```txt
fan_mode=4
target_level=4
```

从狂暴模式切回智能调频后：

```txt
fan_mode=-1
避免风扇长时间维持最高转速
```

---

## 8. 构建

Debug 构建可直接执行：

```sh
cd xposed-fan-mode-hook
bash ../gradle-8.13/bin/gradle assembleDebug
```

Release 构建需要本地提供 release key 与密码环境变量 / Gradle 属性。示例：

```sh
cd xposed-fan-mode-hook
RELEASE_STORE_PASSWORD=... RELEASE_KEY_PASSWORD=... RELEASE_KEY_ALIAS=mifan_release \
  bash ../gradle-8.13/bin/gradle assembleRelease -x :app:checkReleaseAarMetadata --rerun-tasks --stacktrace
mkdir -p dist
cp -f app/build/outputs/apk/release/app-release.apk dist/fan-mode-hook-release.apk
```

当前 release 版本：

```txt
versionName = 1.0
versionCode = 1
```

---

## 9. 当前稳定版不包含的内容

当前稳定版本不包含自定义 `pwm_duty` 滑块。此前曾探索 `fan_mode=5`、`fan_custom_pwm_duty`、SeekBarPreference、IMiCharge、direct sysfs、root `su` fallback 等方案，但真机验证结果为：

```txt
fan_mode=5 和 fan_custom_pwm_duty 可以保存
pwm_duty 实际仍不可靠
PowerKeeper/IMiCharge 对 pwm_duty 写入通路不可用或受限
PowerKeeper 进程执行 su 也存在权限限制
```

因此当前稳定主线只保留官方 `fan_mode -> PowerKeeper -> target_level` 链路。

---

## 10. 维护原则

1. Settings 侧只负责 UI 和 `fan_mode=4` 写入，不恢复 Settings 侧退出桥接。
2. PowerKeeper 侧负责 `target_level=4` 下发、官方策略限制判断和狂暴退出桥接。
3. SystemUI 侧只新增 `identity=4`，不改写官方原生档位。
4. 不要把 `fan_mode=0` 当作智能调频；智能调频是 `fan_mode=-1`。
5. 不要写 `target_level=-1`。
6. 不要恢复“只要 fan_mode=4 就无条件写 4”的逻辑，否则会破坏官方总开关和使用范围。

---

## 11. 注意事项

1. 模块依赖 LSPosed，需要正确启用模块和作用域。
2. 狂暴模式会遵循官方总开关、使用范围和场景限制，并非任何时候都强制运行。
3. 系统更新后，如果 Settings、SystemUI 或 PowerKeeper 的类名、字段名变化，模块可能需要重新适配。
4. 如果控制中心未显示“狂暴模式”，请确认 `com.android.systemui` 作用域已启用并重启 SystemUI。
5. 如果狂暴模式不能实际生效，请确认 `com.miui.powerkeeper` 作用域已启用并重启 PowerKeeper 或重启手机。

---

## 12. 免责声明

本项目仅用于学习、研究和个人设备调试。Hook 系统组件存在兼容性风险，使用前请确认理解相关风险。因系统版本差异、设备差异或使用不当导致的问题需自行承担。