package com.mifan.kt.rom

/**
 * ROM-specific class / field / method names observed on the reverse dump.
 * All reflection targets should go through here so OTA/obfuscation changes
 * are isolated from hook logic.
 */
internal object RomTargets {
    // --- Settings ---
    const val FAN_MODE_CONTROLLER = "com.android.settings.coolingfan.FanModeController"
    const val FAN_MODE_CONTENT_OBSERVER_SUFFIX = "\$FanModeContentObserver"
    const val COOLING_FAN_ENABLE_CONTROLLER = "com.android.settings.coolingfan.CoolingFanEnableController"
    const val FAN_MODE_USAGE_SCENES_CONTROLLER = "com.android.settings.coolingfan.FanModeUsageScenesController"
    const val FAN_MODE_USAGE_SCENES_CONTENT_OBSERVER_SUFFIX = "\$FanModeUsageScenesContentObserver"
    const val FAN_MODE_RANGE_CONTROLLER = "com.android.settings.coolingfan.FanModeRangeController"
    const val FAN_SCENE_GAME_CONTROLLER = "com.android.settings.coolingfan.FanSceneGameController"
    const val FAN_SCENE_RAPID_CHARGE_CONTROLLER = "com.android.settings.coolingfan.FanSceneRapidChargeController"
    const val FAN_SCENE_NAVIGATION_CONTROLLER = "com.android.settings.coolingfan.FanSceneOutdoorController"

    // --- PowerKeeper ---
    const val FAN_STATE_HANDLER = "com.miui.powerkeeper.unionpower.corehandler.FanStateHandler"
    const val POWERKEEPER_BASE_CLASS = "com.miui.powerkeeper.unionpower.corehandler.a"
    const val FIELD_PK_BASE_CONTEXT = "d"
    const val FIELD_PK_RESOLVER_PRIMARY = "g"
    const val FIELD_PK_RESOLVER_SECONDARY = "h"
    const val FIELD_PK_TARGET_LEVEL = "p"
    const val FIELD_PK_SCREEN_LOCKED = "L"
    const val FIELD_PK_RECORDING_ACTIVE = "F"
    const val FIELD_PK_SIM_CALL_ACTIVE = "M"
    const val FIELD_PK_EARPIECE_ACTIVE = "N"
    const val FIELD_PK_MIC_SWITCH = "B"
    const val FIELD_PK_FAST_CHARGE_ACTIVE = "C"
    const val FIELD_PK_NAVIGATION_SCENE = "D"
    const val FIELD_PK_GAME_SWITCH = "y"
    const val FIELD_PK_CHARGE_SWITCH = "z"
    const val FIELD_PK_NAVIGATION_SWITCH = "A"
    const val FIELD_PK_FAN_MODE_RANGE = "l"
    const val FIELD_PK_NAVIGATION_APPS = "m0"
    const val FIELD_PK_FOREGROUND_PACKAGE = "x"
    const val FIELD_PK_THERMAL = "w"
    const val FIELD_PK_GAME_THERMAL_SET_A = "k0"
    const val FIELD_PK_GAME_THERMAL_SET_B = "l0"
    const val FIELD_PK_POWER_MANAGER = "h0"
    const val FIELD_PK_STATE_SCREEN_LOCK = "g"
    const val METHOD_PK_WRITE_PATH = "J"
    const val METHOD_PK_UPDATE_STATE = "W"
    const val METHOD_PK_RELOAD_SETTINGS = "o"
    const val PK_STATE_INNER_CLASS = "g"

    // --- SystemUI ---
    const val SYSTEMUI_COOLING_FAN_CONTROLLER = "com.android.systemui.controlcenter.policy.CoolingFanController"
    const val SYSTEMUI_COOLING_FAN_DETAIL_ADAPTER =
        "com.android.systemui.qs.tiles.CoolingFanTile\$CoolingFanDetailAdapter"
    const val SYSTEMUI_QS_DETAIL_ITEM = "com.android.systemui.qs.QSDetailContent\$Item"
    const val SYSTEMUI_QS_TEXT_DIVIDER = "com.android.systemui.qs.QSDetailContent\$TextDividerItem"
    const val SYSTEMUI_QS_SELECTABLE = "com.android.systemui.qs.QSDetailContent\$SelectableItem"
    const val SYSTEMUI_SELECTABLE_MODEL = "com.miui.systemui.controlcenter.secondary.model.SelectableItem"
    const val SYSTEMUI_COOLING_FAN_TRACK = "com.miui.systemui.controlcenter.secondary.model.CoolingFanTrack"
    const val FIELD_SU_SECONDARY_ITEMS = "_secondaryItems"
    const val FIELD_SU_FAN_MODE_STATE = "fanModeState"
    const val FIELD_SU_TOGGLE_STATE = "toggleState"
    const val FIELD_SU_COOLING_CONTROLLER = "coolingFanController"

    // --- MISettings (obfuscated names are optional enhancements) ---
    const val MISETTINGS_HIGH_REFRESH_OPTIONS_ACTIVITY =
        "com.xiaomi.misettings.display.RefreshRate.HighRefreshOptionsActivity"
    const val MISETTINGS_HIGH_REFRESH_OPTIONS_FRAGMENT =
        "com.xiaomi.misettings.display.RefreshRate.HighRefreshOptionsFragment"
    const val MISETTINGS_APP_SEARCH_FRAGMENT =
        "com.xiaomi.misettings.display.RefreshRate.AppSearchFragment"
    const val MISETTINGS_FOLLOW_VIEW_HOLDER = "oa.j"
    const val MISETTINGS_APP_ITEM_MODEL = "oa.h"
}
