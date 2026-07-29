package com.mifan.kt

import android.content.ContentResolver
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.mifan.kt.rom.OfficialFanModeSchema
import com.mifan.kt.rom.RomTargets
import com.mifan.kt.util.ReflectCache.append
import com.mifan.kt.util.ReflectCache.findCachedField
import com.mifan.kt.util.ReflectCache.findPublicOrDeclaredMethod
import com.mifan.kt.util.ReflectCache.readFieldOrNull
import com.mifan.kt.util.ReflectCache.readFirstCharSequenceArrayField
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method

internal fun HookEntry.resolveThemeColor(context: android.content.Context, attr: Int, fallback: Int): Int {
    val tv = android.util.TypedValue()
    return if (context.theme.resolveAttribute(attr, tv, true)) {
        if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    } else fallback
}

internal fun HookEntry.dp(context: android.content.Context, value: Int): Int {
    return (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

internal fun HookEntry.fanLevelTitle(level: Int): String {
    return when (level) {
        FanModeConstants.PER_APP_LEVEL_SMART -> "智能调频（默认）"
        FanModeConstants.PER_APP_LEVEL_OFF -> "关闭风扇"
        1 -> "静谧模式"
        2 -> "高速强冷"
        4 -> "狂暴模式"
        else -> "未知($level)"
    }
}

internal fun HookEntry.fanLevelSummary(level: Int): String {
    return when (level) {
        FanModeConstants.PER_APP_LEVEL_SMART -> "根据场景和温度智能匹配风扇转速"
        2 -> "强劲散热，伴随风噪和耗电增加"
        1 -> "低速风扇，适合安静氛围的场景"
        4 -> "疾速冷却，最大幅度提升散热能力"
        // 快充/导航场景有独立保底档位；低档 App 配置不压低场景档位。
        FanModeConstants.PER_APP_LEVEL_OFF -> "此应用前台运行时关闭风扇"
        else -> ""
    }
}

internal fun HookEntry.serializePerAppFanRules(rules: Map<String, Int>): String {
    return rules.entries
        .filter { it.key.isNotBlank() && it.value in FanModeConstants.PER_APP_SUPPORTED_LEVELS && it.value != FanModeConstants.PER_APP_LEVEL_SMART }
        .sortedBy { it.key }
        .joinToString(";") { "${it.key}=${it.value}" }
}
