# Changelog / 更新日志

## 1.2.1 - 2026-07-24

### 修复

- 修复「应用配置」选择后下拉刷新恢复默认：规则先写入模块 `SharedPreferences`（UI 必持久），再同步 `Settings.System`（PowerKeeper）；API 无权限时 root `settings` / 自动放行 `WRITE_SETTINGS`

### 版本

- `1.2.1` / versionCode `7`

---

## 1.2.0 - 2026-07-24

### 安装说明

- 版本：`1.2.0` / versionCode `6`
- 本版本为 release 签名包。若设备上已安装 **debug 签名** 或其它密钥的旧包，无法直接覆盖安装，需先卸载再安装。
- 按应用规则保存在 `Settings.System`（`mifan_per_app_fan_rules`），卸载模块应用后规则通常仍会保留。
- 安装后请在 LSPosed 中确认模块已启用，作用域为 **Settings / PowerKeeper / SystemUI**（不再需要 MISettings）。

### 新增

- **独立「应用配置」页**（`FanAppsActivity`）
  - UI 对齐 [KernelSU](https://github.com/tiann/KernelSU) SuperUser 页：Compose + **MIUIX 0.9.3**
  - 标题为「应用配置」；无二级详情页
  - 右上角 **排序**（名称 / 包名 / 安装时间 / 更新时间，可倒序）与 **更多**（显示系统应用等）
  - 完整搜索（含拼音辅助）
  - 去掉日志入口；行内用 **MIUIX `WindowDropdownPreference`（↕）** 选择风扇模式，不再使用彩色 StatusTag 芯片
  - 工具栏菜单使用 **`WindowIconDropdownMenu`**（窗口级，独立 Activity 内可用）
  - 列表 **懒加载**：先出脚手架再后台扫包，避免白屏/卡顿
  - 应用扫描对齐 SuperUser：`FLAG_HAS_CODE`，不扫未安装包
- 设置项入口改为打开模块自有 Activity（失败时仍可回退对话框）

### 调整

- **不再 Hook MISettings**；`scope.list` 仅保留：
  - `com.android.settings`
  - `com.miui.powerkeeper`
  - `com.android.systemui`
- 自定义模式下，未单独配置的应用在 UI 仍为「智能调频」，但硬件 **`target_level` 下限为 1**，避免官方智能策略长期卡在 0 档
- 规则写入走 root/`settings` 路径并做乐观 UI 更新，减轻下拉选择后的卡顿与失败竞态
- 工程拆分为 `hooks/`、`rom/`、`ui/`、`policy/` 等模块结构；接入 Compose / MIUIX / AppIconLoader

### 修复

- **DexKit 在 LSPosed 下加载失败**：除 `System.loadLibrary` 外，依次尝试 APK 旁已解压 `libdexkit.so`、从模块 APK 提取后再 `System.load`，避免宿主 ClassLoader 找不到 native 库导致符号解析失效
- 修复按应用配置页白屏、列表主线程卡顿、错误 Adapter 等问题（迁移至模块自有页后闭环）
- 修复风扇模式选择崩溃、工具栏菜单无效等 MIUIX 弹层问题（`ThemeController`、NoActionBar、窗口级菜单/下拉）

### 其它

- `minSdk` 提升至 **33**（配合 MIUIX 0.9.3 blur 等能力）
- `compileSdk` **37**；release 开启 R8 压缩与资源收缩

---

### Installation Notice

- Version: `1.2.0` / versionCode `6`
- Signed release build. If a debug-signed or differently-signed package is already installed, uninstall it first, then install this APK.
- Per-app rules live in `Settings.System` (`mifan_per_app_fan_rules`) and usually survive uninstalling the module APK.
- After install, enable the module in LSPosed with scope **Settings / PowerKeeper / SystemUI** only (MISettings is no longer required).

### Added

- Standalone **App Config** page (`FanAppsActivity`)
  - UI ported from KernelSU SuperUser: Compose + **MIUIX 0.9.3**
  - Title: 「应用配置」; no secondary detail page
  - Sort + more menus; full search (pinyin-assisted)
  - No log entry; row mode via **`WindowDropdownPreference`** (no StatusTag chips)
  - Toolbar menus via **`WindowIconDropdownMenu`**
  - Lazy app list load (scaffold first, scan packages off the UI thread)
  - Package filter aligned with SuperUser (`FLAG_HAS_CODE` only)
- Settings preference opens the module-owned Activity (dialog fallback retained)

### Changed

- **No MISettings hooks**; scope is Settings, PowerKeeper, and SystemUI only
- In custom mode, UI default remains “smart”, but hardware **`target_level` is floored to 1** so official smart policy does not leave the fan stuck at 0
- Rule writes use root/`settings` with optimistic UI updates
- Codebase split into hooks/rom/ui/policy; Compose + MIUIX stack

### Fixed

- **DexKit load under LSPosed**: fall back from `System.loadLibrary` to extracted `libdexkit.so` next to the module APK, then extract-from-APK + `System.load`
- White screen / main-thread lag on the per-app page (resolved by the module-owned UI)
- Mode-select crashes and dead toolbar menus (ThemeController, NoActionBar, window-level MIUIX menus)

### Other

- `minSdk` **33**; `compileSdk` **37**; release R8 + resource shrinking

---

## 1.1.0 - 2026-07-11

### 安装说明

- 由于构建环境迁移，旧 release 签名凭据已丢失，本版本使用重新生成的 release 签名密钥。
- Android 无法将本版本作为旧签名版本的覆盖升级包安装。
- 如果你已经安装过旧版本，请先卸载旧版本，再安装 `1.1.0`。

### 新增

- 新增自定义风扇模式，支持按应用配置风扇档位。
- 新增基于 MISettings 宿主的按应用风扇配置页面，并支持应用搜索。

### 调整

- 狂暴模式导航逻辑对齐官方场景语义：仅打开导航 App 不会持续启动风扇；进入真实导航后，需要 PowerKeeper 导航场景状态和前台导航包同时成立。
- 保留快充/导航场景保底语义：应用级关闭风扇或静谧模式不会压低这些场景档位，高于场景档位的应用规则仍可覆盖。

### 修复

- 修复高德等导航 App 在真实导航中短暂触发录音状态后，狂暴模式风扇被误停的问题。
- 废弃 `fan_mode=4` 伪装为官方 `fan_mode=2` 的实验路径；该路径在测试设备上会导致 PowerKeeper 持续写入 `target_level=0`。
- 加固自定义场景兜底逻辑，使其遵守风扇总开关、录音智能停转、SIM 通话 + 听筒等硬停止条件。
- 修复部分 ROM 上 SystemUI 或 MISettings 混淆类/方法差异导致功能链路整体失效的问题。
- 修复 MISettings 列表刷新时可能触发 RecyclerView 布局计算期崩溃的问题。

---

### Installation Notice

- This release is signed with a newly generated release key because the previous build environment's signing credentials were lost during migration.
- Android cannot install this APK as an in-place update over versions signed with the old key.
- If you already installed an older release, uninstall the old app first, then install `1.1.0`.

### Added

- Added custom fan mode with per-app fan level rules.
- Added an MISettings-backed per-app fan configuration page with app search support.

### Changed

- Aligned extreme mode navigation behavior with official scene semantics: opening a navigation app alone does not keep the fan running; active navigation requires PowerKeeper's navigation scene state and a foreground navigation package.
- Preserved rapid-charge/navigation scene floor behavior: app-level off or quiet rules do not reduce those scene floors, while higher app levels can still override them.

### Fixed

- Fixed extreme mode navigation stop regression caused by navigation apps temporarily setting PowerKeeper's recording-active flag during real navigation.
- Removed the attempted `fan_mode=4` compatibility path that caused PowerKeeper to keep writing `target_level=0` on the tested device.
- Hardened custom scene fallback so it respects global fan disable, recording smart stop, and SIM call plus earpiece hard stops.
- Fixed feature-chain failures on some ROMs when SystemUI or MISettings obfuscated classes/method signatures differ.
- Fixed a MISettings RecyclerView layout-time refresh crash risk.
