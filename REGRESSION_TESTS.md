# Regression Tests

This checklist is for validating Xiaomi_FanUltra after code changes. It focuses on the high-risk LSPosed hook paths: Settings, PowerKeeper, SystemUI, and MISettings.

## Prerequisites

- Module APK is installed on the test device.
- LSPosed module is enabled.
- Scope includes:
  - `com.android.settings`
  - `com.miui.powerkeeper`
  - `com.android.systemui`
  - `com.xiaomi.misettings`
- After installing a new APK, restart the scoped processes or reboot the device.

Useful commands:

```sh
adb shell am force-stop com.android.settings
adb shell am force-stop com.xiaomi.misettings
adb shell am force-stop com.miui.powerkeeper
adb shell am force-stop com.android.systemui
```

## Log Capture

Before manual testing, clear and capture logcat:

```sh
adb logcat -c
adb logcat -v time | grep -iE 'FanModeHook|FanStateHandler|target_level|fan_mode|CoolingFan|MISettings|HighRefresh|AppSearchFragment|RecyclerView'
```

For MISettings-only validation:

```sh
adb logcat -c
adb logcat -v time | grep -iE 'com.xiaomi.misettings|FanModeHook|HighRefresh|AppSearchFragment|RecyclerView|MISettings'
```

## 1. Settings Page Hook

Steps:

1. Open the system cooling fan settings page.
2. Confirm `狂暴模式` appears in the fan mode list.
3. Confirm `自定义` appears in the fan mode list.
4. Select `自定义`.

Expected:

- UI shows custom mode while the real `fan_mode` stays `-1`.
- `按应用配置风扇模式` entry is visible.
- Usage range is hidden in custom mode.
- Rapid charge and navigation scene switches remain visible.

Useful log signals:

```text
FanModeController.getCurrentMode spoofed for UI: return custom(5) while real fan_mode=-1
Custom mode scene preferences visibility updated
Per-app fan config entry visibility updated: visible=true
```

## 2. MISettings Per-App Config Page

Steps:

1. From the Settings cooling fan page, tap `按应用配置风扇模式`.
2. Wait for the app list to load.
3. Tap one app row.
4. Change its fan level.
5. Search for an app name or package name.
6. Return to the cooling fan page.

Expected:

- MISettings high refresh host opens as the fan config page.
- App list is replaced with fan config items.
- No RecyclerView layout-time crash occurs.
- Search remains usable.
- Changing an app fan level updates `mifan_per_app_fan_rules`.

Useful log signals:

```text
Open per-app fan config page in MISettings host
MISettings high refresh host opened for fan config
MISettings high refresh fragment list replaced for fan config
Per-app fan rule updated
```

Failure signals to watch for:

```text
Rebuild MISettings fan config list failed
Notify MISettings fan config list failed
Cannot call this method while RecyclerView is computing a layout or scrolling
FATAL EXCEPTION
```

## 3. Per-App Policy Semantics

Steps:

1. Enter custom mode.
2. Configure a foreground app as `关闭风扇` or `静谧模式`.
3. Trigger rapid charge or navigation scene.
4. Configure another foreground app as `狂暴模式`.
5. Trigger rapid charge or navigation scene again.

Expected:

- Rapid charge/navigation scenes keep their floor level, normally `2`.
- App levels lower than `2`, including off and quiet, do not lower the rapid charge/navigation floor.
- App level `4` can override the scene floor.
- Scene fallback must still respect hard stops: global fan disable, recording smart stop, and SIM call + earpiece.

Useful log signals:

```text
Custom fan rapid-charge scene fallback matched
Custom fan navigation scene fallback matched
Custom per-app fan policy applied
Custom fan app/scene max applied
```

## 4. Extreme Mode And Smart Bridge

Steps:

1. Select `狂暴模式` from Settings.
2. Wait 2-3 seconds.
3. Confirm fan mode and target level.
4. In partial-scene range, return to launcher and confirm the fan stops.
5. Open a navigation app without entering real navigation and confirm the fan does not keep spinning.
6. Start real/simulated navigation and confirm the fan starts.
7. Return to launcher and confirm the fan stops.
8. Switch back to `智能调频`.
9. Wait at least 1 second.

Expected:

- Extreme mode uses the module's guarded scene decision to drive `target_level=4` when the selected usage range and enabled scene switches allow it.
- Navigation behavior matches official modes: merely entering a navigation app must not start the fan; active navigation requires PowerKeeper's navigation-scene flag and a foreground navigation package.
- Navigation apps may temporarily set PowerKeeper's recording-active flag during real navigation; this must not stop the fan when the navigation scene is confirmed.
- Returning to launcher or leaving the active scene writes `target_level=0` and stops the fan.
- When switching from extreme to smart, PowerKeeper performs the bridge:
  - write `target_level=2`
  - temporarily write `fan_mode=2`
  - delay about 800 ms
  - write `fan_mode=-1`
- Fan should not remain stuck at the highest speed after returning to smart mode.

Useful commands:

```sh
adb shell settings get system fan_mode
adb shell cat /sys/devices/platform/soc/soc:xiaomi_fan/target_level
adb shell cat /sys/devices/platform/soc/soc:xiaomi_fan/real_speed
```

Useful log signals:

```text
Extreme fan policy applied by PowerKeeper J(target_level, 4)
Extreme fan policy applied by PowerKeeper J(target_level, 0)
Extreme -> smart bridge in PowerKeeper: target_level=2, fan_mode=2, then fan_mode=-1
Extreme -> smart PowerKeeper bridge completed: fan_mode=-1
```

Rejected experiment:

- Do not spoof `fan_mode=4` as an official mode such as `fan_mode=2` inside PowerKeeper.
- On the tested device, PowerKeeper still wrote only `target_level=0` after that spoof, causing extreme mode to stop in all scenes.
- The stable fix is the guarded `shouldForceExtremeLevel()` path plus the navigation-specific recording exception above.

## 5. SystemUI Cooling Fan Tile

Steps:

1. Open Control Center.
2. Open the cooling fan tile detail page.
3. Confirm `狂暴模式` and `自定义` are shown.
4. Tap `自定义`.
5. Tap a non-custom fan mode.

Expected:

- Custom mode uses scheme B: UI shows custom, real `fan_mode` remains `-1`.
- Temporary bridge states do not visibly flash as the wrong mode.
- Leaving custom mode restores normal state.

Useful log signals:

```text
SystemUI custom fan mode selected; keep fan_mode=-1 and enable mifan custom mode
SystemUI bridge fan mode masked
SystemUI exit custom mode
SystemUI cooling fan detail items rebuilt
```

## 6. Screen-Off Behavior

Steps:

1. Disable `息屏时保持风扇开启`.
2. Test a normal non-charging screen-off or lock-screen scenario.
3. Enable `息屏时保持风扇开启`.
4. Repeat the normal screen-off scenario.
5. Test rapid-charge screen-off if a charger is available.

Expected:

- In normal non-charging screen-off scenarios, the keep-screen-off switch controls whether fan level is preserved.
- Recording smart stop and SIM call + earpiece remain hard stops.
- Rapid-charge high-heat screen-off can preserve fan level as an independent cooling behavior.

Useful log signals:

```text
Keep fan on screen off switch changed
Keep fan on screen off enabled; mask FanStateHandler state.g screenLock=false
Keep fan on screen off; preserve target_level=0 -> ...
```

## 7. Hook Installation Robustness

Check logcat after process restart.

Expected:

- Settings hook install succeeds.
- PowerKeeper hook install succeeds.
- SystemUI main controller hook install succeeds; optional detail hooks may fail independently without aborting all SystemUI hooks.
- MISettings host hook install succeeds; optional search/follow-row hooks may fail independently without aborting the host page.

Useful log signals:

```text
FanModeController hooks installed in com.android.settings
FanStateHandler hooks installed in com.miui.powerkeeper
CoolingFanTile hooks installed in com.android.systemui
MISettings fan config host hooks installed in com.xiaomi.misettings
```

Failure signals to inspect:

```text
Failed to install hooks for ...
Failed to hook SystemUI cooling fan detail adapter
Hook MISettings AppSearchFragment failed
Hook MISettings fan follow row failed
```

Optional hook failures are acceptable only when the core feature path still works.
