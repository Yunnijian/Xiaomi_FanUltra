# Changelog

## 1.1.0 - 2026-07-11

### Installation Notice

- This release is signed with a newly generated release key because the previous build environment's signing credentials were lost during migration.
- Android cannot install this APK as an in-place update over versions signed with the old key.
- If you already installed an older release, uninstall the old app first, then install `1.1.0`.

### Added

- Added custom fan mode with per-app fan level rules.
- Added MISettings-backed per-app fan configuration page and app search support.
- Added focused regression checklist for Settings, PowerKeeper, SystemUI, MISettings, screen-off behavior, and custom policy semantics.
- Added Gradle wrapper and local toolchain-friendly build scripts.

### Changed

- Aligned extreme mode navigation behavior with official scene semantics: opening a navigation app alone does not keep the fan running; active navigation requires PowerKeeper's navigation scene state and a foreground navigation package.
- Preserved rapid-charge/navigation scene floor behavior: app-level off or quiet rules do not reduce those scene floors, while higher app levels can still override them.
- Documented fast-charge screen-off cooling behavior as expected high-heat handling.
- Consolidated reverse-engineering notes into `知识图谱.md` and removed the duplicate standalone smali analysis document.

### Fixed

- Fixed extreme mode navigation stop regression caused by navigation apps temporarily setting PowerKeeper's recording-active flag during real navigation.
- Fixed an attempted `fan_mode=4` compatibility path that caused PowerKeeper to keep writing `target_level=0` on the tested device; the stable path now uses guarded module scene checks.
- Hardened custom scene fallback so it respects global fan disable, recording smart stop, and SIM call plus earpiece hard stops.
- Split fragile SystemUI and MISettings hooks into independent optional install blocks so one obfuscated method/class mismatch does not disable the whole feature path.
- Avoided MISettings RecyclerView layout-time refresh crashes by posting list refresh work safely.

### Verification

- Debug build verified with `bash scripts/build-debug.sh`.
- User manually verified the extreme mode navigation fix on device.
