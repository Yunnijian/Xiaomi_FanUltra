package com.mifan.kt

import android.content.ContentResolver
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method

class HookEntry : XposedModule() {
    override fun onPackageReady(param: PackageReadyParam) {
        try {
            when (param.packageName) {
                TARGET_PACKAGE -> {
                    installFanModeHooks(param.classLoader)
                    logInfo("FanModeController hooks installed in ${param.packageName}")
                }
                POWERKEEPER_PACKAGE -> {
                    installPowerKeeperHooks(param.classLoader)
                    logInfo("FanStateHandler hooks installed in ${param.packageName}")
                }
                SYSTEMUI_PACKAGE -> {
                    installSystemUiHooks(param.classLoader)
                    logInfo("CoolingFanTile hooks installed in ${param.packageName}")
                }
            }
        } catch (t: Throwable) {
            logError("Failed to install hooks for ${param.packageName}", t)
        }
    }

    private fun installFanModeHooks(classLoader: ClassLoader) {
        val controllerClass = classLoader.loadClass(FAN_MODE_CONTROLLER)
        val preferenceScreenClass = classLoader.loadClass("androidx.preference.PreferenceScreen")
        val preferenceClass = classLoader.loadClass("androidx.preference.Preference")
        val displayPreference = controllerClass.getMethod("displayPreference", preferenceScreenClass)
        val updateState = controllerClass.getMethod("updateState", preferenceClass)
        val onPreferenceChange = controllerClass.getMethod(
            "onPreferenceChange",
            preferenceClass,
            Any::class.java
        )

        hook(displayPreference).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            val controller = chain.thisObject
            val screen = chain.getArg(0)
            ensureExtremeMode(controller, findFanModePreferenceFromScreen(screen))
            result
        })

        hook(updateState).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            val controller = chain.thisObject
            val preference = chain.getArg(0)
            ensureExtremeMode(controller, preference)
            result
        })

        hook(onPreferenceChange).intercept(XposedInterface.Hooker { chain ->
            val value = chain.getArg(1)?.toString()
            if (value == MODE_EXTREME_VALUE) {
                logInfo("Extreme fan mode selected; let Settings write fan_mode=4")
            }
            chain.proceed()
        })
    }

    private fun installPowerKeeperHooks(classLoader: ClassLoader) {
        val handlerClass = classLoader.loadClass(FAN_STATE_HANDLER)
        val writePathMethod = handlerClass.getDeclaredMethod("J", String::class.java, String::class.java)
        writePathMethod.isAccessible = true

        // PowerKeeper 可能只把官方档位映射到 target_level。
        // 只在 fan_mode=4 且符合官方总开关、使用范围、场景限制时，把 target_level 提升为 4。
        // 非狂暴模式，或狂暴模式但当前官方策略不允许风扇运行时，完全放行/交给 applyExtremePolicy 降回 0，
        // 避免干扰官方智能调频/静谧/高速强冷，也避免狂暴模式绕过官方使用范围。
        hook(writePathMethod).intercept(XposedInterface.Hooker { chain ->
            val key = chain.getArg(0)?.toString()
            val requestedValue = chain.getArg(1)?.toString()
            if (key == TARGET_LEVEL && shouldForceExtremeLevel(chain.thisObject) && requestedValue != MODE_EXTREME_VALUE) {
                logInfo("Extreme fan mode allowed; override target_level=$requestedValue -> 4")
                chain.proceed(arrayOf(TARGET_LEVEL, MODE_EXTREME_VALUE))
            } else {
                chain.proceed()
            }
        })
        // fan_mode 改变时 FanStateHandler 会收到 ContentObserver 消息并进入 handleMessage。
        // 原逻辑跑完后主动补一次 target_level=4，处理官方分支完全不写 target_level 的情况。
        val messageClass = classLoader.loadClass("android.os.Message")
        val handleMessage = handlerClass.getMethod("handleMessage", messageClass)
        hook(handleMessage).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            applyExtremePolicy(chain.thisObject, writePathMethod)
            result
        })

        // 初始化/重读系统设置时也补写一次，避免 PowerKeeper 重启后状态不同步。
        val reloadSettings = handlerClass.getDeclaredMethod("o")
        reloadSettings.isAccessible = true
        hook(reloadSettings).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            applyExtremePolicy(chain.thisObject, writePathMethod)
            result
        })
    }


    private fun installSystemUiHooks(classLoader: ClassLoader) {
        val controllerClass = classLoader.loadClass(SYSTEMUI_COOLING_FAN_CONTROLLER)
        controllerClass.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            hook(constructor).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                ensureSystemUiExtremeItem(chain.thisObject, classLoader)
                result
            })
        }

        val adapterClass = classLoader.loadClass(SYSTEMUI_COOLING_FAN_DETAIL_ADAPTER)
        val updateItems = adapterClass.getDeclaredMethod("updateItems")
        updateItems.isAccessible = true
        hook(updateItems).intercept(XposedInterface.Hooker { chain ->
            // 必须在原 updateItems() 执行前补齐模型列表；原方法会基于 _secondaryItems 生成二级页。
            ensureSystemUiExtremeItemFromAdapter(chain.thisObject, classLoader)
            val result = chain.proceed()
            // 原生只能通过 titleRes 显示文本；狂暴模式复用了高速强冷资源防崩溃，所以这里二次覆盖显示标题。
            rebuildSystemUiDetailItems(chain.thisObject, classLoader)
            result
        })

        val createDetailView = adapterClass.getDeclaredMethod(
            "createDetailView",
            classLoader.loadClass("android.content.Context"),
            classLoader.loadClass("android.view.View"),
            classLoader.loadClass("android.view.ViewGroup")
        )
        createDetailView.isAccessible = true
        hook(createDetailView).intercept(XposedInterface.Hooker { chain ->
            ensureSystemUiExtremeItemFromAdapter(chain.thisObject, classLoader)
            chain.proceed()
        })
    }

    private fun ensureSystemUiExtremeItem(controller: Any?, classLoader: ClassLoader) {
        if (controller == null) return
        try {
            val current = controller.readFieldOrNull("_secondaryItems") as? List<*> ?: return
            // Do not rewrite built-in SystemUI identities here. On this ROM their identity values are part of
            // SystemUI/PowerKeeper's own contract; rewriting them can break official modes and duplicate entries.
            if (current.any { it.selectableIdentityAsInt() == 4 }) return
            val context = readSystemUiContext(controller)
            val titleRes = context?.resourceId("quick_settings_coolingfan_mode_high", "string")?.takeIf { it != 0 } ?: SYSTEMUI_FALLBACK_MODE_HIGH_STRING_RES
            val iconRes = context?.resourceId("ic_qs_coolingfan_mode_performance", "drawable")?.takeIf { it != 0 } ?: SYSTEMUI_FALLBACK_MODE_PERFORMANCE_ICON_RES
            val extreme = createSystemUiSelectableModel(classLoader, 4, titleRes, 3, MODE_EXTREME_TITLE, iconRes)
            val updated = ArrayList<Any>(current.size + 1)
            current.filterNotNull().forEach { updated.add(it) }
            updated.add(extreme)
            controller.writeFieldIfExists("_secondaryItems", updated)
            logInfo("SystemUI cooling fan extreme item appended")
        } catch (t: Throwable) {
            logError("Append SystemUI extreme item failed", t)
        }
    }


    private fun ensureSystemUiExtremeItemFromAdapter(adapter: Any?, classLoader: ClassLoader) {
        if (adapter == null) return
        val tile = adapter.readFieldOrNull("this$0") ?: return
        val controller = tile.readFieldOrNull("coolingFanController") ?: return
        ensureSystemUiExtremeItem(controller, classLoader)
    }
    private fun normalizeSystemUiFanModeIdentities(items: List<*>) {
        // 当前不调用该实验方法，不改写 SystemUI 原生 identity。
        // 真实语义以 Settings/PowerKeeper 为准：-1=智能调频，1=静谧模式，2=高速强冷，4=狂暴模式。
        items.filterNotNull().forEach { item ->
            val identity = item.selectableIdentityAsInt() ?: return@forEach
            val titleRes = (item.readFieldOrNull("titleRes") as? Number)?.toInt() ?: 0
            val normalized = when {
                identity == 4 -> 4
                titleRes == SYSTEMUI_FALLBACK_MODE_SMART_STRING_RES -> -1
                titleRes == SYSTEMUI_FALLBACK_MODE_HIGH_STRING_RES -> 2
                titleRes == SYSTEMUI_FALLBACK_MODE_QUIET_STRING_RES -> 1
                else -> identity
            }
            if (normalized != identity) {
                item.writeFieldIfExists("identity", Integer.valueOf(normalized))
                logInfo("SystemUI cooling fan identity normalized $identity -> $normalized")
            }
        }
    }


    private fun rebuildSystemUiDetailItems(adapter: Any?, classLoader: ClassLoader) {
        if (adapter == null) return
        try {
            val detailView = adapter.readFieldOrNull("detailView") ?: return
            val tile = adapter.readFieldOrNull("this$0") ?: return
            val controller = tile.readFieldOrNull("coolingFanController") ?: return
            ensureSystemUiExtremeItem(controller, classLoader)
            if (controller.readFieldOrNull("toggleState") != true) return

            val context = readSystemUiContext(tile) ?: return
            val modelItems = controller.readFieldOrNull("_secondaryItems") as? List<*> ?: return
            val qsItemClass = classLoader.loadClass("com.android.systemui.qs.QSDetailContent\$Item")
            val dividerClass = classLoader.loadClass("com.android.systemui.qs.QSDetailContent\$TextDividerItem")
            val selectableClass = classLoader.loadClass("com.android.systemui.qs.QSDetailContent\$SelectableItem")
            val dividerTitle = context.stringByName("quick_settings_coolingfan_mode") ?: "风扇模式"
            val selectedIcon = context.resourceId("ic_qs_coolingfan_mode_selected", "drawable")
            val fanMode = (controller.readFieldOrNull("fanModeState") as? Number)?.toInt() ?: -1

            val items = ArrayList<Any>()
            items.add(dividerClass.getConstructor(CharSequence::class.java).newInstance(dividerTitle))

            modelItems.filterNotNull().forEach { model ->
                val identity = model.selectableIdentityAsInt()
                val item = selectableClass.getDeclaredConstructor().newInstance()
                val title = if (identity == 4) {
                    MODE_EXTREME_TITLE
                } else {
                    val titleRes = (model.readFieldOrNull("titleRes") as? Number)?.toInt() ?: 0
                    if (titleRes != 0) context.getString(titleRes) else ""
                }
                val selected = identity == fanMode
                item.writeFieldIfExists("isForceSingle", true)
                item.writeFieldIfExists("selectable", true)
                item.writeFieldIfExists("title", title)
                item.writeFieldIfExists("selected", selected)
                val iconRes = (model.readFieldOrNull("iconRes") as? Number)?.toInt()
                    ?: if (identity == 4) context.resourceId("ic_qs_coolingfan_mode_performance", "drawable") else 0
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

    private fun createSystemUiSelectableModel(
        classLoader: ClassLoader,
        identity: Int,
        titleRes: Int,
        trackIndex: Int,
        trackStyle: String,
        iconRes: Int
    ): Any {
        val trackClass = classLoader.loadClass("com.miui.systemui.controlcenter.secondary.model.CoolingFanTrack")
        val selectableClass = classLoader.loadClass("com.miui.systemui.controlcenter.secondary.model.SelectableItem")
        val track = trackClass.getConstructor(Int::class.javaPrimitiveType, String::class.java)
            .newInstance(trackIndex, trackStyle)
        return selectableClass.getConstructor(Any::class.java, Int::class.javaPrimitiveType, trackClass, Int::class.javaPrimitiveType)
            .newInstance(Integer.valueOf(identity), titleRes, track, iconRes)
    }

    private fun Any?.selectableIdentityAsInt(): Int? {
        if (this == null) return null
        return (readFieldOrNull("identity") as? Number)?.toInt()
    }

    private fun readSystemUiContext(target: Any?): android.content.Context? {
        var current: Any? = target
        repeat(4) {
            if (current is android.content.Context) return current as android.content.Context
            val context = current?.readFieldOrNull("mContext") ?: current?.readFieldOrNull("context")
            if (context is android.content.Context) return context
            current = current?.readFieldOrNull("this$0")
        }
        return null
    }

    private fun android.content.Context.resourceId(name: String, type: String): Int {
        return resources.getIdentifier(name, type, packageName)
    }

    private fun android.content.Context.stringByName(name: String): String? {
        val id = resourceId(name, "string")
        return if (id != 0) getString(id) else null
    }

    private fun isExtremeModeSelected(handler: Any?): Boolean {
        return try {
            val resolver = readPowerKeeperResolver(handler) ?: return false
            Settings.System.getInt(resolver, KEY_FAN_MODE, -1) == 4
        } catch (t: Throwable) {
            logError("Read fan_mode failed", t)
            false
        }
    }

    private fun readPowerKeeperResolver(handler: Any?): ContentResolver? {
        if (handler != null) {
            val direct = handler.readFieldOrNull("g") ?: handler.readFieldOrNull("h")
            if (direct is ContentResolver) return direct

            val classLoader = handler.javaClass.classLoader
            val context = classLoader?.loadClass(POWERKEEPER_BASE_CLASS)
                ?.getDeclaredField("d")
                ?.apply { isAccessible = true }
                ?.get(null)
            val method = context?.javaClass?.getMethod("getContentResolver")
            val resolver = method?.invoke(context)
            if (resolver is ContentResolver) return resolver
        }
        return null
    }

    private fun applyExtremePolicy(handler: Any?, writePathMethod: Method) {
        if (handler == null) return
        val resolver = readPowerKeeperResolver(handler)
        val mode = resolver?.let { Settings.System.getInt(it, KEY_FAN_MODE, -1) } ?: -1

        // 非狂暴模式原则上交还 PowerKeeper 原生逻辑处理。
        // 只有“刚从狂暴模式切回智能调频”这一种场景，先桥接到官方高速强冷，
        // 让底层 target_level 从 4 回到官方档位，再延迟切回 fan_mode=-1。
        if (mode != 4) {
            if (mode == MODE_SMART_VALUE.toInt() && (wasExtremeModeActive || lastFanModeSeen == MODE_EXTREME_VALUE.toInt())) {
                bridgeExtremeToSmartInPowerKeeper(handler, writePathMethod, resolver)
            }
            wasExtremeModeActive = false
            lastFanModeSeen = mode
            return
        }

        lastFanModeSeen = 4
        if (shouldForceExtremeLevel(handler)) {
            wasExtremeModeActive = true
            writeTargetLevelByPowerKeeper(handler, writePathMethod, 4)
        } else {
            // fan_mode=4 只代表用户选择了狂暴模式；是否实际运行仍应服从总开关、使用范围和场景开关。
            // 不满足官方场景时主动降到 0，避免狂暴模式全局常驻。
            writeTargetLevelByPowerKeeper(handler, writePathMethod, 0)
        }
    }

    private fun bridgeExtremeToSmartInPowerKeeper(handler: Any?, writePathMethod: Method, resolver: ContentResolver?) {
        if (handler == null || resolver == null) return
        if (extremeToSmartBridgeInProgress) return
        extremeToSmartBridgeInProgress = true
        try {
            logInfo("Extreme -> smart bridge in PowerKeeper: target_level=2, fan_mode=2, then fan_mode=-1")
            writeTargetLevelByPowerKeeper(handler, writePathMethod, MODE_HIGH_VALUE.toInt())
            Settings.System.putInt(resolver, KEY_FAN_MODE, MODE_HIGH_VALUE.toInt())
            Thread {
                try {
                    Thread.sleep(EXTREME_TO_SMART_PK_DELAY_MS)
                    Settings.System.putInt(resolver, KEY_FAN_MODE, MODE_SMART_VALUE.toInt())
                    logInfo("Extreme -> smart PowerKeeper bridge completed: fan_mode=-1")
                } catch (t: Throwable) {
                    logError("Extreme -> smart PowerKeeper bridge failed", t)
                } finally {
                    extremeToSmartBridgeInProgress = false
                }
            }.start()
        } catch (t: Throwable) {
            extremeToSmartBridgeInProgress = false
            logError("Start extreme -> smart PowerKeeper bridge failed", t)
        }
    }

    private fun shouldForceExtremeLevel(handler: Any?): Boolean {
        if (handler == null) return false
        val resolver = readPowerKeeperResolver(handler) ?: return false
        if (Settings.System.getInt(resolver, KEY_FAN_MODE, -1) != 4) return false
        if (Settings.System.getInt(resolver, KEY_COOLING_FAN_ENABLE, 1) != 1) return false

        val range = Settings.System.getInt(resolver, KEY_FAN_MODE_RANGE, readIntField(handler, "l", 0))
        val gameSwitch = Settings.System.getInt(resolver, KEY_SCENE_GAMING, if (readBooleanField(handler, "y", true)) 1 else 0) == 1
        val chargeSwitch = Settings.System.getInt(resolver, KEY_SCENE_RAPID_CHARGE, if (readBooleanField(handler, "z", false)) 1 else 0) == 1
        val navigationSwitch = Settings.System.getInt(resolver, KEY_SCENE_NAVIGATION, if (readBooleanField(handler, "A", false)) 1 else 0) == 1
        val micSwitch = Settings.System.getInt(resolver, KEY_SMART_STOP_RECORDING, if (readBooleanField(handler, "B", true)) 1 else 0) == 1

        // 和官方逻辑一致：通话/录音/锁屏等高优先级场景应允许风扇停止。
        val screenLocked = readBooleanField(handler, "L", false)
        val fastChargeActive = readBooleanField(handler, "C", false)
        val recordingActive = readBooleanField(handler, "F", false)
        val simCall = readBooleanField(handler, "M", false)
        val earpiece = readBooleanField(handler, "N", false)
        if (screenLocked && !fastChargeActive) return false
        if (micSwitch && recordingActive) return false
        if (simCall && earpiece) return false

        // 使用范围为全场景/其它场景时，官方会走 other 场景；这里允许狂暴模式生效。
        if (range == 1) return true

        val thermal = handler.readFieldOrNull("w")?.toString()
        val foregroundPackage = handler.readFieldOrNull("x")?.toString()
        val gameLikeScene = containsInSetField(handler, "k0", thermal) || containsInSetField(handler, "l0", thermal)
        val navigationScene = readBooleanField(handler, "D", false) || containsKeyInMapField(handler, "m0", foregroundPackage)

        return (gameSwitch && gameLikeScene) ||
            (chargeSwitch && fastChargeActive) ||
            (navigationSwitch && navigationScene)
    }

    private fun writeTargetLevelByPowerKeeper(handler: Any?, writePathMethod: Method, level: Int) {
        if (handler == null) return
        try {
            val ok = writePathMethod.invoke(handler, TARGET_LEVEL, level.toString())
            writeIntFieldIfExists(handler, "p", level)
            logInfo("Extreme fan policy applied by PowerKeeper J(target_level, $level), result=$ok")
        } catch (t: Throwable) {
            logError("Apply target_level=$level by PowerKeeper failed", t)
        }
    }

    private fun readIntField(target: Any, fieldName: String, defaultValue: Int): Int {
        val value = target.readFieldOrNull(fieldName)
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            else -> defaultValue
        }
    }

    private fun readBooleanField(target: Any, fieldName: String, defaultValue: Boolean): Boolean {
        val value = target.readFieldOrNull(fieldName)
        return value as? Boolean ?: defaultValue
    }

    private fun containsInSetField(target: Any, fieldName: String, value: String?): Boolean {
        if (value == null) return false
        return (target.readFieldOrNull(fieldName) as? Set<*>)?.contains(value) == true
    }

    private fun containsKeyInMapField(target: Any, fieldName: String, key: String?): Boolean {
        if (key == null) return false
        return (target.readFieldOrNull(fieldName) as? Map<*, *>)?.containsKey(key) == true
    }

    private fun Any.writeFieldIfExists(fieldName: String, value: Any?) {
        var c: Class<*>? = javaClass
        while (c != null) {
            try {
                val field = c.getDeclaredField(fieldName)
                field.isAccessible = true
                field.set(this, value)
                return
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (_: Throwable) {
                return
            }
        }
    }

    private fun writeIntFieldIfExists(target: Any, fieldName: String, value: Int) {
        var c: Class<*>? = target.javaClass
        while (c != null) {
            try {
                val field = c.getDeclaredField(fieldName)
                field.isAccessible = true
                field.setInt(target, value)
                return
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            } catch (_: Throwable) {
                return
            }
        }
    }

    private fun findFanModePreferenceFromScreen(screen: Any?): Any? {
        if (screen == null) return null
        return try {
            val method = screen.javaClass.findPublicOrDeclaredMethod("findPreference", CharSequence::class.java)
            method?.invoke(screen, KEY_FAN_MODE)
        } catch (t: Throwable) {
            logError("findPreference(fan_mode) failed", t)
            null
        }
    }

    private fun ensureExtremeMode(controller: Any?, preference: Any?) {
        if (preference == null) return
        if (!isDropDownPreference(preference)) return

        try {
            appendEntries(preference)
            appendEntryValues(preference)
            appendSummaries(preference)
            writePreferenceRef(controller, preference)
            logInfo("Extreme fan mode entry ensured")
        } catch (t: Throwable) {
            logError("Failed to ensure extreme fan mode entry", t)
        }
    }

    private fun isDropDownPreference(preference: Any): Boolean {
        var c: Class<*>? = preference.javaClass
        while (c != null) {
            if (c.name == "miuix.preference.DropDownPreference") return true
            c = c.superclass
        }
        return false
    }

    private fun appendEntries(preference: Any) {
        val current = callCharSequenceArray(preference, "getEntries")
        if (current.any { it.toString() == MODE_EXTREME_TITLE }) return
        callVoid(preference, "setEntries", arrayOf(CharSequence::class.java.arrayType()), current.append(MODE_EXTREME_TITLE))
    }

    private fun appendEntryValues(preference: Any) {
        val current = callCharSequenceArray(preference, "getEntryValues")
        if (current.any { it.toString() == MODE_EXTREME_VALUE }) return
        callVoid(preference, "setEntryValues", arrayOf(CharSequence::class.java.arrayType()), current.append(MODE_EXTREME_VALUE))
    }

    private fun appendSummaries(preference: Any) {
        val existing = readSummariesFromAdapter(preference)
        if (existing.any { it.toString() == MODE_EXTREME_SUMMARY }) return
        val baseCount = callCharSequenceArray(preference, "getEntries").size
        val normalized = normalizeSummaryCount(existing, baseCount - 1)
        val withExtreme = normalized.append(MODE_EXTREME_SUMMARY)
        if (!callVoid(preference, "setSummaries", arrayOf(CharSequence::class.java.arrayType()), withExtreme)) {
            logInfo("DropDownPreference#setSummaries not available; title/value still appended")
        }
    }

    private fun readSummariesFromAdapter(preference: Any): Array<CharSequence> {
        val adapter = preference.readFieldOrNull("mContentAdapter") ?: preference.readFieldOrNull("mAdapter")
        if (adapter != null) {
            val fromField = adapter.readFirstCharSequenceArrayField(arrayOf("mSummaries", "mEntrySummaries", "mSummary"))
            if (fromField != null) return fromField
        }
        return emptyArray()
    }

    private fun normalizeSummaryCount(existing: Array<CharSequence>, expectedCount: Int): Array<CharSequence> {
        val result = ArrayList<CharSequence>(expectedCount.coerceAtLeast(0))
        for (i in 0 until expectedCount.coerceAtLeast(0)) {
            result += existing.getOrNull(i) ?: ""
        }
        return result.toTypedArray()
    }

    private fun writePreferenceRef(controller: Any?, preference: Any) {
        if (controller == null) return
        try {
            val field = controller.javaClass.getDeclaredField("mDropDownPreferenceRef")
            field.isAccessible = true
            field.set(controller, WeakReference(preference))
        } catch (_: Throwable) {
            // displayPreference 原方法已设置该字段；这里失败不影响新增条目。
        }
    }

    private fun callCharSequenceArray(target: Any, methodName: String): Array<CharSequence> {
        return try {
            val value = target.javaClass.findPublicOrDeclaredMethod(methodName)?.invoke(target)
            (value as? Array<*>)?.map { it?.toString().orEmpty() }?.toTypedArray() ?: emptyArray()
        } catch (_: Throwable) {
            emptyArray()
        }
    }

    private fun callVoid(target: Any, methodName: String, parameterTypes: Array<Class<*>>, arg: Any): Boolean {
        return try {
            val method = target.javaClass.findPublicOrDeclaredMethod(methodName, *parameterTypes) ?: return false
            method.invoke(target, arg)
            true
        } catch (t: Throwable) {
            logError("Call $methodName failed", t)
            false
        }
    }

    private fun Class<*>.findPublicOrDeclaredMethod(name: String, vararg parameterTypes: Class<*>): Method? {
        var c: Class<*>? = this
        while (c != null) {
            try {
                val method = c.getDeclaredMethod(name, *parameterTypes)
                method.isAccessible = true
                return method
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        return try {
            getMethod(name, *parameterTypes).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    private fun Any.readFieldOrNull(fieldName: String): Any? {
        var c: Class<*>? = javaClass
        while (c != null) {
            try {
                val field = c.getDeclaredField(fieldName)
                field.isAccessible = true
                return field.get(this)
            } catch (_: NoSuchFieldException) {
                c = c.superclass
            }
        }
        return null
    }

    private fun Any.readFirstCharSequenceArrayField(names: Array<String>): Array<CharSequence>? {
        for (name in names) {
            val value = readFieldOrNull(name)
            if (value is Array<*>) {
                return value.map { it?.toString().orEmpty() }.toTypedArray()
            }
        }
        return null
    }

    private fun Array<CharSequence>.append(value: CharSequence): Array<CharSequence> {
        val list = ArrayList<CharSequence>(size + 1)
        list.addAll(this)
        list.add(value)
        return list.toTypedArray()
    }

    private fun logInfo(message: String) {
        log(Log.INFO, TAG, message)
    }

    private fun logError(message: String, throwable: Throwable) {
        log(Log.ERROR, TAG, message, throwable)
    }

    companion object {
        @Volatile
        private var wasExtremeModeActive = false
        @Volatile
        private var lastFanModeSeen = -1
        @Volatile
        private var extremeToSmartBridgeInProgress = false

        private const val TAG = "FanModeHook"
        private const val TARGET_PACKAGE = "com.android.settings"
        private const val POWERKEEPER_PACKAGE = "com.miui.powerkeeper"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val POWERKEEPER_BASE_CLASS = "com.miui.powerkeeper.unionpower.corehandler.a"
        private const val FAN_MODE_CONTROLLER = "com.android.settings.coolingfan.FanModeController"
        private const val FAN_STATE_HANDLER = "com.miui.powerkeeper.unionpower.corehandler.FanStateHandler"
        private const val SYSTEMUI_COOLING_FAN_CONTROLLER = "com.android.systemui.controlcenter.policy.CoolingFanController"
        private const val SYSTEMUI_COOLING_FAN_DETAIL_ADAPTER = "com.android.systemui.qs.tiles.CoolingFanTile\$CoolingFanDetailAdapter"
        private const val KEY_FAN_MODE = "fan_mode"
        private const val KEY_COOLING_FAN_ENABLE = "cooling_fan_enable"
        private const val KEY_FAN_MODE_RANGE = "fan_mode_range"
        private const val KEY_SCENE_GAMING = "fan_mode_scene_gaming"
        private const val KEY_SCENE_RAPID_CHARGE = "fan_mode_scene_rapid_charge"
        private const val KEY_SCENE_NAVIGATION = "fan_mode_scene_navigation"
        private const val KEY_SMART_STOP_RECORDING = "fan_smart_stop_on_recording"
        private const val TARGET_LEVEL = "target_level"
        private const val SYSTEMUI_FALLBACK_MODE_SMART_STRING_RES = 0x7f140c70
        private const val SYSTEMUI_FALLBACK_MODE_HIGH_STRING_RES = 0x7f140c6e
        private const val SYSTEMUI_FALLBACK_MODE_QUIET_STRING_RES = 0x7f140c6f
        private const val SYSTEMUI_FALLBACK_MODE_PERFORMANCE_ICON_RES = 0x7f0812c9
        private const val MODE_SMART_VALUE = "-1"
        private const val MODE_HIGH_VALUE = "2"
        private const val MODE_EXTREME_VALUE = "4"
        private const val EXTREME_TO_SMART_PK_DELAY_MS = 800L
        private const val MODE_EXTREME_TITLE = "狂暴模式"
        private const val MODE_EXTREME_SUMMARY = "疾速冷却，最大幅度提升散热能力"
    }
}
