# Changelog / 更新日志

## 1.1.0 - 2026-07-11

### 安装说明

- 由于构建环境迁移，旧 release 签名凭据已丢失，本版本使用重新生成的 release 签名密钥。
- Android 无法将本版本作为旧签名版本的覆盖升级包安装。
- 如果你已经安装过旧版本，请先卸载旧版本，再安装 `1.1.0`。

### 新增

- 新增自定义风扇模式，支持按应用配置风扇档位。
- 新增基于 MISettings 宿主的按应用配置页面，并支持应用搜索。
- 新增针对 Settings、PowerKeeper、SystemUI、MISettings、息屏行为和自定义策略语义的回归验证清单（项目内部维护使用，不再随 GitHub 文档发布）。
- 新增 Gradle wrapper 和适配本地工具链的构建脚本。

### 调整

- 狂暴模式导航逻辑对齐官方场景语义：仅打开导航 App 不会持续启动风扇；进入真实导航后，需要 PowerKeeper 导航场景状态和前台导航包同时成立。
- 保留快充/导航场景保底语义：应用级关闭风扇或静谧模式不会压低这些场景档位，高于场景档位的应用规则仍可覆盖。
- 明确快充高热息屏散热属于预期行为。
- 将逆向分析内容合并进 `知识图谱.md`，删除重复的独立 smali 分析文档。

### 修复

- 修复高德等导航 App 在真实导航中短暂触发录音状态后，狂暴模式风扇被误停的问题。
- 废弃 `fan_mode=4` 伪装为官方 `fan_mode=2` 的实验路径；该路径在测试设备上会导致 PowerKeeper 持续写入 `target_level=0`。
- 加固自定义场景兜底逻辑，使其遵守风扇总开关、录音智能停转、SIM 通话 + 听筒等硬停止条件。
- 将 SystemUI 与 MISettings 的脆弱 Hook 拆分为独立可选安装块，避免单个混淆类或方法签名变化拖垮整条功能链路。
- 修复 MISettings RecyclerView 布局计算期间刷新列表导致的崩溃风险。

### 验证

- Debug 构建已通过：`bash scripts/build-debug.sh`。
- Release 构建已通过并上传到 GitHub Release。
- 用户已在真机手动验证狂暴模式导航场景修复成功。

---

### Installation Notice

- This release is signed with a newly generated release key because the previous build environment's signing credentials were lost during migration.
- Android cannot install this APK as an in-place update over versions signed with the old key.
- If you already installed an older release, uninstall the old app first, then install `1.1.0`.

### Added

- Added custom fan mode with per-app fan level rules.
- Added MISettings-backed per-app fan configuration page and app search support.
- Added focused internal regression checks for Settings, PowerKeeper, SystemUI, MISettings, screen-off behavior, and custom policy semantics.
- Added Gradle wrapper and local toolchain-friendly build scripts.

### Changed

- Aligned extreme mode navigation behavior with official scene semantics: opening a navigation app alone does not keep the fan running; active navigation requires PowerKeeper's navigation scene state and a foreground navigation package.
- Preserved rapid-charge/navigation scene floor behavior: app-level off or quiet rules do not reduce those scene floors, while higher app levels can still override them.
- Documented fast-charge screen-off cooling behavior as expected high-heat handling.
- Consolidated reverse-engineering notes into `知识图谱.md` and removed the duplicate standalone smali analysis document.

### Fixed

- Fixed extreme mode navigation stop regression caused by navigation apps temporarily setting PowerKeeper's recording-active flag during real navigation.
- Removed the attempted `fan_mode=4` compatibility path that caused PowerKeeper to keep writing `target_level=0` on the tested device.
- Hardened custom scene fallback so it respects global fan disable, recording smart stop, and SIM call plus earpiece hard stops.
- Split fragile SystemUI and MISettings hooks into independent optional install blocks so one obfuscated method/class mismatch does not disable the whole feature path.
- Avoided MISettings RecyclerView layout-time refresh crashes by posting list refresh work safely.

### Verification

- Debug build verified with `bash scripts/build-debug.sh`.
- Release build completed and uploaded to GitHub Releases.
- User manually verified the extreme mode navigation fix on device.
