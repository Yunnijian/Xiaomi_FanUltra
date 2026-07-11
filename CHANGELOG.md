# Changelog / 更新日志

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
