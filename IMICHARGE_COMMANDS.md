# IMiCharge AIDL 命令参考

本文整理从当前 ROM 框架反编译得到的 `vendor.xiaomi.hardware.micharge.IMiCharge/default` AIDL 接口，适用于分析和短时真机验证。

下文的事务号和参数格式仅适用于当前 AIDL 实现。切勿未经重新验证就用于其他 ROM、机型，或旧版 HIDL 服务。

## 适用范围与安全提示

- 文中所有 `service call` 都以前缀为准：

  ```sh
  adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default
  ```

- 下方每一项都写出完整命令，可直接复制后替换其中的占位符。
- 示例采用本机已验证可用于事务 `18` 的 `service call` 格式。
- 读取命令不会改动状态。写入命令可能改变充电、无线充、蓝牙传输或风扇状态，不要使用猜测出来的值。
- 通用路径接口没有公开完整的键白名单。本文只记录本次逆向中已确认的键；专有 HAL 可能接受更多键。其中仅 `target_level` 和 `fan_real_speed` 已确认与风扇直接相关。
- PowerKeeper 可能立即覆盖对 `target_level` 的直接写入。直接写入会绕过风扇总开关、场景、温控和使用范围策略，只应用于验证。

## Java 调用

`miui.util.IMiCharge` 是隐藏的 MIUI 框架 API。下面的直接调用适用于框架代码或被 Hook 的系统进程；普通第三方应用通常无法直接编译或调用它，只有在模块进程和平台策略允许时才应使用反射。

```java
// 直接调用形式：该类不属于公开 Android SDK。
IMiCharge miCharge = IMiCharge.getInstance();
String speed = miCharge.getMiChargePath("fan_real_speed");
boolean written = miCharge.setMiChargePath("target_level", "2");
```

```java
// 模块或系统进程中可使用的反射形式。
Class<?> type = Class.forName("miui.util.IMiCharge");
Object miCharge = type.getMethod("getInstance").invoke(null);

String speed = (String) type
        .getMethod("getMiChargePath", String.class)
        .invoke(miCharge, "fan_real_speed");

boolean written = (Boolean) type
        .getMethod("setMiChargePath", String.class, String.class)
        .invoke(miCharge, "target_level", "2");
```

下表中的“Java 调用”均假设已有 `IMiCharge miCharge` 对象。

## 风扇相关命令

| 操作 | 完整 `service call` 命令 | Java 调用 | 证据与说明 |
|---|---|---|---|
| 读取当前目标档位 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 18 s16 target_level` | `miCharge.getMiChargePath("target_level")` | 已在 `FanStateHandler` 中确认 |
| 读取风扇真实转速 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 18 s16 fan_real_speed` | `miCharge.getMiChargePath("fan_real_speed")` | 已在 `FanStateHandler` 中确认 |
| 写入风扇目标档位 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 43 s16 target_level s16 <0-4>` | `miCharge.setMiChargePath("target_level", "<0-4>")` | 已确认；会绕过官方策略 |

`FanStateHandler` 在传入通用写路径前只接受 `0` 到 `4` 的档位。这是 PowerKeeper 的应用层限制，并不代表所有 MICharge HAL 都保证接受这些值。

## 只读事务

大部分固定 Getter 不需要传参，返回字符串。能力探测类方法在 reply parcel 中返回整数/布尔值。通用读取和通用信息读取需要一个 UTF-16 字符串参数（`s16`）。

| AIDL 事务号 | 完整 `service call` 命令 | Java 调用 | 用途 |
|---:|---|---|---|
| 1 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 1` | `miCharge.getBatteryAuthentic()` | 电池认证状态 |
| 2 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 2` | `miCharge.getBatteryCapacity()` | 电池容量 |
| 3 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 3` | `miCharge.getBatteryChargeFull()` | 满充容量值 |
| 4 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 4` | `miCharge.getBatteryChargeType()` | 电池充电类型 |
| 5 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 5` | `miCharge.getBatteryCycleCount()` | 电池循环次数 |
| 6 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 6` | `miCharge.getBatteryIbat()` | 电池电流 |
| 7 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 7` | `miCharge.getBatteryResistance()` | 电池内阻 |
| 8 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 8` | `miCharge.getBatterySoh()` | 电池健康度 |
| 9 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 9` | `miCharge.getBatteryTbat()` | 电池温度 |
| 10 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 10` | `miCharge.getBatteryThermaLevel()` | 电池热等级 |
| 11 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 11` | `miCharge.getBatteryVbat()` | 电池电压 |
| 12 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 12` | `miCharge.getBtTransferStartState()` | 蓝牙传输启动状态 |
| 13 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 13` | `miCharge.getCarChargingType()` | 车载充电类型 |
| 14 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 14` | `miCharge.getChargingPowerMax()` | 最大充电功率 |
| 15 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 15` | `miCharge.getCoolModeState()` | MICharge 冷却模式状态，未证实等于 `fan_mode` |
| 16 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 16` | `miCharge.getFastChargeModeStatus()` | 快充模式状态 |
| 17 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 17` | `miCharge.getInputSuspendState()` | 充电输入挂起状态 |
| 18 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 18 s16 <name>` | `miCharge.getMiChargePath("<name>")` | 通用路径/键读取 |
| 19 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 19` | `miCharge.getNightChargingState()` | 夜间充电状态 |
| 20 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 20` | `miCharge.getPSValue()` | PS 值，厂商语义未知 |
| 21 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 21` | `miCharge.getPdApdoMax()` | USB PD APDO 最大值 |
| 22 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 22` | `miCharge.getPdAuthentication()` | USB PD 认证状态 |
| 23 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 23` | `miCharge.getQuickChargeType()` | 快充类型 |
| 24 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 24` | `miCharge.getSBState()` | SB 状态，厂商语义未知 |
| 25 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 25` | `miCharge.getSocDecimal()` | 小数电量 SOC |
| 26 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 26` | `miCharge.getSocDecimalRate()` | 小数 SOC 变化率 |
| 27 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 27` | `miCharge.getTxAdapt()` | TX 适配器信息 |
| 28 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 28` | `miCharge.getUsbCurrent()` | USB 电流 |
| 29 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 29` | `miCharge.getUsbVoltage()` | USB 电压 |
| 30 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 30` | `miCharge.getWirelessChargingStatus()` | 无线充电状态 |
| 31 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 31` | `miCharge.getWirelessFwStatus()` | 无线充电固件状态 |
| 32 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 32` | `miCharge.getWirelessReverseStatus()` | 无线反向充电状态 |
| 33 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 33` | `miCharge.isBatteryLifeFunctionSupported()` | 电池寿命功能是否支持 |
| 34 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 34` | `miCharge.isDPConnected()` | DisplayPort 连接状态 |
| 35 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 35 s16 <name>` | `miCharge.isFunctionSupported("<name>")` | 通用厂商功能支持探测 |
| 36 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 36` | `miCharge.isUSB32()` | USB 3.2 能力/状态 |
| 37 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 37` | `miCharge.isWirelessChargingSupported()` | 无线反向充电是否支持 |
| 38 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 38` | `miCharge.isWiressFwUpdateSupported()` | 无线固件更新是否支持；MIUI 方法名拼写为 `Wiress` |
| 51 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 51 s16 <event>` | `miCharge.getTypeCCommonInfo("<event>")` | Type-C 通用信息读取 |
| 53 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 53 s16 <event>` | `miCharge.getChargeCommonInfo("<event>")` | 充电通用信息读取 |
| 55 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 55 s16 <event>` | `miCharge.getBatteryCommonInfo("<event>")` | 电池通用信息读取 |

ROM 封装未定义通用信息接口接受的 `<event>` 词表，因此不能把任意事件名视为受支持的命令。

## 写入事务

事务 `39` 到 `48` 与 `50` 中，Java 封装会把值序列化为字符串，因此 `service call` 使用 `s16 <value>`。事务 `49` 序列化为布尔值，使用 `i32 0` 或 `i32 1`。通用信息写入需要两个 UTF-16 字符串。

| AIDL 事务号 | 完整 `service call` 命令 | Java 调用 | 用途与风险 |
|---:|---|---|---|
| 39 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 39 s16 <state>` | `miCharge.setBtState(<state>)` | 设置充电逻辑使用的蓝牙状态 |
| 40 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 40 s16 <state>` | `miCharge.setBtTransferStartState(<state>)` | 设置蓝牙传输启动状态 |
| 41 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 41 s16 <state>` | `miCharge.setCoolModeState(<state>)` | 设置 MICharge 冷却模式，未证实为风扇控制 |
| 42 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 42 s16 <state>` | `miCharge.setInputSuspendState("<state>")` | 修改充电输入挂起状态 |
| 43 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 43 s16 <name> s16 <value>` | `miCharge.setMiChargePath("<name>", "<value>")` | 通用路径/键写入，包含风扇 `target_level` |
| 44 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 44 s16 <state>` | `miCharge.setNightChargingState(<state>)` | 修改夜间充电状态 |
| 45 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 45 s16 <value>` | `miCharge.setRxCr("<value>")` | 设置 RXCR 厂商值，语义未知 |
| 46 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 46 s16 <state>` | `miCharge.setSBState(<state>)` | 设置 SB 状态，语义未知 |
| 47 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 47 s16 <state>` | `miCharge.setSmCountReset(<state>)` | 重置/设置 SM 计数状态 |
| 48 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 48 s16 <state>` | `miCharge.setUpdateWirelessFw(<state>)` | 无线固件更新控制，不要手动调用 |
| 49 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 49 i32 <0-or-1>` | `miCharge.setWirelessChargingEnabled(<boolean>)` | 开启/关闭无线反向充电 |
| 50 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 50 s16 <state>` | `miCharge.setWlsTxSpeed(<state>)` | 设置无线 TX 速度/状态 |
| 52 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 52 s16 <event> s16 <value>` | `miCharge.setTypeCCommonInfo("<event>", "<value>")` | Type-C 通用事件写入 |
| 54 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 54 s16 <event> s16 <value>` | `miCharge.setChargeCommonInfo("<event>", "<value>")` | 充电通用事件写入 |
| 56 | `adb shell service call vendor.xiaomi.hardware.micharge.IMiCharge/default 56 s16 <event> s16 <value>` | `miCharge.setBatteryCommonInfo("<event>", "<value>")` | 电池通用事件写入 |

`setChargeCommonInfo("callAiChargePrediction", value)` 在 MIUI 封装内走特殊内部逻辑，但仍使用事务 `54`，并传入两个字符串参数。

## 已确认的通用键

| 键 | 访问方式 | Java 调用 | 已观察到的用途 |
|---|---|---|---|
| `target_level` | 读/写 | `getMiChargePath` / `setMiChargePath` | 风扇目标档位 |
| `fan_real_speed` | 读 | `getMiChargePath` | 风扇实际转速 |
| `is_support_mca` | 读 | `getMiChargePath` | Dynamic Turbo 能力探测 |
| `is_support_dtpt` | 读 | `getMiChargePath` | Dynamic Turbo 能力探测 |
| `plate_shock` | 写 | `setMiChargePath` | Telephony 侧厂商用途，与风扇无关 |

通用路径服务可能支持额外的厂商键，但它们实现在专有 HAL 中，而不是这个 Java 封装里。建议仅做只读验证；任意写入可能影响硬件行为。

## 源码依据

- AIDL 事务常量和完整封装 API：[IMiCharge.smali](reverse/miui-framework-smali/miui/util/IMiCharge.smali)。
- AIDL 通用读取 `18`：`IMiCharge.getMiChargePath(String)`。
- AIDL 通用写入 `43`：`IMiCharge.setMiChargePath(String, String)`。
- 风扇使用路径：[FanStateHandler.smali](reverse/powerkeeper-smali/com/miui/powerkeeper/unionpower/corehandler/FanStateHandler.smali)，其中读取/写入 `target_level`，并读取 `fan_real_speed`。

旧 HIDL 服务的通用路径读写事务号分别为 `47` 和 `46`。本文件不提供 HIDL 完整命令表，因为目标设备暴露的是上述 AIDL 服务。
