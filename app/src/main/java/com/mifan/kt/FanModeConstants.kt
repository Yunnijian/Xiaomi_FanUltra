package com.mifan.kt

/**
 * Shared Settings.System keys, mode IDs, package names, and UI copy.
 *
 * Official [fan_mode] numbering is ROM/cloud dependent — see
 * [com.mifan.kt.rom.OfficialFanModeSchema]. Module-owned modes (extreme=4,
 * custom UI=5) stay fixed across schemes.
 */
internal object FanModeConstants {
    const val TAG = "FanModeHook"

    const val MODULE_PACKAGE = "com.mifan.kt"
    const val TARGET_PACKAGE = "com.android.settings"
    const val POWERKEEPER_PACKAGE = "com.miui.powerkeeper"
    const val SYSTEMUI_PACKAGE = "com.android.systemui"
    const val MISETTINGS_PACKAGE = "com.xiaomi.misettings"

    const val KEY_FAN_MODE = "fan_mode"
    const val KEY_COOLING_FAN_ENABLE = "cooling_fan_enable"
    const val KEY_FAN_MODE_RANGE = "fan_mode_range"
    const val KEY_SCENE_GAMING = "fan_mode_scene_gaming"
    const val KEY_SCENE_RAPID_CHARGE = "fan_mode_scene_rapid_charge"
    const val KEY_SCENE_NAVIGATION = "fan_mode_scene_navigation"
    const val KEY_SMART_STOP_RECORDING = "fan_smart_stop_on_recording"
    const val KEY_FAN_OTHER_FEATURES_CATEGORY = "fan_other_features_category"

    const val KEY_EXTREME_TO_SMART_BRIDGE_UNTIL = "mifan_extreme_to_smart_bridge_until"
    const val KEY_KEEP_FAN_ON_SCREEN_OFF = "mifan_keep_fan_on_screen_off"
    const val KEY_PER_APP_FAN_RULES = "mifan_per_app_fan_rules"
    const val KEY_CUSTOM_FAN_MODE_ENABLED = "mifan_custom_fan_mode_enabled"
    const val KEY_PREVIOUS_FAN_MODE_RANGE = "mifan_previous_fan_mode_range"
    const val KEY_PER_APP_FAN_CONFIG_ENTRY = "mifan_per_app_fan_config_entry"
    const val PER_APP_PAGE_TAG = "mifan_per_app_fan_config_page"
    const val EXTRA_MIFAN_FAN_CONFIG_PAGE = "mifan_fan_config_page"

    const val TARGET_LEVEL = "target_level"

    /** Module-owned: 狂暴. Not used by stock HyperOS. */
    const val MODE_EXTREME = 4
    const val MODE_EXTREME_VALUE = "4"

    /**
     * Module UI identity for 自定义. Real [KEY_FAN_MODE] stays smart (-1)
     * under scheme B ([KEY_CUSTOM_FAN_MODE_ENABLED]=1).
     */
    const val MODE_CUSTOM = 5
    const val MODE_CUSTOM_VALUE = "5"

    const val MODE_SMART = -1
    const val MODE_SMART_VALUE = "-1"

    /** Per-app rules write target_level, not fan_mode. */
    const val PER_APP_LEVEL_SMART = -1
    const val PER_APP_LEVEL_OFF = 0
    /**
     * Custom mode UI default is still「智能调频」, but we floor hardware [target_level] to this
     * so official smart policy does not leave the fan stuck at 0.
     */
    const val PER_APP_SMART_TARGET_LEVEL = 1
    val PER_APP_SUPPORTED_LEVELS = setOf(PER_APP_LEVEL_SMART, PER_APP_LEVEL_OFF, 1, 2, 4)

    const val PREVIOUS_FAN_MODE_RANGE_NONE = -1

    const val EXTREME_TO_SMART_PK_DELAY_MS = 800L
    const val SYSTEMUI_BRIDGE_MASK_EXTRA_MS = 400L
    const val KEEP_SCREEN_OFF_LEVEL_CACHE_MS = 10 * 60 * 1000L
    const val PER_APP_ACTIVE_WRITE_MIN_INTERVAL_MS = 2_000L

    const val MODE_EXTREME_TITLE = "狂暴模式"
    const val MODE_EXTREME_SUMMARY = "疾速冷却，最大幅度提升散热能力"
    const val MODE_CUSTOM_TITLE = "自定义"
    const val MODE_CUSTOM_SUMMARY = "按前台应用的独立配置控制风扇；未配置应用默认智能调频"
    const val PER_APP_FAN_CONFIG_TITLE = "应用配置"
    const val PER_APP_FAN_CONFIG_SUMMARY = "为每个应用单独选择智能调频、静谧、高速强冷、狂暴或关闭风扇"
    const val KEEP_FAN_ON_SCREEN_OFF_TITLE = "息屏时保持风扇开启"
    const val KEEP_FAN_ON_SCREEN_OFF_SUMMARY = "开启后，息屏或锁屏时各风扇档位仍可继续运行，可能增加功耗和噪音"

    // SystemUI resource fallbacks when name lookup fails (from reverse dump).
    const val SYSTEMUI_FALLBACK_MODE_SMART_STRING_RES = 0x7f140c70
    const val SYSTEMUI_FALLBACK_MODE_HIGH_STRING_RES = 0x7f140c6e
    const val SYSTEMUI_FALLBACK_MODE_QUIET_STRING_RES = 0x7f140c6f
    const val SYSTEMUI_FALLBACK_MODE_PERFORMANCE_ICON_RES = 0x7f0812c9
}
