# reverse 目录 Smali 逆向分析报告

## 目录结构

| 目录 | 内容 | 对应 Hook 目标 |
|------|------|----------------|
| `settings-smali/` | Settings APK 的 smali（17个 coolingfan 相关类） | `FanModeController.smali` 等 |
| `powerkeeper-smali/` | PowerKeeper APK 的 smali | `FanStateHandler.smali` + 10 个内部类 |
| `systemui-cooling/` | SystemUI 的 smali（3个 dex 分包） | `CoolingFanController`、`CoolingFanTile`、`CoolingFanDetailAdapter` |
| `MISettings/` | MiSettings APK 完整反编译 | 分应用配置页面宿主 |
| `Settings/` | Settings APK 完整反编译（12个 dex） | 全量参考 |
| `PowerKeeper/` | PowerKeeper APK 完整反编译 | 全量参考 |
| `systemui-smali/` | 空目录 | 预留 |

---

## 1. Settings 侧 (17个 smali)

### `FanModeController.smali` — 风扇模式控制器

- 继承 `BasePreferenceController`，实现 `OnPreferenceChangeListener`
- **字段**:
  - `mContentObserver` — ContentObserver
  - `mDropDownPreferenceRef` — WeakReference<DropDownPreference>
  - `mCachedMode` — 静态 int

- **关键方法**:
  - `getCurrentMode()` — 读 `Settings.System["fan_mode"]`，默认 `-1`，写入 `mCachedMode` 并返回字符串
  - `displayPreference(PreferenceScreen)` — 找到 `DropDownPreference` 存入弱引用
  - `updateState(Preference)` — 调 `getCurrentMode()` 更新下拉选中值
  - `onPreferenceChange(Preference, Any)` — `parseInt(value)` → `Settings.System.putInt("fan_mode", mode)`，同时上报埋点
  - `onResume()` / `onPause()` — 注册/注销 `cooling_fan_enable` 和 `fan_mode` 的 ContentObserver
  - `updateFanModeState()` — 读 `cooling_fan_enable`，若关闭则 disable 下拉

### `FanModeController$FanModeContentObserver` — 监听 fan_mode 和 cooling_fan_enable 变化

### `CoolingFanEnableController.smali` — 散热风扇总开关

- 字段: `mSwitchPreferenceRef` (WeakReference<SwitchPreference>)
- `isFanEnabled(Context)` — 读 `Settings.System["cooling_fan_enable"]`，默认 1（开）
- `onPreferenceChange()` — `putInt("cooling_fan_enable", enabled)`，上报埋点
- `updateFanEnableState()` — 同步 SwitchPreference checked 状态

### `FanModeRangeController.smali` — 使用范围（全场景/游戏场景）

### `FanSceneGameController.smali` — 游戏场景开关

### `FanSceneRapidChargeController.smali` — 快充场景开关

### `FanSceneOutdoorController.smali` — 户外场景开关

### `FanSmartStopOnRecordingController.smali` — 录音智能停转开关

### `FanSelfCleanController.smali` — 自清洁开关

### `MiuiCoolingFanSettings.smali` — 散热风扇设置 Fragment（XML: `cooling_fan_settings.xml`）

### `CoolingFanTrackHelper.smali` — 埋点上报工具

其余为 ContentObserver 内部类和 Lambda。

---

## 2. PowerKeeper 侧 (11个 smali)

### `FanStateHandler.smali` — 核心风扇策略引擎（10068行）

#### 字段映射（HookEntry.kt 中引用的混淆字段名）

| 字段 | 类型 | 含义 |
|------|------|------|
| `g` | ContentResolver | 主 ContentResolver |
| `h` | ContentResolver | 备用 ContentResolver |
| `j` | int | 当前 fan_mode 值 |
| `k` | int | 上次 fan_mode |
| `l` | int | fan_mode_range |
| `w` | String | 当前 thermal 场景（如 "game", "yuanshen" 等） |
| `x` | String | 前台应用包名 |
| `p` | int | 当前 target_level |
| `y` | boolean | gameSwitch（fan_mode_scene_gaming） |
| `z` | boolean | chargeSwitch（fan_mode_scene_rapid_charge） |
| `A` | boolean | naviSwitch（fan_mode_scene_navigation） |
| `B` | boolean | micSwitch（fan_smart_stop_on_recording） |
| `C` | boolean | fastChargeActive（快充激活） |
| `D` | boolean | navigationScene（导航场景） |
| `E` | boolean | huanjiActive（换机场景） |
| `F` | boolean | recordingActive（录音中） |
| `G` | boolean | voiceAssistActive |
| `L` | boolean | screenLocked（息屏/锁屏） |
| `M` | boolean | simCallActive（SIM 通话） |
| `N` | boolean | earpieceActive（听筒模式） |
| `I` | boolean | cooling_fan_enable |
| `h0` | PowerManager | 电源管理 |
| `k0` | Set\<String\> | 游戏场景标识集（game, pubg, yuanshen, xingtie 等） |
| `l0` | Set\<String\> | 高性能场景集（arvr, noLimits） |
| `m0` | Map\<String, Integer\> | 导航应用包名 → uid 映射 |

#### 关键方法

**`J(String path, String value)`**
通过 `IMiCharge.setMiChargePath()` 写入底层节点（如 `target_level`）。失败时上报 `FAN_SET_LEVEL_FAIL`。

**`W(FanStateHandler$g state)`**
核心策略判断：检查 `k0`（游戏场景）、`l0`（高性能场景）、导航/快充/录音/通话等高优先级停转场景，决定写 `"target"` 还是 `"other"` 场景。若录音+语音助手、SIM通话+听筒、锁屏+非快充等情况则停转。

**`V(String scene, FanStateHandler$g state)`**
根据场景和 fan_mode 决定 target_level，最终调用 `J("target_level", level)`。

**`o()`**
重新读取所有 Settings.System 配置：
- `cooling_fan_enable` → `I`
- `fan_mode` → `j`
- `fan_mode_range` → `l`
- `fan_mode_scene_gaming` → `y`
- `fan_mode_scene_rapid_charge` → `z`
- `fan_mode_scene_navigation` → `A`
- `fan_smart_stop_on_recording` → `B`
- `fan_self_clean` → `P`

**`l()`**
构建 FanStateHandler$g 快照，从实例字段复制到 state 对象。

**`C()`**
采集当前状态：读取 `board_sensor_temp`，前台包名，thermal 场景，快充类型，导航/换机服务状态。

**`G(ContentResolver)`**
注册 7 个 ContentObserver 监听 `cooling_fan_enable`/`fan_mode`/`fan_mode_range`/`fan_mode_scene_gaming`/`fan_mode_scene_rapid_charge`/`fan_mode_scene_navigation`/`fan_smart_stop_on_recording`/`fan_self_clean`。

**`B()`**
当 `I`（总开关）&& `H`（某条件）时，调 `l()` 构建快照 → `W(state)` 执行策略。

### `FanStateHandler$g.smali` — FanStateSnapshot 数据类

字段：

| 字段 | 含义 |
|------|------|
| `a` | mode (fan_mode) |
| `b` | range (fan_mode_range) |
| `c` | temp (board_sensor_temp) |
| `d` | — |
| `e` | pkg (前台应用包名) |
| `f` | scenario (thermal 场景) |
| `g` | screenLock (息屏/锁屏) |
| `h` | microstate (录音中) |
| `i` | voiceassist (语音助手) |
| `j` | fastCharge (快充激活) |
| `k` | navigation (导航场景) |
| `l` | huanji (换机场景) |
| `m` | gameEnable (游戏场景开关) |
| `n` | chargeEnable (快充场景开关) |
| `o` | naviEnable (导航场景开关) |
| `p` | micEnable (录音停转开关) |
| `q` | inSimCall (SIM 通话) |
| `r` | isEarpiece (听筒模式) |

### 其余内部类

| 内部类 | 用途 |
|--------|------|
| `$a` | KeyguardLockedStateListener |
| `$b` | OnCommunicationDeviceChangedListener |
| `$c` / `$d` / `$f` | BroadcastReceiver（用户切换、错误状态等） |
| `$e` | ContentObserver（配置变化） |
| `$h` | FanStateData 构建相关 |
| `$i` | 自清洁相关 |

---

## 3. SystemUI 侧 (3个 smali)

### `CoolingFanController.smali` — 控制中心风扇控制器

- 字段：
  - `_secondaryItems` — `List<SelectableItem>`
  - `fanModeState` — int
  - `toggleState` — boolean
  - `systemSettings` — SystemSettingsImpl
  - `supportCoolingFan$delegate` — Lazy\<Boolean\>

- **构造函数**初始化 3 个 SelectableItem：

| identity | 含义 | titleRes | iconRes | trackIndex |
|----------|------|----------|---------|------------|
| `-1` | 智能调频 | `0x7f140c70` | `0x7f0812cc` | 0 |
| `0` | 高速强冷 | `0x7f140c6e` | `0x7f0812c9` | 1 |
| `1` | 静谧模式 | `0x7f140c6f` | `0x7f0812cb` | 2 |

- `fanModeState` 初始值：`systemSettings.getInt(-1, userId, "fan_mode")`
- `toggleFanState()` — 翻转 toggleState → `putIntForUser("cooling_fan_enable")`

### `CoolingFanTile.smali` — 控制中心磁贴

- 字段：`coolingFanController`, `detailAdapter`, `systemUIStat`
- `handleClick()` — `toggleFanState()` + `refreshState()`
- `handleSecondaryClick()` — `showDetail(true)` + 若未开启则开启 + `updateItems()`
- `getCoolingFanSettingsIntent()` — Intent 指向 `com.android.settings.SubSettings`，fragment = `MiuiCoolingFanSettings`
- `isAvailable()` — `supportCoolingFan$delegate.getValue()`

### `CoolingFanTile$CoolingFanDetailAdapter.smali` — 二级页适配器

- 字段：`detailView`(QSDetailContent), `this$0`(CoolingFanTile)
- `updateItems()`：
  - 若 `toggleState=false` → `setEmptyState(0x7f0812c6, 0x7f140c71)` + 空列表
  - 若 `toggleState=true` → 遍历 `_secondaryItems` 生成 `QSDetailContent$SelectableItem`，selected 基于 `identity == fanModeState`
- `onDetailItemClick()` — 读取 `SelectableItem.identity` → `fanModeState = identity` → `systemSettings.putIntForUser("fan_mode", identity)` + `updateItems()`

---

## 4. MISettings 侧

`MISettings/` 包含完整的 `com.xiaomi.misettings` APK 反编译（smali/ + smali_classes2/），模块通过 Hook `HighRefreshOptionsActivity` 和 `HighRefreshOptionsFragment` 来实现分应用风扇配置页面。

关键类：
- `com.xiaomi.misettings.display.RefreshRate.HighRefreshOptionsActivity`
- `com.xiaomi.misettings.display.RefreshRate.HighRefreshOptionsFragment`
- `com.xiaomi.misettings.display.RefreshRate.AppSearchFragment`
- `oa.j` — FollowViewHolder
- `oa.h` — AppItemModel

---

## 5. 完整 APK 反编译

| 目录 | 说明 |
|------|------|
| `Settings/` | 12 个 dex，完整反编译，用于参考所有 Settings 相关类 |
| `PowerKeeper/` | 完整反编译，用于参考 PowerKeeper 全部逻辑 |

---

## 与 HookEntry.kt 的对应关系

这些 smali 就是 `HookEntry.kt` 中所有反射字段名、方法签名和类名的来源：

| HookEntry.kt 常量 | Smali 来源 |
|--------------------|------------|
| `FAN_MODE_CONTROLLER` | `settings-smali/.../FanModeController.smali` |
| `FAN_STATE_HANDLER` | `powerkeeper-smali/.../FanStateHandler.smali` |
| `SYSTEMUI_COOLING_FAN_CONTROLLER` | `systemui-cooling/classes1/.../CoolingFanController.smali` |
| `SYSTEMUI_COOLING_FAN_DETAIL_ADAPTER` | `systemui-cooling/classes2/.../CoolingFanTile$CoolingFanDetailAdapter.smali` |
| `COOLING_FAN_ENABLE_CONTROLLER` | `settings-smali/.../CoolingFanEnableController.smali` |
| `MISETTINGS_HIGH_REFRESH_OPTIONS_ACTIVITY` | `MISettings/smali/.../HighRefreshOptionsActivity.smali` |
| `MISETTINGS_HIGH_REFRESH_OPTIONS_FRAGMENT` | `MISettings/smali/.../HighRefreshOptionsFragment.smali` |
| `MISETTINGS_FOLLOW_VIEW_HOLDER` | `MISettings/smali/oa/j.smali` |
| `MISETTINGS_APP_ITEM_MODEL` | `MISettings/smali/oa/h.smali` |

---

*生成时间：2026-07-09*
