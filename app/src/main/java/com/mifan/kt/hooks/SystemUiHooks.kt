package com.mifan.kt

import android.content.ContentResolver
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.mifan.kt.rom.HostDexResolver
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

internal fun HookEntry.installSystemUiHooks(classLoader: ClassLoader, apkPath: String? = null) {
    val resolved = HostDexResolver.resolveSystemUi(apkPath) { logInfo(it) }
    logInfo(
        "SystemUI targets source=${resolved.source} dexkit=${com.mifan.kt.rom.DexKitSupport.loadSource()}: " +
            "controller=${resolved.coolingFanController}, adapter=${resolved.detailAdapter}"
    )
    val controllerClass = classLoader.loadClass(resolved.coolingFanController)
    runCatching {
        controllerClass.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            hook(constructor).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                ensureSystemUiModeItems(chain.thisObject, classLoader)
                result
            })
        }
    }.onFailure {
        logError("Failed to hook SystemUI cooling fan controller constructors", it)
    }

    runCatching {
        val adapterClass = classLoader.loadClass(resolved.detailAdapter)
        logSystemUiProbe(controllerClass, adapterClass)

        runCatching {
            val updateItems = adapterClass.getDeclaredMethod("updateItems")
            updateItems.isAccessible = true
            hook(updateItems).intercept(XposedInterface.Hooker { chain ->
                // 必须在原 updateItems() 执行前补齐模型列表；原方法会基于 _secondaryItems 生成二级页。
                ensureSystemUiModeItemsFromAdapter(chain.thisObject, classLoader)
                // PowerKeeper 侧为了稳定退出会短暂写 fan_mode=高速强冷(官方 schema.high，云控对齐后多为 0)。
                // 这里在 SystemUI 刷新前屏蔽该中转状态，避免控制中心从“智能调频”闪到“高速强冷”。
                maskSystemUiBridgeStateFromAdapter(chain.thisObject)
                val result = chain.proceed()
                // 原生只能通过 titleRes 显示文本；狂暴模式复用了高速强冷资源防崩溃，所以这里二次覆盖显示标题。
                rebuildSystemUiDetailItems(chain.thisObject, classLoader)
                result
            })
        }.onFailure {
            logError("Failed to hook SystemUI cooling fan detail updateItems", it)
        }

        runCatching {
            val createDetailView = adapterClass.getDeclaredMethod(
                "createDetailView",
                classLoader.loadClass("android.content.Context"),
                classLoader.loadClass("android.view.View"),
                classLoader.loadClass("android.view.ViewGroup")
            )
            createDetailView.isAccessible = true
            hook(createDetailView).intercept(XposedInterface.Hooker { chain ->
                ensureSystemUiModeItemsFromAdapter(chain.thisObject, classLoader)
                chain.proceed()
            })
        }.onFailure {
            logError("Failed to hook SystemUI cooling fan detail createDetailView", it)
        }

        runCatching {
            val qsDetailItemClass = classLoader.loadClass("com.android.systemui.qs.QSDetailContent\$Item")
            val onDetailItemClick = adapterClass.getDeclaredMethod("onDetailItemClick", qsDetailItemClass)
            onDetailItemClick.isAccessible = true
            hook(onDetailItemClick).intercept(XposedInterface.Hooker { chain ->
                handleSystemUiDetailItemClick(chain.thisObject, chain.getArg(0)) { chain.proceed() }
            })
        }.onFailure {
            logError("Failed to hook SystemUI cooling fan detail click for custom mode", it)
        }
    }.onFailure {
        logError("Failed to hook SystemUI cooling fan detail adapter", it)
    }
}

internal fun HookEntry.ensureSystemUiModeItems(controller: Any?, classLoader: ClassLoader) {
    if (controller == null) return
    try {
        val current = controller.readFieldOrNull(RomTargets.FIELD_SU_SECONDARY_ITEMS) as? List<*> ?: return
        // Do not rewrite built-in SystemUI identities here. On this ROM their identity values are part of
        // SystemUI/PowerKeeper's own contract; rewriting them can break official modes and duplicate entries.
        val officialIds = current.mapNotNull { it.selectableIdentityAsInt() }
        val schema = OfficialFanModeSchema.noteObservedOfficialIds(officialIds, "SystemUI._secondaryItems")
        logInfo("Official fan_mode schema from SystemUI: ${schema.scheme} high=${schema.high} quiet=${schema.quiet} ids=$officialIds")
        val context = readSystemUiContext(controller)
        val titleRes = context?.resourceId("quick_settings_coolingfan_mode_high", "string")?.takeIf { it != 0 } ?: FanModeConstants.SYSTEMUI_FALLBACK_MODE_HIGH_STRING_RES
        val iconRes = context?.resourceId("ic_qs_coolingfan_mode_performance", "drawable")?.takeIf { it != 0 } ?: FanModeConstants.SYSTEMUI_FALLBACK_MODE_PERFORMANCE_ICON_RES
        val updated = ArrayList<Any>(current.size + 2)
        current.filterNotNull().forEach { updated.add(it) }
        val appendExtreme = current.none { it.selectableIdentityAsInt() == FanModeConstants.MODE_EXTREME }
        val appendCustom = current.none { it.selectableIdentityAsInt() == FanModeConstants.MODE_CUSTOM }
        if (appendExtreme) {
            updated.add(createSystemUiSelectableModel(classLoader, FanModeConstants.MODE_EXTREME, titleRes, 3, FanModeConstants.MODE_EXTREME_TITLE, iconRes))
        }
        if (appendCustom) {
            updated.add(createSystemUiSelectableModel(classLoader, FanModeConstants.MODE_CUSTOM, titleRes, 4, FanModeConstants.MODE_CUSTOM_TITLE, iconRes))
        }
        if (!appendExtreme && !appendCustom) return
        controller.writeFieldIfExists(RomTargets.FIELD_SU_SECONDARY_ITEMS, updated)
        logInfo("SystemUI cooling fan extra mode items appended: extreme=$appendExtreme, custom=$appendCustom")
    } catch (t: Throwable) {
        logError("Append SystemUI extreme item failed", t)
    }
}

internal fun HookEntry.ensureSystemUiModeItemsFromAdapter(adapter: Any?, classLoader: ClassLoader) {
    if (adapter == null) return
    val tile = adapter.readFieldOrNull("this$0") ?: return
    val controller = tile.readFieldOrNull(RomTargets.FIELD_SU_COOLING_CONTROLLER) ?: return
    ensureSystemUiModeItems(controller, classLoader)
}

internal fun HookEntry.maskSystemUiBridgeStateFromAdapter(adapter: Any?) {
    try {
        val tile = adapter?.readFieldOrNull("this$0") ?: return
        val controller = tile.readFieldOrNull(RomTargets.FIELD_SU_COOLING_CONTROLLER) ?: return
        val context = readSystemUiContext(tile) ?: readSystemUiContext(controller) ?: return
        val rawFanMode = (controller.readFieldOrNull(RomTargets.FIELD_SU_FAN_MODE_STATE) as? Number)?.toInt() ?: return
        val displayFanMode = resolveSystemUiDisplayFanMode(context.contentResolver, rawFanMode)
        if (displayFanMode != rawFanMode) {
            controller.writeFieldIfExists(RomTargets.FIELD_SU_FAN_MODE_STATE, Integer.valueOf(displayFanMode))
            logInfo("SystemUI bridge fan mode masked $rawFanMode -> $displayFanMode")
        }
    } catch (t: Throwable) {
        logError("Mask SystemUI bridge state failed", t)
    }
}

internal fun HookEntry.rebuildSystemUiDetailItems(adapter: Any?, classLoader: ClassLoader) {
    if (adapter == null) return
    try {
        val detailView = adapter.readFieldOrNull("detailView") ?: return
        val tile = adapter.readFieldOrNull("this$0") ?: return
        val controller = tile.readFieldOrNull(RomTargets.FIELD_SU_COOLING_CONTROLLER) ?: return
        ensureSystemUiModeItems(controller, classLoader)
        if (controller.readFieldOrNull(RomTargets.FIELD_SU_TOGGLE_STATE) != true) return

        val context = readSystemUiContext(tile) ?: return
        val modelItems = controller.readFieldOrNull(RomTargets.FIELD_SU_SECONDARY_ITEMS) as? List<*> ?: return
        val qsItemClass = classLoader.loadClass(RomTargets.SYSTEMUI_QS_DETAIL_ITEM)
        val dividerClass = classLoader.loadClass(RomTargets.SYSTEMUI_QS_TEXT_DIVIDER)
        val selectableClass = classLoader.loadClass(RomTargets.SYSTEMUI_QS_SELECTABLE)
        val dividerTitle = context.stringByName("quick_settings_coolingfan_mode") ?: "风扇模式"
        val selectedIcon = context.resourceId("ic_qs_coolingfan_mode_selected", "drawable")
        val rawFanMode = (controller.readFieldOrNull(RomTargets.FIELD_SU_FAN_MODE_STATE) as? Number)?.toInt() ?: -1
        val fanMode = resolveSystemUiDisplayFanMode(context.contentResolver, rawFanMode)

        val items = ArrayList<Any>()
        items.add(dividerClass.getConstructor(CharSequence::class.java).newInstance(dividerTitle))

        modelItems.filterNotNull().forEach { model ->
            val identity = model.selectableIdentityAsInt()
            val item = selectableClass.getDeclaredConstructor().newInstance()
            val title = when (identity) {
                FanModeConstants.MODE_EXTREME_VALUE.toInt() -> FanModeConstants.MODE_EXTREME_TITLE
                FanModeConstants.MODE_CUSTOM_VALUE.toInt() -> FanModeConstants.MODE_CUSTOM_TITLE
                else -> {
                    val titleRes = (model.readFieldOrNull("titleRes") as? Number)?.toInt() ?: 0
                    if (titleRes != 0) context.getString(titleRes) else ""
                }
            }
            val selected = identity == fanMode
            item.writeFieldIfExists("isForceSingle", true)
            item.writeFieldIfExists("selectable", true)
            item.writeFieldIfExists("title", title)
            item.writeFieldIfExists("selected", selected)
            val iconRes = (model.readFieldOrNull("iconRes") as? Number)?.toInt()
                ?: if (identity == FanModeConstants.MODE_EXTREME_VALUE.toInt() || identity == FanModeConstants.MODE_CUSTOM_VALUE.toInt()) context.resourceId("ic_qs_coolingfan_mode_performance", "drawable") else 0
            item.writeFieldIfExists("iconRes", iconRes)
            item.writeFieldIfExists("icon2Res", if (selected) selectedIcon else 0)
            item.writeFieldIfExists("tag", model)
            item.writeFieldIfExists("contentDescription", title)
            items.add(item)
        }

        val array = ReflectArray.newInstance(qsItemClass, items.size)
        items.forEachIndexed { index, item -> ReflectArray.set(array, index, item) }
        detailView.javaClass.findPublicOrDeclaredMethod("setItems", array.javaClass)?.invoke(detailView, array)
        logInfo("SystemUI cooling fan detail items rebuilt with extreme mode")
    } catch (t: Throwable) {
        logError("Rebuild SystemUI cooling fan detail items failed", t)
    }
}

internal fun HookEntry.handleSystemUiDetailItemClick(adapter: Any?, item: Any?, proceed: () -> Any?): Any? {
    val model = readSystemUiDetailItemTag(item) ?: return proceed()
    val identity = model.selectableIdentityAsInt() ?: return proceed()
    val tile = adapter?.readFieldOrNull("this$0") ?: return proceed()
    val controller = tile.readFieldOrNull(RomTargets.FIELD_SU_COOLING_CONTROLLER) ?: return proceed()
    val context = readSystemUiContext(tile) ?: readSystemUiContext(controller) ?: return proceed()
    return if (identity == FanModeConstants.MODE_CUSTOM_VALUE.toInt()) {
        enterCustomFanMode(context.contentResolver, "SystemUI")
        controller.writeFieldIfExists(RomTargets.FIELD_SU_FAN_MODE_STATE, Integer.valueOf(FanModeConstants.MODE_CUSTOM))
        logInfo("SystemUI custom fan mode selected; keep fan_mode=-1 and enable mifan custom mode")
        adapter.javaClass.findPublicOrDeclaredMethod("updateItems")?.invoke(adapter)
        null
    } else {
        exitCustomFanMode(context.contentResolver, "SystemUI")
        val result = proceed()
        val writtenMode = Settings.System.getInt(context.contentResolver, FanModeConstants.KEY_FAN_MODE, FanModeConstants.MODE_SMART)
        if (writtenMode == FanModeConstants.MODE_CUSTOM) {
            enterCustomFanMode(context.contentResolver, "SystemUI legacy correction")
            controller.writeFieldIfExists(RomTargets.FIELD_SU_FAN_MODE_STATE, Integer.valueOf(FanModeConstants.MODE_CUSTOM))
            logInfo("SystemUI legacy fan_mode=5 write corrected to scheme B")
        }
        result
    }
}

internal fun HookEntry.readSystemUiDetailItemTag(item: Any?): Any? {
    if (item == null) return null
    return try {
        item.javaClass.findPublicOrDeclaredMethod("getTag")?.invoke(item) ?: item.readFieldOrNull("tag")
    } catch (_: Throwable) {
        item.readFieldOrNull("tag")
    }
}

internal fun HookEntry.resolveSystemUiDisplayFanMode(resolver: ContentResolver?, fanMode: Int): Int {
    if (isCustomFanModeSelected(resolver)) return FanModeConstants.MODE_CUSTOM_VALUE.toInt()
    return maskTransientBridgeFanModeForSystemUi(resolver, fanMode)
}

internal fun HookEntry.createSystemUiSelectableModel(
    classLoader: ClassLoader,
    identity: Int,
    titleRes: Int,
    trackIndex: Int,
    trackStyle: String,
    iconRes: Int
): Any {
    val trackClass = classLoader.loadClass(RomTargets.SYSTEMUI_COOLING_FAN_TRACK)
    val selectableClass = classLoader.loadClass(RomTargets.SYSTEMUI_SELECTABLE_MODEL)
    val track = trackClass.getConstructor(Int::class.javaPrimitiveType, String::class.java)
        .newInstance(trackIndex, trackStyle)
    return selectableClass.getConstructor(Any::class.java, Int::class.javaPrimitiveType, trackClass, Int::class.javaPrimitiveType)
        .newInstance(Integer.valueOf(identity), titleRes, track, iconRes)
}

internal fun HookEntry.readSystemUiContext(target: Any?): android.content.Context? {
    var current: Any? = target
    repeat(4) {
        if (current is android.content.Context) return current
        val context = current?.readFieldOrNull("mContext") ?: current?.readFieldOrNull("context")
        if (context is android.content.Context) return context
        current = current?.readFieldOrNull("this$0")
    }
    return null
}

internal fun HookEntry.logSystemUiProbe(controllerClass: Class<*>, adapterClass: Class<*>) {
    val checks = listOf(
        "CoolingFanController" to true,
        "CoolingFanDetailAdapter" to true,
        "controller field _secondaryItems" to controllerClass.hasFieldCached("_secondaryItems"),
        "controller field fanModeState" to controllerClass.hasFieldCached("fanModeState"),
        "controller field toggleState" to controllerClass.hasFieldCached("toggleState"),
        "adapter field this$0" to adapterClass.hasFieldCached("this$0"),
        "adapter field detailView" to adapterClass.hasFieldCached("detailView"),
        "method updateItems" to adapterClass.hasMethodCached("updateItems"),
        "method createDetailView" to adapterClass.declaredMethods.any { it.name == "createDetailView" }
    )
    logProbe("SystemUI cooling fan probe", checks)
}
