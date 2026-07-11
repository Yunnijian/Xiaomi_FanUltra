package com.mifan.kt

import android.content.ContentResolver
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.lang.ref.WeakReference
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
                MISETTINGS_PACKAGE -> {
                    installMiSettingsFanConfigHostHooks(param.classLoader)
                    logInfo("MISettings fan config host hooks installed in ${param.packageName}")
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

        logSettingsProbe(classLoader, controllerClass, preferenceClass, preferenceScreenClass)

        runCatching {
            val getCurrentMode = controllerClass.getDeclaredMethod("getCurrentMode")
            getCurrentMode.isAccessible = true
            hook(getCurrentMode).intercept(XposedInterface.Hooker { chain ->
                val resolver = readControllerContentResolver(chain.thisObject)
                if (isCustomFanModeUiEnabled(resolver)) {
                    logInfo("FanModeController.getCurrentMode spoofed for UI: return custom(5) while real fan_mode=-1")
                    MODE_CUSTOM_VALUE
                } else {
                    chain.proceed()
                }
            })
        }.onFailure {
            logError("Failed to hook FanModeController.getCurrentMode for custom UI spoof", it)
        }

        hook(displayPreference).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            val controller = chain.thisObject
            val screen = chain.getArg(0)
            val fanModePreference = findPreferenceFromScreen(screen, KEY_FAN_MODE)
            ensureExtremeMode(controller, fanModePreference)
            ensureKeepFanOnScreenOffSwitch(classLoader, screen)
            ensurePerAppFanConfigEntry(classLoader, screen)
            updateCustomModePreferenceVisibility(screen, null)
            result
        })

        hook(updateState).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            val controller = chain.thisObject
            val preference = chain.getArg(0)
            ensureExtremeMode(controller, preference)
            updatePerAppFanConfigEntryVisibilityFromPreference(preference, null)
            updateCustomModePreferenceVisibilityFromPreference(preference, null)
            result
        })

        hook(onPreferenceChange).intercept(XposedInterface.Hooker { chain ->
            val value = chain.getArg(1)?.toString()
            val preference = chain.getArg(0)
            val resolver = getPreferenceContentResolver(preference)
            if (value == MODE_CUSTOM_VALUE) {
                resolver?.let {
                    enterCustomFanMode(it, "Settings")
                }
                updatePerAppFanConfigEntryVisibilityFromPreference(preference, true)
                updateCustomModePreferenceVisibilityFromPreference(preference, true)
                logInfo("Custom fan mode selected; keep fan_mode=-1 and enable mifan custom mode")
                true
            } else {
                resolver?.let { exitCustomFanMode(it, "Settings") }
                if (value == MODE_EXTREME_VALUE) {
                    logInfo("Extreme fan mode selected; let Settings write fan_mode=4")
                }
                val result = chain.proceed()
                updatePerAppFanConfigEntryVisibilityFromPreference(preference, false)
                updateCustomModePreferenceVisibilityFromPreference(preference, false)
                result
            }
        })

        installCustomModeSceneVisibilityControllerHooks(classLoader, preferenceClass)

        runCatching {
            val enableControllerClass = classLoader.loadClass(COOLING_FAN_ENABLE_CONTROLLER)
            val enableOnPreferenceChange = enableControllerClass.getMethod(
                "onPreferenceChange",
                preferenceClass,
                Any::class.java
            )
            hook(enableOnPreferenceChange).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                val enabled = chain.getArg(1) as? Boolean
                updateKeepFanOnScreenOffSwitchVisibilityFromPreference(chain.getArg(0), enabled)
                updatePerAppFanConfigEntryVisibilityFromPreference(chain.getArg(0), null)
                updateCustomModePreferenceVisibilityFromPreference(chain.getArg(0), null)
                result
            })
        }.onFailure {
            logError("Failed to hook CoolingFanEnableController for keep fan switch visibility", it)
        }
    }

    private fun installCustomModeSceneVisibilityControllerHooks(classLoader: ClassLoader, preferenceClass: Class<*>) {
        runCatching {
            val usageScenesClass = classLoader.loadClass(FAN_MODE_USAGE_SCENES_CONTROLLER)
            val isVisible = usageScenesClass.getDeclaredMethod("isVisible")
            isVisible.isAccessible = true
            hook(isVisible).intercept(XposedInterface.Hooker { chain ->
                val resolver = readControllerContentResolver(chain.thisObject)
                if (isCustomFanModeSelected(resolver)) {
                    logInfo("Custom mode usage scenes category forced visible")
                    true
                } else {
                    chain.proceed()
                }
            })
        }.onFailure {
            logError("Failed to hook FanModeUsageScenesController visibility for custom mode", it)
        }

        runCatching {
            val rangeClass = classLoader.loadClass(FAN_MODE_RANGE_CONTROLLER)
            val updateState = rangeClass.getMethod("updateState", preferenceClass)
            hook(updateState).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                val resolver = readControllerContentResolver(chain.thisObject)
                if (isCustomFanModeSelected(resolver)) {
                    setPreferenceVisible(chain.getArg(0), false)
                    logInfo("Custom mode range preference hidden by FanModeRangeController hook")
                }
                result
            })
        }.onFailure {
            logError("Failed to hook FanModeRangeController visibility for custom mode", it)
        }

        runCatching {
            val gameSceneClass = classLoader.loadClass(FAN_SCENE_GAME_CONTROLLER)
            val updateState = gameSceneClass.getMethod("updateState", preferenceClass)
            hook(updateState).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                val preference = chain.getArg(0)
                rememberGameScenePreference(preference)
                val resolver = readControllerContentResolver(chain.thisObject)
                if (isCustomFanModeSelected(resolver)) {
                    setPreferenceVisible(preference, false)
                    logInfo("Custom mode game scene preference hidden by FanSceneGameController hook, cached=${preference != null}")
                }
                result
            })
        }.onFailure {
            logError("Failed to hook FanSceneGameController visibility for custom mode", it)
        }

        for (className in arrayOf(FAN_SCENE_RAPID_CHARGE_CONTROLLER, FAN_SCENE_NAVIGATION_CONTROLLER)) {
            runCatching {
                val sceneClass = classLoader.loadClass(className)
                val updateState = sceneClass.getMethod("updateState", preferenceClass)
                hook(updateState).intercept(XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    val resolver = readControllerContentResolver(chain.thisObject)
                    if (isCustomFanModeSelected(resolver)) {
                        setPreferenceVisible(chain.getArg(0), true)
                        logInfo("Custom mode scene preference forced visible by ${sceneClass.simpleName}")
                    }
                    result
                })
            }.onFailure {
                logError("Failed to hook $className visibility for custom mode", it)
            }
        }

        installSettingsCustomModeObserverSyncHooks(classLoader)
    }

    private fun installSettingsCustomModeObserverSyncHooks(classLoader: ClassLoader) {
        runCatching {
            val observerClass = classLoader.loadClass("$FAN_MODE_CONTROLLER$FAN_MODE_CONTENT_OBSERVER_SUFFIX")
            val onChange = observerClass.getMethod("onChange", Boolean::class.javaPrimitiveType)
            hook(onChange).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                syncCustomModeVisibilityFromObserver(
                    chain.thisObject,
                    "mDropDownPreferenceRef",
                    "FanModeContentObserver"
                )
                result
            })
        }.onFailure {
            logError("Failed to hook FanModeContentObserver for custom visibility sync", it)
        }

        runCatching {
            val observerClass = classLoader.loadClass("$FAN_MODE_USAGE_SCENES_CONTROLLER$FAN_MODE_USAGE_SCENES_CONTENT_OBSERVER_SUFFIX")
            val onChange = observerClass.getMethod("onChange", Boolean::class.javaPrimitiveType)
            hook(onChange).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                syncCustomModeVisibilityFromObserver(
                    chain.thisObject,
                    "mUsageScenesCategoryRef",
                    "FanModeUsageScenesContentObserver"
                )
                result
            })
        }.onFailure {
            logError("Failed to hook FanModeUsageScenesContentObserver for custom visibility sync", it)
        }
    }

    private fun installPowerKeeperHooks(classLoader: ClassLoader) {
        val handlerClass = classLoader.loadClass(FAN_STATE_HANDLER)
        logPowerKeeperProbe(classLoader, handlerClass)
        val writePathMethod = handlerClass.getDeclaredMethod("J", String::class.java, String::class.java)
        writePathMethod.isAccessible = true

        // 官方会在 FanStateHandler.W(state) 中把息屏/锁屏视为高优先级禁用场景，
        // 非快充状态下最终写 target_level=0。开关开启时临时屏蔽 state.g(screenLock)，
        // 让智能调频/静谧/高速强冷/狂暴模式都继续走官方亮屏策略。
        runCatching {
            val stateClass = classLoader.loadClass(FAN_STATE_HANDLER + "$" + "g")
            val updateFanState = handlerClass.getDeclaredMethod("W", stateClass)
            updateFanState.isAccessible = true
            hook(updateFanState).intercept(XposedInterface.Hooker { chain ->
                val handler = chain.thisObject
                val state = chain.getArg(0)
                val resolver = readPowerKeeperResolver(handler)
                val keepOnScreenOff = isKeepFanOnScreenOffEnabled(resolver)
                val coolingFanEnabled = resolver?.let { Settings.System.getInt(it, KEY_COOLING_FAN_ENABLE, 1) == 1 } ?: true
                if (!keepOnScreenOff || !coolingFanEnabled || state == null) {
                    chain.proceed()
                } else {
                    val oldScreenLocked = readBooleanField(state, "g", false)
                    try {
                        if (oldScreenLocked) {
                            state.writeFieldIfExists("g", false)
                            logInfo("Keep fan on screen off enabled; mask FanStateHandler state.g screenLock=false")
                        }
                        chain.proceed()
                    } finally {
                        if (oldScreenLocked) state.writeFieldIfExists("g", oldScreenLocked)
                    }
                }
            })
        }.onFailure {
            logError("Failed to hook FanStateHandler.W for keep-on-screen-off", it)
        }

        // PowerKeeper 可能只把官方档位映射到 target_level。
        // 只在 fan_mode=4 且符合官方总开关、使用范围、场景限制时，把 target_level 提升为 4。
        // 非狂暴模式，或狂暴模式但当前官方策略不允许风扇运行时，完全放行/交给 applyExtremePolicy 降回 0，
        // 避免干扰官方智能调频/静谧/高速强冷，也避免狂暴模式绕过官方使用范围。
        hook(writePathMethod).intercept(XposedInterface.Hooker { chain ->
            val key = chain.getArg(0)?.toString()
            val requestedValue = chain.getArg(1)?.toString()
            if (key == TARGET_LEVEL) {
                val perAppLevel = resolveEffectivePerAppFanLevel(chain.thisObject)
                if (perAppLevel != null && perAppLevel != PER_APP_LEVEL_SMART) {
                    val finalValue = perAppLevel.toString()
                    updateLastPositiveFanLevel(key, finalValue)
                    if (requestedValue != finalValue) {
                        logInfo("Custom per-app fan rule matched; override target_level=$requestedValue -> $finalValue")
                    }
                    chain.proceed(arrayOf(TARGET_LEVEL, finalValue))
                } else {
                    updateLastPositiveFanLevel(key, requestedValue)
                    val keepLevel = keepCurrentFanLevelOnScreenOff(chain.thisObject, requestedValue)
                    if (keepLevel != null) {
                        logInfo("Keep fan on screen off; preserve target_level=$requestedValue -> $keepLevel")
                        chain.proceed(arrayOf(TARGET_LEVEL, keepLevel.toString()))
                    } else if (shouldPromoteOfficialLevelToExtreme(chain.thisObject, requestedValue)) {
                        logInfo("Extreme fan mode follows official policy; promote target_level=$requestedValue -> 4")
                        chain.proceed(arrayOf(TARGET_LEVEL, MODE_EXTREME_VALUE))
                    } else {
                        chain.proceed()
                    }
                }
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
            // ForegroundInfo 变化只会更新 FanStateHandler.x，不一定立刻触发官方 target_level 写入。
            // 因此分应用规则需要在消息处理后主动补写一次。
            if (!applyPerAppFanPolicy(chain.thisObject, writePathMethod)) {
                applyExtremePolicy(chain.thisObject, writePathMethod)
            }
            result
        })

        // 初始化/重读系统设置时也补写一次，避免 PowerKeeper 重启后状态不同步。
        val reloadSettings = handlerClass.getDeclaredMethod("o")
        reloadSettings.isAccessible = true
        hook(reloadSettings).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            if (!applyPerAppFanPolicy(chain.thisObject, writePathMethod)) {
                applyExtremePolicy(chain.thisObject, writePathMethod)
            }
            result
        })
    }


    private fun installSystemUiHooks(classLoader: ClassLoader) {
        val controllerClass = classLoader.loadClass(SYSTEMUI_COOLING_FAN_CONTROLLER)
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
            val adapterClass = classLoader.loadClass(SYSTEMUI_COOLING_FAN_DETAIL_ADAPTER)
            logSystemUiProbe(controllerClass, adapterClass)

            runCatching {
                val updateItems = adapterClass.getDeclaredMethod("updateItems")
                updateItems.isAccessible = true
                hook(updateItems).intercept(XposedInterface.Hooker { chain ->
                    // 必须在原 updateItems() 执行前补齐模型列表；原方法会基于 _secondaryItems 生成二级页。
                    ensureSystemUiModeItemsFromAdapter(chain.thisObject, classLoader)
                    // PowerKeeper 侧为了稳定退出会短暂写 fan_mode=2。
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

    private fun ensureSystemUiModeItems(controller: Any?, classLoader: ClassLoader) {
        if (controller == null) return
        try {
            val current = controller.readFieldOrNull("_secondaryItems") as? List<*> ?: return
            // Do not rewrite built-in SystemUI identities here. On this ROM their identity values are part of
            // SystemUI/PowerKeeper's own contract; rewriting them can break official modes and duplicate entries.
            val context = readSystemUiContext(controller)
            val titleRes = context?.resourceId("quick_settings_coolingfan_mode_high", "string")?.takeIf { it != 0 } ?: SYSTEMUI_FALLBACK_MODE_HIGH_STRING_RES
            val iconRes = context?.resourceId("ic_qs_coolingfan_mode_performance", "drawable")?.takeIf { it != 0 } ?: SYSTEMUI_FALLBACK_MODE_PERFORMANCE_ICON_RES
            val updated = ArrayList<Any>(current.size + 2)
            current.filterNotNull().forEach { updated.add(it) }
            val appendExtreme = current.none { it.selectableIdentityAsInt() == MODE_EXTREME_VALUE.toInt() }
            val appendCustom = current.none { it.selectableIdentityAsInt() == MODE_CUSTOM_VALUE.toInt() }
            if (appendExtreme) {
                updated.add(createSystemUiSelectableModel(classLoader, MODE_EXTREME_VALUE.toInt(), titleRes, 3, MODE_EXTREME_TITLE, iconRes))
            }
            if (appendCustom) {
                updated.add(createSystemUiSelectableModel(classLoader, MODE_CUSTOM_VALUE.toInt(), titleRes, 4, MODE_CUSTOM_TITLE, iconRes))
            }
            if (!appendExtreme && !appendCustom) return
            controller.writeFieldIfExists("_secondaryItems", updated)
            logInfo("SystemUI cooling fan extra mode items appended: extreme=$appendExtreme, custom=$appendCustom")
        } catch (t: Throwable) {
            logError("Append SystemUI extreme item failed", t)
        }
    }


    private fun ensureSystemUiModeItemsFromAdapter(adapter: Any?, classLoader: ClassLoader) {
        if (adapter == null) return
        val tile = adapter.readFieldOrNull("this$0") ?: return
        val controller = tile.readFieldOrNull("coolingFanController") ?: return
        ensureSystemUiModeItems(controller, classLoader)
    }

    private fun maskSystemUiBridgeStateFromAdapter(adapter: Any?) {
        try {
            val tile = adapter?.readFieldOrNull("this$0") ?: return
            val controller = tile.readFieldOrNull("coolingFanController") ?: return
            val context = readSystemUiContext(tile) ?: readSystemUiContext(controller) ?: return
            val rawFanMode = (controller.readFieldOrNull("fanModeState") as? Number)?.toInt() ?: return
            val displayFanMode = resolveSystemUiDisplayFanMode(context.contentResolver, rawFanMode)
            if (displayFanMode != rawFanMode) {
                controller.writeFieldIfExists("fanModeState", Integer.valueOf(displayFanMode))
                logInfo("SystemUI bridge fan mode masked $rawFanMode -> $displayFanMode")
            }
        } catch (t: Throwable) {
            logError("Mask SystemUI bridge state failed", t)
        }
    }

    private fun rebuildSystemUiDetailItems(adapter: Any?, classLoader: ClassLoader) {
        if (adapter == null) return
        try {
            val detailView = adapter.readFieldOrNull("detailView") ?: return
            val tile = adapter.readFieldOrNull("this$0") ?: return
            val controller = tile.readFieldOrNull("coolingFanController") ?: return
            ensureSystemUiModeItems(controller, classLoader)
            if (controller.readFieldOrNull("toggleState") != true) return

            val context = readSystemUiContext(tile) ?: return
            val modelItems = controller.readFieldOrNull("_secondaryItems") as? List<*> ?: return
            val qsItemClass = classLoader.loadClass("com.android.systemui.qs.QSDetailContent\$Item")
            val dividerClass = classLoader.loadClass("com.android.systemui.qs.QSDetailContent\$TextDividerItem")
            val selectableClass = classLoader.loadClass("com.android.systemui.qs.QSDetailContent\$SelectableItem")
            val dividerTitle = context.stringByName("quick_settings_coolingfan_mode") ?: "风扇模式"
            val selectedIcon = context.resourceId("ic_qs_coolingfan_mode_selected", "drawable")
            val rawFanMode = (controller.readFieldOrNull("fanModeState") as? Number)?.toInt() ?: -1
            val fanMode = resolveSystemUiDisplayFanMode(context.contentResolver, rawFanMode)

            val items = ArrayList<Any>()
            items.add(dividerClass.getConstructor(CharSequence::class.java).newInstance(dividerTitle))

            modelItems.filterNotNull().forEach { model ->
                val identity = model.selectableIdentityAsInt()
                val item = selectableClass.getDeclaredConstructor().newInstance()
                val title = when (identity) {
                    MODE_EXTREME_VALUE.toInt() -> MODE_EXTREME_TITLE
                    MODE_CUSTOM_VALUE.toInt() -> MODE_CUSTOM_TITLE
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
                    ?: if (identity == MODE_EXTREME_VALUE.toInt() || identity == MODE_CUSTOM_VALUE.toInt()) context.resourceId("ic_qs_coolingfan_mode_performance", "drawable") else 0
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

    private fun handleSystemUiDetailItemClick(adapter: Any?, item: Any?, proceed: () -> Any?): Any? {
        val model = readSystemUiDetailItemTag(item) ?: return proceed()
        val identity = model.selectableIdentityAsInt() ?: return proceed()
        val tile = adapter?.readFieldOrNull("this$0") ?: return proceed()
        val controller = tile.readFieldOrNull("coolingFanController") ?: return proceed()
        val context = readSystemUiContext(tile) ?: readSystemUiContext(controller) ?: return proceed()
        return if (identity == MODE_CUSTOM_VALUE.toInt()) {
            enterCustomFanMode(context.contentResolver, "SystemUI")
            controller.writeFieldIfExists("fanModeState", Integer.valueOf(MODE_CUSTOM_VALUE.toInt()))
            logInfo("SystemUI custom fan mode selected; keep fan_mode=-1 and enable mifan custom mode")
            adapter.javaClass.findPublicOrDeclaredMethod("updateItems")?.invoke(adapter)
            null
        } else {
            exitCustomFanMode(context.contentResolver, "SystemUI")
            val result = proceed()
            val writtenMode = Settings.System.getInt(context.contentResolver, KEY_FAN_MODE, MODE_SMART_VALUE.toInt())
            if (writtenMode == MODE_CUSTOM_VALUE.toInt()) {
                enterCustomFanMode(context.contentResolver, "SystemUI legacy correction")
                controller.writeFieldIfExists("fanModeState", Integer.valueOf(MODE_CUSTOM_VALUE.toInt()))
                logInfo("SystemUI legacy fan_mode=5 write corrected to scheme B")
            }
            result
        }
    }

    private fun readSystemUiDetailItemTag(item: Any?): Any? {
        if (item == null) return null
        return try {
            item.javaClass.findPublicOrDeclaredMethod("getTag")?.invoke(item) ?: item.readFieldOrNull("tag")
        } catch (_: Throwable) {
            item.readFieldOrNull("tag")
        }
    }

    private fun resolveSystemUiDisplayFanMode(resolver: ContentResolver?, fanMode: Int): Int {
        if (isCustomFanModeSelected(resolver)) return MODE_CUSTOM_VALUE.toInt()
        return maskTransientBridgeFanModeForSystemUi(resolver, fanMode)
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
            if (current is android.content.Context) return current
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

    private fun shouldPromoteOfficialLevelToExtreme(handler: Any?, requestedValue: String?): Boolean {
        if (!isExtremeModeSelected(handler)) return false
        val requestedLevel = requestedValue?.toIntOrNull() ?: return false
        return requestedLevel != MODE_EXTREME_VALUE.toInt() &&
            (requestedLevel > 0 || shouldForceExtremeLevel(handler))
    }

    private fun readPowerKeeperResolver(handler: Any?): ContentResolver? {
        powerKeeperResolverRef?.get()?.let { return it }
        if (handler != null) {
            val direct = handler.readFieldOrNull(FIELD_PK_RESOLVER_PRIMARY)
                ?: handler.readFieldOrNull(FIELD_PK_RESOLVER_SECONDARY)
            if (direct is ContentResolver) return rememberPowerKeeperResolver(direct)

            val classLoader = handler.javaClass.classLoader
            val context = classLoader?.loadClass(POWERKEEPER_BASE_CLASS)
                ?.getDeclaredField(FIELD_PK_BASE_CONTEXT)
                ?.apply { isAccessible = true }
                ?.get(null)
            val method = context?.javaClass?.getMethod("getContentResolver")
            val resolver = method?.invoke(context)
            if (resolver is ContentResolver) return rememberPowerKeeperResolver(resolver)
        }
        return null
    }

    private fun rememberPowerKeeperResolver(resolver: ContentResolver): ContentResolver {
        powerKeeperResolverRef = WeakReference(resolver)
        return resolver
    }

    private fun resolveEffectivePerAppFanLevel(handler: Any?): Int? {
        if (handler == null) return null
        val resolver = readPowerKeeperResolver(handler) ?: return null
        if (!isCustomFanModeSelected(resolver)) return null
        val packageName = handler.readFieldOrNull("x")?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val appLevel = resolvePerAppFanLevel(handler)
        return resolveEffectiveCustomFanLevel(handler, resolver, packageName, appLevel)
    }

    private fun applyPerAppFanPolicy(handler: Any?, writePathMethod: Method): Boolean {
        if (handler == null) return false
        val resolver = readPowerKeeperResolver(handler) ?: return false
        if (!isCustomFanModeSelected(resolver)) return false

        val packageName = handler.readFieldOrNull("x")?.toString()?.takeIf { it.isNotBlank() } ?: return false
        val appLevel = resolvePerAppFanLevel(handler)
        val sceneLevel = resolveCustomSceneLevel(handler, resolver, packageName)
        val level = mergeCustomFanLevels(appLevel, sceneLevel)

        val now = System.currentTimeMillis()
        val hasAnyRule = Settings.System.getString(resolver, KEY_PER_APP_FAN_RULES)?.isNotBlank() == true
        if (level == null || level == PER_APP_LEVEL_SMART) {
            var shouldLogSmartDefault = false
            var shouldResetFromForcedApp = false
            var previousForcedPackage: String? = null
            synchronized(powerKeeperStateLock) {
                if (hasAnyRule && packageName != lastPerAppNoMatchPackage) {
                    lastPerAppNoMatchPackage = packageName
                    shouldLogSmartDefault = true
                }

                // 如果上一个前台应用被自定义规则强制到固定档位，切到未配置/智能调频应用后，
                // PowerKeeper 不一定会立刻重新写 target_level，导致风扇停留在上一个应用的档位。
                // 这里主动写 0 释放上一条固定档位，然后继续放行官方智能调频后续接管。
                shouldResetFromForcedApp = lastPerAppAppliedLevel >= 0 &&
                    lastPerAppAppliedPackage != null &&
                    packageName != lastPerAppAppliedPackage &&
                    now - lastPerAppAppliedAt >= PER_APP_ACTIVE_WRITE_MIN_INTERVAL_MS
                if (shouldResetFromForcedApp) previousForcedPackage = lastPerAppAppliedPackage
            }
            if (shouldLogSmartDefault) {
                logInfo("Custom per-app fan check: pkg=$packageName, smart/default mode; allow official smart policy")
            }
            if (shouldResetFromForcedApp) {
                writeTargetLevelByPowerKeeper(
                    handler,
                    writePathMethod,
                    PER_APP_LEVEL_OFF,
                    "Custom per-app fan reset to smart/default: pkg=$packageName, previous=$previousForcedPackage"
                )
                synchronized(powerKeeperStateLock) {
                    lastPerAppAppliedPackage = packageName
                    lastPerAppAppliedLevel = PER_APP_LEVEL_SMART
                    lastPerAppAppliedAt = now
                }
                return true
            }
            return false
        }

        val recentlyApplied = synchronized(powerKeeperStateLock) {
            packageName == lastPerAppAppliedPackage &&
                level == lastPerAppAppliedLevel &&
                now - lastPerAppAppliedAt < PER_APP_ACTIVE_WRITE_MIN_INTERVAL_MS
        }
        if (recentlyApplied) return true

        val reason = when {
            sceneLevel != null && appLevel != null && appLevel != PER_APP_LEVEL_SMART -> "Custom fan app/scene max applied: pkg=$packageName, appLevel=$appLevel, sceneLevel=$sceneLevel"
            sceneLevel != null -> "Custom fan scene fallback applied: pkg=$packageName, sceneLevel=$sceneLevel"
            else -> "Custom per-app fan policy applied: pkg=$packageName"
        }
        writeTargetLevelByPowerKeeper(handler, writePathMethod, level, reason)
        synchronized(powerKeeperStateLock) {
            lastPerAppAppliedPackage = packageName
            lastPerAppAppliedLevel = level
            lastPerAppAppliedAt = now
            lastPerAppNoMatchPackage = null
        }
        return true
    }

    private fun applyExtremePolicy(handler: Any?, writePathMethod: Method) {
        if (handler == null) return
        val resolver = readPowerKeeperResolver(handler)
        val mode = resolver?.let { Settings.System.getInt(it, KEY_FAN_MODE, -1) } ?: -1

        // 非狂暴模式原则上交还 PowerKeeper 原生逻辑处理。
        // 只有“刚从狂暴模式切回智能调频”这一种场景，先桥接到官方高速强冷，
        // 让底层 target_level 从 4 回到官方档位，再延迟切回 fan_mode=-1。
        if (mode != 4) {
            val shouldBridge = synchronized(powerKeeperStateLock) {
                val bridge = mode == MODE_SMART_VALUE.toInt() &&
                    (wasExtremeModeActive || lastFanModeSeen == MODE_EXTREME_VALUE.toInt())
                wasExtremeModeActive = false
                lastFanModeSeen = mode
                bridge
            }
            if (shouldBridge) {
                bridgeExtremeToSmartInPowerKeeper(handler, writePathMethod, resolver)
            }
            return
        }

        synchronized(powerKeeperStateLock) {
            lastFanModeSeen = 4
        }
        if (shouldForceExtremeLevel(handler)) {
            synchronized(powerKeeperStateLock) {
                wasExtremeModeActive = true
            }
            writeTargetLevelByPowerKeeper(handler, writePathMethod, 4)
        } else {
            writeTargetLevelByPowerKeeper(handler, writePathMethod, 0)
        }
    }

    private fun bridgeExtremeToSmartInPowerKeeper(handler: Any?, writePathMethod: Method, resolver: ContentResolver?) {
        if (handler == null || resolver == null) return
        if (!extremeToSmartBridgeInProgress.compareAndSet(false, true)) return
        try {
            logInfo("Extreme -> smart bridge in PowerKeeper: target_level=2, fan_mode=2, then fan_mode=-1")
            Settings.System.putLong(
                resolver,
                KEY_EXTREME_TO_SMART_BRIDGE_UNTIL,
                System.currentTimeMillis() + EXTREME_TO_SMART_PK_DELAY_MS + SYSTEMUI_BRIDGE_MASK_EXTRA_MS
            )
            writeTargetLevelByPowerKeeper(handler, writePathMethod, MODE_HIGH_VALUE.toInt())
            Settings.System.putInt(resolver, KEY_FAN_MODE, MODE_HIGH_VALUE.toInt())
            mainHandler.postDelayed({
                try {
                    Settings.System.putInt(resolver, KEY_FAN_MODE, MODE_SMART_VALUE.toInt())
                    logInfo("Extreme -> smart PowerKeeper bridge completed: fan_mode=-1")
                } catch (t: Throwable) {
                    logError("Extreme -> smart PowerKeeper bridge failed", t)
                } finally {
                    extremeToSmartBridgeInProgress.set(false)
                }
            }, EXTREME_TO_SMART_PK_DELAY_MS)
        } catch (t: Throwable) {
            extremeToSmartBridgeInProgress.set(false)
            logError("Start extreme -> smart PowerKeeper bridge failed", t)
        }
    }

    private fun maskTransientBridgeFanModeForSystemUi(resolver: ContentResolver?, fanMode: Int): Int {
        if (resolver == null || fanMode != MODE_HIGH_VALUE.toInt()) return fanMode
        return try {
            val bridgeUntil = Settings.System.getLong(resolver, KEY_EXTREME_TO_SMART_BRIDGE_UNTIL, 0L)
            if (bridgeUntil > System.currentTimeMillis()) MODE_SMART_VALUE.toInt() else fanMode
        } catch (_: Throwable) {
            fanMode
        }
    }

    private fun updateLastPositiveFanLevel(key: String?, requestedValue: String?) {
        if (key != TARGET_LEVEL) return
        val level = requestedValue?.toIntOrNull() ?: return
        if (level > 0) {
            synchronized(powerKeeperStateLock) {
                lastPositiveFanLevel = level
                lastPositiveFanLevelAt = System.currentTimeMillis()
            }
        }
    }

    private fun keepCurrentFanLevelOnScreenOff(handler: Any?, requestedValue: String?): Int? {
        if (handler == null || requestedValue != "0") return null
        val resolver = readPowerKeeperResolver(handler) ?: return null
        val fastChargeActive = readBooleanField(handler, FIELD_PK_FAST_CHARGE_ACTIVE, false)
        if (!isKeepFanOnScreenOffEnabled(resolver) && !fastChargeActive) return null
        if (Settings.System.getInt(resolver, KEY_COOLING_FAN_ENABLE, 1) != 1) return null

        // 不再按 fan_mode 白名单过滤。部分 HyperOS 场景策略会使用 0/3 等内部模式值，
        // 这里以官方实际输出过的 target_level>0 为准，息屏时只保留这个官方已给出的正档位。
        if (!isScreenOffOrLocked(handler)) return null

        // 保留录音/通话听筒等高优先级停转场景，避免绕过安全/体验限制。
        val micSwitch = Settings.System.getInt(resolver, KEY_SMART_STOP_RECORDING, if (readBooleanField(handler, FIELD_PK_MIC_SWITCH, true)) 1 else 0) == 1
        val recordingActive = readBooleanField(handler, FIELD_PK_RECORDING_ACTIVE, false)
        val simCall = readBooleanField(handler, FIELD_PK_SIM_CALL_ACTIVE, false)
        val earpiece = readBooleanField(handler, FIELD_PK_EARPIECE_ACTIVE, false)
        if (micSwitch && recordingActive) return null
        if (simCall && earpiece) return null

        if (isExtremePartialSceneInactiveForScreenOffKeep(handler, resolver)) {
            logInfo("Keep fan on screen off skipped for extreme partial range: current scene inactive")
            return null
        }

        val currentLevel = readIntField(handler, FIELD_PK_TARGET_LEVEL, 0).takeIf { it > 0 }
        val cachedLevel = synchronized(powerKeeperStateLock) {
            lastPositiveFanLevel.takeIf {
                it > 0 && System.currentTimeMillis() - lastPositiveFanLevelAt <= KEEP_SCREEN_OFF_LEVEL_CACHE_MS
            }
        }
        return currentLevel ?: cachedLevel
    }

    private fun resolveEffectiveCustomFanLevel(
        handler: Any,
        resolver: ContentResolver,
        packageName: String,
        appLevel: Int?
    ): Int? {
        val sceneLevel = if (isCustomSceneFallbackAllowed(handler, resolver)) {
            resolveCustomSceneLevel(handler, resolver, packageName)
        } else {
            null
        }
        return mergeCustomFanLevels(appLevel, sceneLevel)
    }

    private fun isCustomSceneFallbackAllowed(handler: Any, resolver: ContentResolver): Boolean {
        if (Settings.System.getInt(resolver, KEY_COOLING_FAN_ENABLE, 1) != 1) return false

        // Scene fallback is a charging/navigation safety floor, but it must not bypass higher-priority stops.
        val micSwitch = Settings.System.getInt(resolver, KEY_SMART_STOP_RECORDING, if (readBooleanField(handler, FIELD_PK_MIC_SWITCH, true)) 1 else 0) == 1
        val recordingActive = readBooleanField(handler, FIELD_PK_RECORDING_ACTIVE, false)
        val simCall = readBooleanField(handler, FIELD_PK_SIM_CALL_ACTIVE, false)
        val earpiece = readBooleanField(handler, FIELD_PK_EARPIECE_ACTIVE, false)
        if (micSwitch && recordingActive) return false
        if (simCall && earpiece) return false
        return true
    }

    private fun mergeCustomFanLevels(appLevel: Int?, sceneLevel: Int?): Int? {
        val normalizedAppLevel = appLevel?.takeIf { it != PER_APP_LEVEL_SMART }
        return when {
            normalizedAppLevel != null && sceneLevel != null -> maxOf(normalizedAppLevel, sceneLevel)
            normalizedAppLevel != null -> normalizedAppLevel
            sceneLevel != null -> sceneLevel
            else -> appLevel
        }
    }

    private fun resolveCustomSceneLevel(handler: Any, resolver: ContentResolver, packageName: String): Int? {
        val sceneLevel = MODE_HIGH_VALUE.toInt()
        val chargeSwitch = Settings.System.getInt(resolver, KEY_SCENE_RAPID_CHARGE, if (readBooleanField(handler, FIELD_PK_CHARGE_SWITCH, false)) 1 else 0) == 1
        val navigationSwitch = Settings.System.getInt(resolver, KEY_SCENE_NAVIGATION, if (readBooleanField(handler, FIELD_PK_NAVIGATION_SWITCH, false)) 1 else 0) == 1
        val fastChargeActive = readBooleanField(handler, FIELD_PK_FAST_CHARGE_ACTIVE, false)
        val navigationScene = readBooleanField(handler, FIELD_PK_NAVIGATION_SCENE, false) || containsKeyInMapField(handler, FIELD_PK_NAVIGATION_APPS, packageName)
        return when {
            chargeSwitch && fastChargeActive -> {
                logInfo("Custom fan rapid-charge scene fallback matched: pkg=$packageName, level=$sceneLevel")
                sceneLevel
            }
            navigationSwitch && navigationScene -> {
                logInfo("Custom fan navigation scene fallback matched: pkg=$packageName, level=$sceneLevel")
                sceneLevel
            }
            else -> null
        }
    }

    private fun resolvePerAppFanLevel(handler: Any?): Int? {
        if (handler == null) return null
        val resolver = readPowerKeeperResolver(handler) ?: return null
        if (!isCustomFanModeSelected(resolver)) return null
        if (Settings.System.getInt(resolver, KEY_COOLING_FAN_ENABLE, 1) != 1) return null

        // 分应用规则不绕过现有高优先级停转场景：录音智能停转、SIM 通话听筒。
        val micSwitch = Settings.System.getInt(resolver, KEY_SMART_STOP_RECORDING, if (readBooleanField(handler, FIELD_PK_MIC_SWITCH, true)) 1 else 0) == 1
        val recordingActive = readBooleanField(handler, FIELD_PK_RECORDING_ACTIVE, false)
        val simCall = readBooleanField(handler, FIELD_PK_SIM_CALL_ACTIVE, false)
        val earpiece = readBooleanField(handler, FIELD_PK_EARPIECE_ACTIVE, false)
        if (micSwitch && recordingActive) return null
        if (simCall && earpiece) return null

        val packageName = handler.readFieldOrNull("x")?.toString()?.takeIf { it.isNotBlank() } ?: return null

        // “息屏时保持风扇开启”只控制普通非充电息屏；快充/充电息屏保持官方允许风扇开启的语义。
        val fastChargeActive = readBooleanField(handler, FIELD_PK_FAST_CHARGE_ACTIVE, false)
        if (isScreenOffOrLocked(handler) && !isKeepFanOnScreenOffEnabled(resolver) && !fastChargeActive) return null

        // 自定义/分应用固定档位不绕过官方“使用场景”里的快充和导航开关：对应 active 但开关关闭时放行官方策略。
        val chargeSwitch = Settings.System.getInt(resolver, KEY_SCENE_RAPID_CHARGE, if (readBooleanField(handler, FIELD_PK_CHARGE_SWITCH, false)) 1 else 0) == 1
        val navigationSwitch = Settings.System.getInt(resolver, KEY_SCENE_NAVIGATION, if (readBooleanField(handler, FIELD_PK_NAVIGATION_SWITCH, false)) 1 else 0) == 1
        val navigationScene = readBooleanField(handler, FIELD_PK_NAVIGATION_SCENE, false) || containsKeyInMapField(handler, FIELD_PK_NAVIGATION_APPS, packageName)
        if (fastChargeActive && !chargeSwitch) {
            logInfo("Custom per-app fan scene blocked by rapid-charge switch: pkg=$packageName")
            return null
        }
        if (navigationScene && !navigationSwitch) {
            logInfo("Custom per-app fan scene blocked by navigation switch: pkg=$packageName")
            return null
        }

        val rules = readPerAppFanRules(resolver)
        return rules[packageName]
    }

    private fun readPerAppFanRules(resolver: ContentResolver): Map<String, Int> {
        val raw = try {
            Settings.System.getString(resolver, KEY_PER_APP_FAN_RULES)
        } catch (_: Throwable) {
            null
        } ?: return emptyMap()

        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        raw.split(';', '\n', ',').forEach { item ->
            val rule = item.trim()
            if (rule.isEmpty()) return@forEach
            val separator = rule.indexOf('=')
            if (separator <= 0 || separator >= rule.length - 1) return@forEach
            val packageName = rule.substring(0, separator).trim()
            val level = rule.substring(separator + 1).trim().toIntOrNull() ?: return@forEach
            if (packageName.isNotEmpty() && level in PER_APP_SUPPORTED_LEVELS) {
                result[packageName] = level
            }
        }
        return result
    }

    private fun isScreenOffOrLocked(handler: Any): Boolean {
        if (readBooleanField(handler, FIELD_PK_SCREEN_LOCKED, false)) return true
        return try {
            val powerManager = handler.readFieldOrNull("h0")
            val interactive = powerManager?.javaClass?.getMethod("isInteractive")?.invoke(powerManager) as? Boolean
            interactive == false
        } catch (_: Throwable) {
            false
        }
    }

    private fun isKeepFanOnScreenOffEnabled(resolver: ContentResolver?): Boolean {
        if (resolver == null) return false
        return try {
            Settings.System.getInt(resolver, KEY_KEEP_FAN_ON_SCREEN_OFF, 0) == 1
        } catch (_: Throwable) {
            false
        }
    }

    private fun isExtremePartialSceneInactiveForScreenOffKeep(handler: Any, resolver: ContentResolver): Boolean {
        return try {
            val mode = Settings.System.getInt(resolver, KEY_FAN_MODE, -1)
            if (mode != MODE_EXTREME_VALUE.toInt()) return false
            val range = Settings.System.getInt(resolver, KEY_FAN_MODE_RANGE, readIntField(handler, FIELD_PK_FAN_MODE_RANGE, 0))
            if (range != 0) return false
            !shouldForceExtremeLevel(handler)
        } catch (_: Throwable) {
            false
        }
    }

    private fun enterCustomFanMode(resolver: ContentResolver, source: String) {
        try {
            val wasCustom = Settings.System.getInt(resolver, KEY_CUSTOM_FAN_MODE_ENABLED, 0) == 1
            val previousStored = Settings.System.getInt(resolver, KEY_PREVIOUS_FAN_MODE_RANGE, PREVIOUS_FAN_MODE_RANGE_NONE)
            if (!wasCustom || previousStored == PREVIOUS_FAN_MODE_RANGE_NONE) {
                val currentRange = Settings.System.getInt(resolver, KEY_FAN_MODE_RANGE, 0)
                Settings.System.putInt(resolver, KEY_PREVIOUS_FAN_MODE_RANGE, currentRange)
                logInfo("$source enter custom mode: save previous fan_mode_range=$currentRange")
            }
            Settings.System.putInt(resolver, KEY_CUSTOM_FAN_MODE_ENABLED, 1)
            // 方案 B：UI 显示“自定义”，但底层 fan_mode 保持官方智能调频 -1。
            Settings.System.putInt(resolver, KEY_FAN_MODE, MODE_SMART_VALUE.toInt())
            // 自定义模式内部临时使用“部分场景”，让官方 UsageScenes/场景开关立即创建并显示；UI 再隐藏使用范围本身。
            Settings.System.putInt(resolver, KEY_FAN_MODE_RANGE, 0)
            logInfo("$source enter custom mode: fan_mode=-1, custom enabled=1, temporary fan_mode_range=0")
        } catch (t: Throwable) {
            logError("$source enter custom mode failed", t)
        }
    }

    private fun exitCustomFanMode(resolver: ContentResolver, source: String) {
        try {
            val wasCustom = Settings.System.getInt(resolver, KEY_CUSTOM_FAN_MODE_ENABLED, 0) == 1
            if (wasCustom) {
                val previousRange = Settings.System.getInt(resolver, KEY_PREVIOUS_FAN_MODE_RANGE, PREVIOUS_FAN_MODE_RANGE_NONE)
                if (previousRange != PREVIOUS_FAN_MODE_RANGE_NONE) {
                    Settings.System.putInt(resolver, KEY_FAN_MODE_RANGE, previousRange)
                    Settings.System.putInt(resolver, KEY_PREVIOUS_FAN_MODE_RANGE, PREVIOUS_FAN_MODE_RANGE_NONE)
                    logInfo("$source exit custom mode: restore previous fan_mode_range=$previousRange")
                }
            }
            Settings.System.putInt(resolver, KEY_CUSTOM_FAN_MODE_ENABLED, 0)
            logInfo("$source exit custom mode: custom enabled=0")
        } catch (t: Throwable) {
            logError("$source exit custom mode failed", t)
        }
    }

    private fun isCustomFanModeSelected(resolver: ContentResolver?): Boolean {
        if (resolver == null) return false
        return try {
            if (Settings.System.getInt(resolver, KEY_CUSTOM_FAN_MODE_ENABLED, 0) == 1) return true
            // 兼容旧版真实 fan_mode=5：首次读到后迁移到方案 B。
            if (Settings.System.getInt(resolver, KEY_FAN_MODE, -1) == MODE_CUSTOM_VALUE.toInt()) {
                enterCustomFanMode(resolver, "legacy migration")
                logInfo("Migrated legacy custom fan_mode=5 to scheme B: fan_mode=-1, custom enabled=1")
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun shouldForceExtremeLevel(handler: Any?): Boolean {
        if (handler == null) return false
        val resolver = readPowerKeeperResolver(handler) ?: return false
        if (Settings.System.getInt(resolver, KEY_FAN_MODE, -1) != 4) return false
        if (Settings.System.getInt(resolver, KEY_COOLING_FAN_ENABLE, 1) != 1) return false

        val range = Settings.System.getInt(resolver, KEY_FAN_MODE_RANGE, readIntField(handler, FIELD_PK_FAN_MODE_RANGE, 0))
        val gameSwitch = Settings.System.getInt(resolver, KEY_SCENE_GAMING, if (readBooleanField(handler, FIELD_PK_GAME_SWITCH, true)) 1 else 0) == 1
        val chargeSwitch = Settings.System.getInt(resolver, KEY_SCENE_RAPID_CHARGE, if (readBooleanField(handler, FIELD_PK_CHARGE_SWITCH, false)) 1 else 0) == 1
        val navigationSwitch = Settings.System.getInt(resolver, KEY_SCENE_NAVIGATION, if (readBooleanField(handler, FIELD_PK_NAVIGATION_SWITCH, false)) 1 else 0) == 1
        val micSwitch = Settings.System.getInt(resolver, KEY_SMART_STOP_RECORDING, if (readBooleanField(handler, FIELD_PK_MIC_SWITCH, true)) 1 else 0) == 1

        // 和官方逻辑一致：通话/录音/锁屏等高优先级场景应允许风扇停止。
        val screenLocked = readBooleanField(handler, FIELD_PK_SCREEN_LOCKED, false)
        val fastChargeActive = readBooleanField(handler, FIELD_PK_FAST_CHARGE_ACTIVE, false)
        val keepOnScreenOff = isKeepFanOnScreenOffEnabled(resolver)
        val simCall = readBooleanField(handler, FIELD_PK_SIM_CALL_ACTIVE, false)
        val earpiece = readBooleanField(handler, FIELD_PK_EARPIECE_ACTIVE, false)
        if (screenLocked && !fastChargeActive && !keepOnScreenOff) return false
        if (simCall && earpiece) return false

        // 使用范围为全场景/其它场景时，官方会走 other 场景；这里允许狂暴模式生效。
        if (range == 1) return true

        val thermal = handler.readFieldOrNull("w")?.toString()
        val foregroundPackage = handler.readFieldOrNull("x")?.toString()
        val gameLikeScene = containsInSetField(handler, "k0", thermal) || containsInSetField(handler, "l0", thermal)
        // Align extreme mode with official modes: entering a navigation app alone is not enough.
        // Also guard against stale navigation-scene state after returning home.
        val navigationScene = readBooleanField(handler, FIELD_PK_NAVIGATION_SCENE, false) &&
            containsKeyInMapField(handler, FIELD_PK_NAVIGATION_APPS, foregroundPackage)
        val recordingActive = readBooleanField(handler, FIELD_PK_RECORDING_ACTIVE, false)
        // Navigation apps may hold the recording flag while real navigation is active; do not let that
        // transient audio state cancel the navigation scene itself.
        if (micSwitch && recordingActive && !navigationScene) return false

        return (gameSwitch && gameLikeScene) ||
            (chargeSwitch && fastChargeActive) ||
            (navigationSwitch && navigationScene)
    }

    private fun writeTargetLevelByPowerKeeper(
        handler: Any?,
        writePathMethod: Method,
        level: Int,
        reason: String = "Extreme fan policy applied"
    ) {
        if (handler == null) return
        try {
            val ok = writePathMethod.invoke(handler, TARGET_LEVEL, level.toString())
            writeIntFieldIfExists(handler, FIELD_PK_TARGET_LEVEL, level)
            logInfo("$reason by PowerKeeper J(target_level, $level), result=$ok")
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
        try {
            javaClass.findCachedField(fieldName)?.set(this, value)
        } catch (t: Throwable) {
            logReflectWriteFailure(javaClass, fieldName, "writeField", t)
        }
    }

    private fun writeIntFieldIfExists(target: Any, fieldName: String, value: Int) {
        try {
            target.javaClass.findCachedField(fieldName)?.setInt(target, value)
        } catch (t: Throwable) {
            logReflectWriteFailure(target.javaClass, fieldName, "writeIntField", t)
        }
    }

    private fun logReflectWriteFailure(owner: Class<*>, fieldName: String, operation: String, throwable: Throwable) {
        val key = "${owner.name}#$fieldName#$operation#${throwable.javaClass.name}"
        if (reflectWriteFailureLogged.add(key)) {
            logError("$operation failed for ${owner.name}.$fieldName", throwable)
        }
    }

    private fun findPreferenceFromScreen(screen: Any?, key: String): Any? {
        if (screen == null) return null
        return try {
            val method = screen.javaClass.findPublicOrDeclaredMethod("findPreference", CharSequence::class.java)
            method?.invoke(screen, key)
        } catch (t: Throwable) {
            logError("findPreference($key) failed", t)
            null
        }
    }

    private fun ensureKeepFanOnScreenOffSwitch(classLoader: ClassLoader, screen: Any?) {
        if (screen == null) return
        if (findPreferenceFromScreen(screen, KEY_KEEP_FAN_ON_SCREEN_OFF) != null) return

        try {
            val context = screen.javaClass.findPublicOrDeclaredMethod("getContext")?.invoke(screen) ?: return
            val resolver = context.javaClass.getMethod("getContentResolver").invoke(context) as? ContentResolver ?: return
            val switchClass = loadKeepFanSwitchPreferenceClass(classLoader) ?: return
            val preference = createPreferenceInstance(switchClass, context) ?: return

            callPreferenceVoid(preference, "setKey", arrayOf(String::class.java), KEY_KEEP_FAN_ON_SCREEN_OFF)
            callPreferenceVoid(preference, "setTitle", arrayOf(CharSequence::class.java), KEEP_FAN_ON_SCREEN_OFF_TITLE)
            callPreferenceVoid(preference, "setSummary", arrayOf(CharSequence::class.java), KEEP_FAN_ON_SCREEN_OFF_SUMMARY)
            callPreferenceVoid(preference, "setPersistent", arrayOf(Boolean::class.javaPrimitiveType!!), false)
            // 部分系统同款 SwitchPreference 的 setDependency 反射签名不稳定；
            // 可见性已由 CoolingFanEnableController.onPreferenceChange + setVisible 接管，避免无意义错误日志。
            setPreferenceVisible(preference, isCoolingFanEnabled(resolver))
            callPreferenceVoid(preference, "setChecked", arrayOf(Boolean::class.javaPrimitiveType!!), isKeepFanOnScreenOffEnabled(resolver))
            installKeepFanOnScreenOffChangeListener(classLoader, preference, resolver)

            val parent = findPreferenceFromScreen(screen, KEY_FAN_OTHER_FEATURES_CATEGORY) ?: screen
            var added = callPreferenceBoolean(parent, "addPreference", preference)
            if (!added && parent !== screen) {
                added = callPreferenceBoolean(screen, "addPreference", preference)
            }
            logInfo("Keep fan on screen off switch ensured, added=$added, class=${switchClass.name}")
        } catch (t: Throwable) {
            logError("Failed to ensure keep fan on screen off switch", t)
        }
    }


    private fun ensurePerAppFanConfigEntry(classLoader: ClassLoader, screen: Any?) {
        if (screen == null) return
        findPreferenceFromScreen(screen, KEY_PER_APP_FAN_CONFIG_ENTRY)?.let { existing ->
            perAppFanConfigEntryRef = WeakReference(existing)
            updatePerAppFanConfigEntryVisibility(screen)
            return
        }

        try {
            val context = screen.javaClass.findPublicOrDeclaredMethod("getContext")?.invoke(screen) ?: return
            val preferenceClass = loadBasicPreferenceClass(classLoader) ?: return
            val preference = createPreferenceInstance(preferenceClass, context) ?: return

            callPreferenceVoid(preference, "setKey", arrayOf(String::class.java), KEY_PER_APP_FAN_CONFIG_ENTRY)
            callPreferenceVoid(preference, "setTitle", arrayOf(CharSequence::class.java), PER_APP_FAN_CONFIG_TITLE)
            callPreferenceVoid(preference, "setSummary", arrayOf(CharSequence::class.java), PER_APP_FAN_CONFIG_SUMMARY)
            callPreferenceVoid(preference, "setPersistent", arrayOf(Boolean::class.javaPrimitiveType!!), false)
            val visible = isCustomFanModeSelectedFromContext(context)
            setPreferenceVisible(preference, visible)
            perAppFanConfigEntryRef = WeakReference(preference)
            logInfo("Per-app fan config entry ensured visibility: visible=$visible")
            installPerAppFanConfigClickListener(classLoader, preference, context)

            val parent = findPreferenceFromScreen(screen, KEY_FAN_OTHER_FEATURES_CATEGORY) ?: screen
            var added = callPreferenceBoolean(parent, "addPreference", preference)
            if (!added && parent !== screen) {
                added = callPreferenceBoolean(screen, "addPreference", preference)
            }
            logInfo("Per-app fan config entry ensured, added=$added, class=${preferenceClass.name}")
        } catch (t: Throwable) {
            logError("Failed to ensure per-app fan config entry", t)
        }
    }

    private fun loadBasicPreferenceClass(classLoader: ClassLoader): Class<*>? {
        val names = arrayOf(
            "com.android.settingslib.miuisettings.preference.Preference",
            "miuix.preference.Preference",
            "androidx.preference.Preference"
        )
        for (name in names) {
            try {
                return classLoader.loadClass(name)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun installPerAppFanConfigClickListener(classLoader: ClassLoader, preference: Any, context: Any) {
        val listenerClass = classLoader.loadClass("androidx.preference.Preference" + "$" + "OnPreferenceClickListener")
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            classLoader,
            arrayOf(listenerClass)
        ) { _, method, args ->
            when (method.name) {
                "onPreferenceClick" -> {
                    openPerAppFanConfigPageInSettings(context)
                    true
                }
                "toString" -> "MifanPerAppFanConfigClickListener"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === args?.getOrNull(0)
                else -> null
            }
        }
        callPreferenceVoid(preference, "setOnPreferenceClickListener", arrayOf(listenerClass), listener)
    }

    private fun openPerAppFanConfigPageInSettings(contextAny: Any) {
        try {
            val context = contextAny as? android.content.Context ?: return
            val intent = Intent().apply {
                setClassName(MISETTINGS_PACKAGE, MISETTINGS_HIGH_REFRESH_OPTIONS_ACTIVITY)
                putExtra(EXTRA_MIFAN_FAN_CONFIG_PAGE, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            logInfo("Open per-app fan config page in MISettings host")
        } catch (t: Throwable) {
            logError("Open MISettings per-app fan config page failed; fallback to Settings overlay/dialog", t)
            try {
                val context = contextAny as? android.content.Context ?: return
                val activity = findActivityFromContext(context) ?: throw IllegalStateException("No Settings Activity from context=${context.javaClass.name}")
                showPerAppFanConfigPageInActivity(activity)
            } catch (inner: Throwable) {
                logError("Fallback Settings overlay failed; fallback to dialog", inner)
                showPerAppFanConfigDialog(contextAny)
            }
        }
    }

    private fun installMiSettingsFanConfigHostHooks(classLoader: ClassLoader) {
        val activityClass = classLoader.loadClass(MISETTINGS_HIGH_REFRESH_OPTIONS_ACTIVITY)
        val fragmentClass = classLoader.loadClass(MISETTINGS_HIGH_REFRESH_OPTIONS_FRAGMENT)

        runCatching {
            val searchFragmentClass = classLoader.loadClass(MISETTINGS_APP_SEARCH_FRAGMENT)
            val followHolderClass = classLoader.loadClass(MISETTINGS_FOLLOW_VIEW_HOLDER)
            logMiSettingsProbe(classLoader, activityClass, fragmentClass, searchFragmentClass, followHolderClass)
        }.onFailure { logError("MISettings fan config probe skipped", it) }

        val activityOnCreate = activityClass.getMethod("onCreate", android.os.Bundle::class.java)
        hook(activityOnCreate).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            val activity = chain.thisObject as? android.app.Activity
            if (isMifanFanConfigHostActivity(activity)) {
                activity?.title = PER_APP_FAN_CONFIG_TITLE
                activity?.let { syncMiSettingsFanConfigSearchSource(it, classLoader) }
                logInfo("MISettings high refresh host opened for fan config")
            }
            result
        })

        val fragmentOnViewCreated = fragmentClass.getMethod("onViewCreated", android.view.View::class.java, android.os.Bundle::class.java)
        hook(fragmentOnViewCreated).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            val fragment = chain.thisObject
            if (isMifanFanConfigFragment(fragment)) {
                rebuildMiSettingsFanConfigList(fragment)
                logInfo("MISettings high refresh fragment list replaced for fan config")
            }
            result
        })

        runCatching {
            val fragmentOnStart = fragmentClass.getMethod("onStart")
            hook(fragmentOnStart).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                val fragment = chain.thisObject
                if (isMifanFanConfigFragment(fragment)) {
                    rebuildMiSettingsFanConfigList(fragment)
                }
                result
            })
        }.onFailure { logError("Hook MISettings fan fragment onStart failed", it) }

        runCatching {
            val rebuild = fragmentClass.getMethod("y")
            hook(rebuild).intercept(XposedInterface.Hooker { chain ->
                val fragment = chain.thisObject
                if (isMifanFanConfigFragment(fragment)) {
                    rebuildMiSettingsFanConfigList(fragment)
                    null
                } else {
                    chain.proceed()
                }
            })
        }.onFailure { logError("Hook MISettings fan fragment y failed", it) }

        runCatching {
            val searchFragmentClass = classLoader.loadClass(MISETTINGS_APP_SEARCH_FRAGMENT)

            runCatching {
                val searchOnViewCreated = searchFragmentClass.getMethod("onViewCreated", android.view.View::class.java, android.os.Bundle::class.java)
                hook(searchOnViewCreated).intercept(XposedInterface.Hooker { chain ->
                    val result = chain.proceed()
                    if (syncMiSettingsFanConfigSearchFragment(chain.thisObject, classLoader)) {
                        val query = chain.thisObject.readFieldOrNull("i")?.toString().orEmpty()
                        if (query.isNotBlank()) filterMiSettingsFanConfigSearch(chain.thisObject, query)
                    }
                    result
                })
            }.onFailure { logError("Hook MISettings AppSearchFragment onViewCreated failed", it) }

            runCatching {
                val searchMethod = searchFragmentClass.getMethod("y", String::class.java)
                hook(searchMethod).intercept(XposedInterface.Hooker { chain ->
                    val fragment = chain.thisObject
                    if (syncMiSettingsFanConfigSearchFragment(fragment, classLoader)) {
                        filterMiSettingsFanConfigSearch(fragment, chain.args.getOrNull(0)?.toString().orEmpty())
                        null
                    } else {
                        chain.proceed()
                    }
                })
            }.onFailure { logError("Hook MISettings AppSearchFragment search failed", it) }
        }.onFailure { logError("Hook MISettings AppSearchFragment failed", it) }

        runCatching {
            val followHolderClass = classLoader.loadClass(MISETTINGS_FOLLOW_VIEW_HOLDER)
            val bindFollow = followHolderClass.getMethod("a", classLoader.loadClass("androidx.recyclerview.widget.p0"), Any::class.java, Int::class.javaPrimitiveType)
            hook(bindFollow).intercept(XposedInterface.Hooker { chain ->
                val result = chain.proceed()
                val holder = chain.thisObject
                val item = chain.args[1]
                updateMiSettingsFanFollowRow(holder, item)
                result
            })
        }.onFailure { logError("Hook MISettings fan follow row failed", it) }
    }

    private fun isMifanFanConfigFragment(fragment: Any?): Boolean {
        return try {
            val activity = fragment?.javaClass?.findPublicOrDeclaredMethod("getActivity")?.invoke(fragment) as? android.app.Activity
                ?: fragment?.javaClass?.findPublicOrDeclaredMethod("n")?.invoke(fragment) as? android.app.Activity
            isMifanFanConfigHostActivity(activity)
        } catch (_: Throwable) {
            false
        }
    }

    private fun rebuildMiSettingsFanConfigList(fragment: Any) {
        try {
            val activity = getMiSettingsSearchHostActivity(fragment) ?: return
            val list = buildMiSettingsFanConfigItems(activity, fragment.javaClass.classLoader ?: activity.classLoader)
            fragment.writeFieldIfExists("l", list)
            fragment.writeFieldIfExists("j", list)
            activity.writeFieldIfExists("d", list)
            syncMiSettingsFanConfigSearchSource(activity, fragment.javaClass.classLoader ?: activity.classLoader, list)
            val adapter = fragment.readFieldOrNull("k")
            val loading = fragment.readFieldOrNull("f") as? android.view.View
            if (adapter != null) {
                adapter.writeFieldIfExists("j", list)
                val notify = adapter.javaClass.findPublicOrDeclaredMethod("notifyDataSetChanged")
                val hostView = loading ?: activity.window?.decorView
                hostView?.post {
                    runCatching { notify?.invoke(adapter) }
                        .onFailure { logError("Notify MISettings fan config list failed", it) }
                } ?: notify?.invoke(adapter)
            }
            loading?.visibility = android.view.View.GONE
        } catch (t: Throwable) {
            logError("Rebuild MISettings fan config list failed", t)
        }
    }

    private fun buildMiSettingsFanConfigItems(activity: android.app.Activity, classLoader: ClassLoader): java.util.ArrayList<Any> {
        val itemClass = classLoader.loadClass(MISETTINGS_APP_ITEM_MODEL)
        val titleCtor = itemClass.getConstructor(String::class.java)
        val appCtor = itemClass.getConstructor(android.content.Context::class.java, String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val rules = readPerAppFanRules(activity.contentResolver)
        val pm = activity.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
                Pair(label, pkg)
            }
            .distinctBy { it.second }
            .sortedWith(compareBy<Pair<String, String>> { it.first.lowercase() }.thenBy { it.second })
        val result = java.util.ArrayList<Any>()
        result.add(titleCtor.newInstance("已配置应用"))
        apps.filter { rules.containsKey(it.second) }.forEach { (_, pkg) -> result.add(appCtor.newInstance(activity, pkg, false, 3)) }
        result.add(titleCtor.newInstance("智能调频（默认）"))
        apps.filterNot { rules.containsKey(it.second) }.forEach { (_, pkg) -> result.add(appCtor.newInstance(activity, pkg, false, 3)) }
        return result
    }

    private fun updateMiSettingsFanFollowRow(holder: Any, item: Any?) {
        try {
            val adapter = holder.readFieldOrNull("e")
            val activity = adapter?.readFieldOrNull("i") as? android.app.Activity ?: return
            if (!isMifanFanConfigHostActivity(activity)) return
            val packageName = item?.readFieldOrNull("a")?.toString()?.takeIf { it.isNotBlank() } ?: return
            val rules = readPerAppFanRules(activity.contentResolver)
            val level = rules[packageName] ?: PER_APP_LEVEL_SMART
            val summary = holder.readFieldOrNull("c") as? android.widget.TextView
            summary?.text = fanLevelTitle(level).replace("（默认）", "")
            summary?.setTextColor(if (level == PER_APP_LEVEL_SMART) 0xff8a8a8a.toInt() else 0xffff6900.toInt())
            summary?.setCompoundDrawablesWithIntrinsicBounds(0, 0, getMiuixArrowUpDownDrawableId(activity), 0)
            summary?.compoundDrawablePadding = dp(activity, 6)
            val row = findViewHolderItemView(holder)
            row?.isClickable = true
            row?.setOnClickListener {
                val label = (holder.readFieldOrNull("b") as? android.widget.TextView)?.text?.toString()?.takeIf { it.isNotBlank() } ?: packageName
                showMiSettingsFanModeDialog(activity, label, packageName, adapter, row)
            }
        } catch (t: Throwable) {
            logError("Update MISettings fan follow row failed", t)
        }
    }

    private fun showMiSettingsFanModeDialog(activity: android.app.Activity, label: String, pkg: String, adapter: Any?, anchor: android.view.View?) {
        showFanModeDropDownPreferenceChooser(activity, label, pkg, anchor) {
            try {
                val list = buildMiSettingsFanConfigItems(activity, activity.classLoader)
                activity.writeFieldIfExists("d", list)
                adapter?.writeFieldIfExists("j", list)
                adapter?.javaClass?.findPublicOrDeclaredMethod("notifyDataSetChanged")?.invoke(adapter)
                syncMiSettingsFanConfigSearchSource(activity, activity.classLoader, list)
                logInfo("MISettings fan config list refreshed after native popup selection: size=${list.size}")
            } catch (t: Throwable) {
                logError("Refresh MISettings fan config list after selection failed", t)
                adapter?.javaClass?.findPublicOrDeclaredMethod("notifyDataSetChanged")?.invoke(adapter)
            }
        }
    }

    private fun showFanModeDropDownPreferenceChooser(activity: android.app.Activity, label: String, pkg: String, anchor: android.view.View?, onDone: () -> Unit) {
        try {
            val rules = LinkedHashMap(readPerAppFanRules(activity.contentResolver))
            val levels = intArrayOf(PER_APP_LEVEL_SMART, 2, 1, 4, PER_APP_LEVEL_OFF)
            val current = rules[pkg] ?: PER_APP_LEVEL_SMART
            val dropDownClass = activity.classLoader.loadClass("miuix.preference.DropDownPreference")
            val preference = dropDownClass.getConstructor(android.content.Context::class.java).newInstance(activity)
            val entries = levels.map { fanLevelTitle(it).replace("（默认）", "") }.toTypedArray<CharSequence>()
            val values = levels.map { it.toString() }.toTypedArray<CharSequence>()
            val summaries = levels.map { fanLevelSummary(it) }.toTypedArray<CharSequence>()
            preference.javaClass.findPublicOrDeclaredMethod("setTitle", CharSequence::class.java)?.invoke(preference, label)
            callVoid(preference, "setEntries", arrayOf(CharSequence::class.java.arrayType()), entries)
            callVoid(preference, "setEntryValues", arrayOf(CharSequence::class.java.arrayType()), values)
            callVoid(preference, "setSummaries", arrayOf(CharSequence::class.java.arrayType()), summaries)
            // On this MISettings build the public setters may be absent/no-op on the obfuscated
            // DropDownPreference path. Fill the real internal MIUI adapter/fields directly so
            // miuix Spinner can measure non-empty dropdown rows instead of a 25px x 0 popup.
            runCatching {
                preference.writeFieldIfExists("f", entries)
                preference.writeFieldIfExists("g", values)
                val baseAdapter = preference.readFieldOrNull("b")
                baseAdapter?.writeFieldIfExists("a", entries)
                baseAdapter?.writeFieldIfExists("b", summaries)
                baseAdapter?.writeFieldIfExists("g", values)
                baseAdapter?.javaClass?.findPublicOrDeclaredMethod("notifyDataSetChanged")?.invoke(baseAdapter)
                val count = baseAdapter?.javaClass?.findPublicOrDeclaredMethod("getCount")?.invoke(baseAdapter)
                logInfo("Prepared native MIUI DropDownPreference adapter: class=${baseAdapter?.javaClass?.name}, count=$count")
            }.onFailure { logError("Prepare native MIUI DropDownPreference adapter failed", it) }
            runCatching { preference.javaClass.findPublicOrDeclaredMethod("h", String::class.java)?.invoke(preference, current.toString()) }

            var selectionApplied = false
            var popupHost: android.view.View? = null
            var popupDecor: android.view.ViewGroup? = null
            fun cleanupNativeDropDownHost() {
                runCatching { preference.readFieldOrNull("e")?.javaClass?.findPublicOrDeclaredMethod("dismissPopup")?.invoke(preference.readFieldOrNull("e")) }
                runCatching {
                    val hostView = popupHost
                    val decorView = popupDecor
                    if (hostView != null && hostView.parent === decorView) decorView.removeView(hostView)
                }
            }
            fun applySelectedValue(valueText: String?, reason: String) {
                if (selectionApplied) return
                val level = valueText?.toIntOrNull() ?: return
                if (level !in levels) return
                selectionApplied = true
                if (level == PER_APP_LEVEL_SMART) rules.remove(pkg) else rules[pkg] = level
                Settings.System.putString(activity.contentResolver, KEY_PER_APP_FAN_RULES, serializePerAppFanRules(rules))
                logInfo("Per-app fan rule updated from native MIUI DropDownPreference popup: $pkg=${if (level == PER_APP_LEVEL_SMART) "smart/default" else level}, value=$valueText, reason=$reason")
                cleanupNativeDropDownHost()
                onDone()
            }
            runCatching {
                // MISettings bundles AndroidX Preference with obfuscated listener names.
                // In this build Preference.setOnPreferenceChangeListener takes androidx.preference.m,
                // not the public nested Preference.OnPreferenceChangeListener name.
                val changeListenerClass = runCatching {
                    activity.classLoader.loadClass("androidx.preference.m")
                }.getOrElse {
                    activity.classLoader.loadClass("androidx.preference.Preference" + "$" + "OnPreferenceChangeListener")
                }
                val proxy = java.lang.reflect.Proxy.newProxyInstance(activity.classLoader, arrayOf(changeListenerClass)) { _, method, args ->
                    if (method.name == "onPreferenceChange") {
                        val newValue = args?.getOrNull(1)?.toString()
                        if (newValue != current.toString()) applySelectedValue(newValue, "Preference.OnPreferenceChangeListener/${changeListenerClass.name}")
                        true
                    } else null
                }
                preference.javaClass.findPublicOrDeclaredMethod("setOnPreferenceChangeListener", changeListenerClass)?.invoke(preference, proxy)
                logInfo("Installed native MIUI DropDownPreference change listener: ${changeListenerClass.name}")
            }.onFailure { logError("Install native MIUI DropDownPreference change listener failed", it) }

            val decor = activity.window?.decorView as? android.view.ViewGroup
            popupDecor = decor
            // MISettings bundles AndroidX Preference with obfuscated class names.
            // Its DropDownPreference.onBindViewHolder signature is onBindViewHolder(Landroidx/preference/l0;)V,
            // not androidx.preference.PreferenceViewHolder (that public class name is absent in this APK).
            val holderClass = activity.classLoader.loadClass("androidx.preference.l0")
            val holderCtor = holderClass.getDeclaredConstructor(android.view.View::class.java).apply { isAccessible = true }
            val host = android.widget.FrameLayout(activity).apply {
                alpha = 0.01f
                isClickable = false
                visibility = android.view.View.VISIBLE
            }
            // Do not inflate miuix_dropdown_preference_flexible_layout here: in HighRefreshOptionsActivityTheme
            // its HyperCellLayout background attr (?preferenceItemBackground) is not resolvable and crashes.
            // Build the minimal real Preference row programmatically, but still use the MIUI DropDownPreference
            // and its internal miuix.appcompat.widget.Spinner popup/adapter implementation.
            val view = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                clipChildren = false
                clipToPadding = false
                val titleView = android.widget.TextView(activity).apply {
                    id = android.R.id.title
                    text = label
                    visibility = android.view.View.GONE
                }
                val summaryView = android.widget.TextView(activity).apply {
                    id = android.R.id.summary
                    visibility = android.view.View.GONE
                }
                val text1View = android.widget.TextView(activity).apply {
                    id = android.R.id.text1
                    visibility = android.view.View.GONE
                }
                val spinnerId = activity.resources.getIdentifier("spinner", "id", MISETTINGS_PACKAGE)
                val spinnerView = activity.classLoader.loadClass("miuix.appcompat.widget.Spinner")
                    .getConstructor(android.content.Context::class.java)
                    .newInstance(activity) as android.view.View
                if (spinnerId != 0) spinnerView.id = spinnerId
                addView(titleView, android.widget.LinearLayout.LayoutParams(1, 1))
                addView(summaryView, android.widget.LinearLayout.LayoutParams(1, 1))
                addView(text1View, android.widget.LinearLayout.LayoutParams(1, 1))
                addView(spinnerView, android.widget.LinearLayout.LayoutParams((anchor?.width ?: dp(activity, 220)).coerceAtLeast(dp(activity, 160)), dp(activity, 48)))
            }
            host.addView(view, android.widget.FrameLayout.LayoutParams((anchor?.width ?: dp(activity, 220)).coerceAtLeast(dp(activity, 160)), dp(activity, 56)))
            popupHost = host
            decor?.addView(host, android.widget.FrameLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.START))
            anchor?.let { a ->
                val loc = IntArray(2)
                a.getLocationOnScreen(loc)
                host.x = loc[0].toFloat()
                host.y = loc[1].toFloat()
            }
            val holder = holderCtor.newInstance(view)
            preference.javaClass.findPublicOrDeclaredMethod("onBindViewHolder", holderClass)?.invoke(preference, holder)
            val currentIndex = levels.indexOf(current).coerceAtLeast(0)
            var ignoreInitial = true
            val listener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    if (ignoreInitial && position == currentIndex) {
                        ignoreInitial = false
                        logInfo("Ignore native MIUI DropDownPreference initial selection: pkg=$pkg, position=$position")
                        return
                    }
                    ignoreInitial = false
                    applySelectedValue(levels.getOrNull(position)?.toString(), "onItemSelected")
                    if (selectionApplied) cleanupNativeDropDownHost()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            val spinnerObj = preference.readFieldOrNull("e")
            val spinnerAdapter = spinnerObj?.javaClass?.findPublicOrDeclaredMethod("getAdapter")?.invoke(spinnerObj)
            val spinnerCount = spinnerAdapter?.javaClass?.findPublicOrDeclaredMethod("getCount")?.invoke(spinnerAdapter)
            (spinnerObj as? android.widget.AdapterView<*>)?.onItemSelectedListener = listener
            logInfo("Show native MIUI DropDownPreference popup for per-app fan: pkg=$pkg, current=$current, spinner=${spinnerObj?.javaClass?.name}, spinnerCount=$spinnerCount")
            host.post {
                runCatching { preference.javaClass.findPublicOrDeclaredMethod("performClick", android.view.View::class.java)?.invoke(preference, view) }
                    .onFailure { runCatching { spinnerObj?.javaClass?.findPublicOrDeclaredMethod("performClick")?.invoke(spinnerObj) } }
                host.postDelayed({ runCatching { decor?.removeView(host) } }, 15000L)
            }
        } catch (t: Throwable) {
            logError("Show native MIUI DropDownPreference popup failed; fallback to fan-mode style dialog", t)
            showFanModeChoiceDialog(activity, label, pkg, onDone)
        }
    }

    private fun getMiSettingsSearchHostActivity(fragment: Any): android.app.Activity? {
        return try {
            (fragment.readFieldOrNull("e") as? android.app.Activity)
                ?: (fragment.javaClass.findPublicOrDeclaredMethod("getActivity")?.invoke(fragment) as? android.app.Activity)
                ?: (fragment.javaClass.findPublicOrDeclaredMethod("n")?.invoke(fragment) as? android.app.Activity)
        } catch (_: Throwable) {
            null
        }
    }

    private fun syncMiSettingsFanConfigSearchSource(activity: android.app.Activity, classLoader: ClassLoader, list: java.util.ArrayList<Any>? = null) {
        try {
            if (!isMifanFanConfigHostActivity(activity)) return
            val source = list ?: buildMiSettingsFanConfigItems(activity, classLoader)
            activity.writeFieldIfExists("d", source)
            val searchFragment = activity.readFieldOrNull("c") ?: return
            searchFragment.writeFieldIfExists("l", source)
            logInfo("MISettings fan config search source synced: size=${source.size}")
        } catch (t: Throwable) {
            logError("Sync MISettings fan config search source failed", t)
        }
    }

    private fun syncMiSettingsFanConfigSearchFragment(fragment: Any, classLoader: ClassLoader): Boolean {
        return try {
            val activity = getMiSettingsSearchHostActivity(fragment) ?: return false
            if (!isMifanFanConfigHostActivity(activity)) return false
            val source = buildMiSettingsFanConfigItems(activity, classLoader)
            fragment.writeFieldIfExists("l", source)
            activity.writeFieldIfExists("d", source)
            activity.writeFieldIfExists("c", fragment)
            val result = fragment.readFieldOrNull("j") as? java.util.ArrayList<*>
            val adapter = fragment.readFieldOrNull("h")
            adapter?.writeFieldIfExists("j", if (result == null || result.isEmpty()) source else result)
            (fragment.readFieldOrNull("g") as? android.view.View)?.visibility = android.view.View.VISIBLE
            adapter?.javaClass?.findPublicOrDeclaredMethod("notifyDataSetChanged")?.invoke(adapter)
            adapter?.javaClass?.findPublicOrDeclaredMethod("f")?.invoke(adapter)
            logInfo("MISettings AppSearchFragment source replaced for fan config: source=${source.size}, result=${result?.size ?: -1}, adapter=${adapter != null}")
            true
        } catch (t: Throwable) {
            logError("Sync MISettings AppSearchFragment failed", t)
            false
        }
    }

    private fun filterMiSettingsFanConfigSearch(fragment: Any, query: String) {
        try {
            val activity = getMiSettingsSearchHostActivity(fragment) ?: return
            val source = fragment.readFieldOrNull("l") as? java.util.ArrayList<*> ?: return
            val result = fragment.readFieldOrNull("j") as? java.util.ArrayList<*> ?: return
            @Suppress("UNCHECKED_CAST")
            val mutableResult = result as java.util.ArrayList<Any>
            mutableResult.clear()
            val q = query.trim().lowercase()
            source.forEach { item ->
                    val viewType = (item?.readFieldOrNull("d") as? Int) ?: 0
                    if (viewType == 1) return@forEach
                    val pkg = item?.readFieldOrNull("a")?.toString().orEmpty()
                    if (pkg.isBlank()) return@forEach
                    val label = runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                    if (q.isEmpty() || pkg.lowercase().contains(q) || label.lowercase().contains(q)) {
                        @Suppress("UNCHECKED_CAST")
                        mutableResult.add(item as Any)
                    }
                }
            val adapter = fragment.readFieldOrNull("h")
            adapter?.writeFieldIfExists("j", mutableResult)
            (fragment.readFieldOrNull("g") as? android.view.View)?.visibility = android.view.View.VISIBLE
            adapter?.javaClass?.findPublicOrDeclaredMethod("notifyDataSetChanged")?.invoke(adapter)
            adapter?.javaClass?.findPublicOrDeclaredMethod("f")?.invoke(adapter)
            logInfo("MISettings fan config search filtered: query=$query, result=${mutableResult.size}, recyclerVisible=${(fragment.readFieldOrNull("g") as? android.view.View)?.visibility}")
        } catch (t: Throwable) {
            logError("Filter MISettings fan config search failed", t)
        }
    }

    private fun findViewHolderItemView(holder: Any): android.view.View? {
        var c: Class<*>? = holder.javaClass
        while (c != null) {
            runCatching {
                val f = c.getDeclaredField("itemView")
                f.isAccessible = true
                return f.get(holder) as? android.view.View
            }
            c = c.superclass
        }
        return null
    }

    private fun getMiuixArrowUpDownDrawableId(context: android.content.Context): Int {
        return context.resources.getIdentifier("miuix_appcompat_arrow_up_down_integrated", "drawable", MISETTINGS_PACKAGE).takeIf { it != 0 } ?: 0
    }

    private fun showFanModeChoiceDialog(activity: android.app.Activity, label: String, pkg: String, onDone: () -> Unit) {
        val rules = LinkedHashMap(readPerAppFanRules(activity.contentResolver))
        val levels = intArrayOf(PER_APP_LEVEL_SMART, 2, 1, 4, PER_APP_LEVEL_OFF)
        val current = rules[pkg] ?: PER_APP_LEVEL_SMART
        val blue = 0xff3482e8.toInt()
        val primary = resolveThemeColor(activity, android.R.attr.textColorPrimary, android.graphics.Color.BLACK)
        val secondary = resolveThemeColor(activity, android.R.attr.textColorSecondary, 0xff666666.toInt())
        val content = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(activity, 28), dp(activity, 24), dp(activity, 28), dp(activity, 16))
        }
        var dialogRef: android.app.AlertDialog? = null
        levels.forEach { level ->
            val selected = level == current
            val row = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                isClickable = true
                setPadding(0, dp(activity, 10), 0, dp(activity, 10))
            }
            val textBox = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
            textBox.addView(android.widget.TextView(activity).apply {
                text = fanLevelTitle(level).replace("（默认）", "")
                textSize = 20f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (selected) blue else primary)
            }, android.widget.LinearLayout.LayoutParams(-1, -2))
            textBox.addView(android.widget.TextView(activity).apply {
                text = fanLevelSummary(level)
                textSize = 16f
                setTextColor(if (selected) blue else secondary)
                setLineSpacing(0f, 1.05f)
            }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(activity, 2) })
            row.addView(textBox, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(android.widget.TextView(activity).apply {
                text = if (selected) "✓" else ""
                textSize = 30f
                gravity = android.view.Gravity.CENTER
                setTextColor(blue)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, android.widget.LinearLayout.LayoutParams(dp(activity, 42), -1))
            row.setOnClickListener {
                if (level == PER_APP_LEVEL_SMART) rules.remove(pkg) else rules[pkg] = level
                Settings.System.putString(activity.contentResolver, KEY_PER_APP_FAN_RULES, serializePerAppFanRules(rules))
                logInfo("Per-app fan rule updated from fan-mode style chooser: $pkg=${if (level == PER_APP_LEVEL_SMART) "smart/default" else level}")
                dialogRef?.dismiss()
                onDone()
            }
            content.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        dialogRef = android.app.AlertDialog.Builder(activity)
            .setTitle(label)
            .setView(content)
            .create()
        dialogRef.show()
    }

    private fun isMifanFanConfigHostActivity(activity: android.app.Activity?): Boolean {
        return try {
            activity?.intent?.getBooleanExtra(EXTRA_MIFAN_FAN_CONFIG_PAGE, false) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun findActivityFromContext(context: android.content.Context?): android.app.Activity? {
        var current = context
        var depth = 0
        while (current != null && depth < 8) {
            if (current is android.app.Activity) return current
            current = (current as? android.content.ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    private fun showPerAppFanConfigPageInActivity(activity: android.app.Activity) {
        val decor = activity.window.decorView as android.view.ViewGroup
        val oldPage = decor.findViewWithTag<android.view.View>(PER_APP_PAGE_TAG)
        if (oldPage != null) decor.removeView(oldPage)
        val root = android.widget.LinearLayout(activity).apply {
            tag = PER_APP_PAGE_TAG
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(resolveThemeColor(activity, android.R.attr.colorBackground, 0xfff6f6f6.toInt()))
            setPadding(dp(activity, 24), dp(activity, 18), dp(activity, 24), 0)
            isClickable = true
            isFocusable = true
        }
        val header = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(android.widget.TextView(activity).apply {
            text = "‹"
            textSize = 36f
            gravity = android.view.Gravity.CENTER
            setTextColor(resolveThemeColor(activity, android.R.attr.textColorPrimary, android.graphics.Color.BLACK))
            setOnClickListener { decor.removeView(root) }
        }, android.widget.LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 48)))
        header.addView(android.widget.TextView(activity).apply {
            text = PER_APP_FAN_CONFIG_TITLE
            textSize = 28f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setTextColor(resolveThemeColor(activity, android.R.attr.textColorPrimary, android.graphics.Color.BLACK))
        }, android.widget.LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header, android.widget.LinearLayout.LayoutParams(-1, -2))
        val search = android.widget.EditText(activity).apply {
            hint = "搜索"
            setSingleLine(true)
            textSize = 16f
            setPadding(dp(activity, 18), 0, dp(activity, 18), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xffeeeeee.toInt())
                cornerRadius = dp(activity, 18).toFloat()
            }
        }
        root.addView(search, android.widget.LinearLayout.LayoutParams(-1, dp(activity, 46)).apply { topMargin = dp(activity, 14); bottomMargin = dp(activity, 12) })
        root.addView(android.widget.TextView(activity).apply {
            text = PER_APP_FAN_CONFIG_SUMMARY
            textSize = 14f
            setTextColor(resolveThemeColor(activity, android.R.attr.textColorSecondary, 0xff777777.toInt()))
        }, android.widget.LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(activity, 8) })
        val scroll = android.widget.ScrollView(activity)
        val list = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        scroll.addView(list, android.view.ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        fun render(query: String) = renderPerAppFanRows(activity, list, query)
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        render("")
        decor.addView(root, android.view.ViewGroup.LayoutParams(-1, -1))
        root.requestFocus()
    }

    private fun renderPerAppFanRows(activity: android.app.Activity, list: android.widget.LinearLayout, query: String) {
        val q = query.trim().lowercase()
        val rules = readPerAppFanRules(activity.contentResolver)
        val pm = activity.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0).mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            Triple(label, pkg, info.loadIcon(pm))
        }.distinctBy { it.second }.filter { q.isEmpty() || it.first.lowercase().contains(q) || it.second.lowercase().contains(q) }
            .sortedWith(compareBy<Triple<String, String, android.graphics.drawable.Drawable>> { it.first.lowercase() }.thenBy { it.second })
        list.removeAllViews()
        fun section(text: String) {
            list.addView(android.widget.TextView(activity).apply {
                this.text = text
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(resolveThemeColor(activity, android.R.attr.textColorSecondary, 0xff777777.toInt()))
                setPadding(0, dp(activity, 18), 0, dp(activity, 8))
            }, android.widget.LinearLayout.LayoutParams(-1, -2))
        }
        section("已配置应用")
        apps.filter { rules.containsKey(it.second) }.forEach { addPerAppFanRow(activity, list, it.first, it.second, it.third, rules[it.second] ?: PER_APP_LEVEL_SMART) }
        section("智能调频（默认）")
        apps.filterNot { rules.containsKey(it.second) }.forEach { addPerAppFanRow(activity, list, it.first, it.second, it.third, PER_APP_LEVEL_SMART) }
    }

    private fun addPerAppFanRow(activity: android.app.Activity, list: android.widget.LinearLayout, label: String, pkg: String, icon: android.graphics.drawable.Drawable, level: Int) {
        val row = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(activity, 10), 0, dp(activity, 10))
            isClickable = true
            setOnClickListener { showSettingsPageFanModeDialog(activity, label, pkg) { renderPerAppFanRows(activity, list, "") } }
        }
        row.addView(android.widget.ImageView(activity).apply { setImageDrawable(icon) }, android.widget.LinearLayout.LayoutParams(dp(activity, 42), dp(activity, 42)))
        val texts = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        texts.addView(android.widget.TextView(activity).apply { text = label; textSize = 16f; maxLines = 1 })
        texts.addView(android.widget.TextView(activity).apply { text = pkg; textSize = 12f; maxLines = 1; setTextColor(0xff888888.toInt()) })
        row.addView(texts, android.widget.LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(activity, 14); rightMargin = dp(activity, 10) })
        row.addView(android.widget.TextView(activity).apply {
            text = fanLevelTitle(level).replace("（默认）", "")
            textSize = 14f
            setTextColor(if (level == PER_APP_LEVEL_SMART) 0xff888888.toInt() else 0xffff6900.toInt())
        }, android.widget.LinearLayout.LayoutParams(-2, -2))
        list.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2))
    }

    private fun showSettingsPageFanModeDialog(activity: android.app.Activity, label: String, pkg: String, onDone: () -> Unit) {
        showFanModeChoiceDialog(activity, label, pkg, onDone)
    }

    private fun resolveThemeColor(context: android.content.Context, attr: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
        } else fallback
    }

    private fun dp(context: android.content.Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun showPerAppFanConfigDialog(contextAny: Any) {
        val context = contextAny as? android.content.Context ?: return
        try {
            val packageManager = context.packageManager
            val resolver = context.contentResolver
            val rules = readPerAppFanRules(resolver)
            val apps = packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { it.enabled && packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .map { app ->
                    val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(app.packageName)
                    Triple(label, app.packageName, rules[app.packageName] ?: PER_APP_LEVEL_SMART)
                }
                .sortedWith(compareBy<Triple<String, String, Int>> { it.first.lowercase() }.thenBy { it.second })
                .toList()

            if (apps.isEmpty()) {
                android.app.AlertDialog.Builder(context)
                    .setTitle(PER_APP_FAN_CONFIG_TITLE)
                    .setMessage("没有找到可启动的应用")
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return
            }

            val labels = apps.map { (label, pkg, level) ->
                label + 10.toChar() + pkg + " · " + fanLevelTitle(level)
            }.toTypedArray()

            android.app.AlertDialog.Builder(context)
                .setTitle(PER_APP_FAN_CONFIG_TITLE)
                .setItems(labels) { _, which ->
                    val app = apps[which]
                    showPerAppFanModeDialog(context, app.first, app.second)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("清空全部配置") { _, _ ->
                    Settings.System.putString(resolver, KEY_PER_APP_FAN_RULES, "")
                    logInfo("Per-app fan rules cleared from Settings dialog")
                }
                .show()
        } catch (t: Throwable) {
            logError("Show per-app fan config dialog failed", t)
        }
    }

    private fun showPerAppFanModeDialog(context: android.content.Context, appLabel: String, packageName: String) {
        val resolver = context.contentResolver
        val rules = LinkedHashMap(readPerAppFanRules(resolver))
        val currentLevel = rules[packageName] ?: PER_APP_LEVEL_SMART
        val levels = intArrayOf(PER_APP_LEVEL_SMART, 1, 2, 4, PER_APP_LEVEL_OFF)
        val titles = levels.map { fanLevelTitle(it) }.toTypedArray()
        val checked = levels.indexOf(currentLevel).takeIf { it >= 0 } ?: 0

        android.app.AlertDialog.Builder(context)
            .setTitle(appLabel)
            .setSingleChoiceItems(titles, checked) { dialog, which ->
                val level = levels[which]
                if (level == PER_APP_LEVEL_SMART) {
                    rules.remove(packageName)
                } else {
                    rules[packageName] = level
                }
                Settings.System.putString(resolver, KEY_PER_APP_FAN_RULES, serializePerAppFanRules(rules))
                logInfo("Per-app fan rule updated from Settings dialog: $packageName=${if (level == PER_APP_LEVEL_SMART) "smart/default" else level}")
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fanLevelTitle(level: Int): String {
        return when (level) {
            PER_APP_LEVEL_SMART -> "智能调频（默认）"
            PER_APP_LEVEL_OFF -> "关闭风扇"
            1 -> "静谧模式"
            2 -> "高速强冷"
            4 -> "狂暴模式"
            else -> "未知($level)"
        }
    }

    private fun fanLevelSummary(level: Int): String {
        return when (level) {
            PER_APP_LEVEL_SMART -> "根据场景和温度智能匹配风扇转速"
            2 -> "强劲散热，伴随风噪和耗电增加"
            1 -> "低速风扇，适合安静氛围的场景"
            4 -> "疾速冷却，最大幅度提升散热能力"
            // 快充/导航场景有独立保底档位；低档 App 配置不压低场景档位。
            PER_APP_LEVEL_OFF -> "此应用前台运行时关闭风扇"
            else -> ""
        }
    }

    private fun serializePerAppFanRules(rules: Map<String, Int>): String {
        return rules.entries
            .filter { it.key.isNotBlank() && it.value in PER_APP_SUPPORTED_LEVELS && it.value != PER_APP_LEVEL_SMART }
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value}" }
    }

    private fun createPreferenceInstance(preferenceClass: Class<*>, context: Any): Any? {
        val contextClass = context.javaClass
        return try {
            val constructor = preferenceClass.constructors.firstOrNull { ctor ->
                ctor.parameterTypes.size == 1 && ctor.parameterTypes[0].isAssignableFrom(contextClass)
            } ?: preferenceClass.declaredConstructors.firstOrNull { ctor ->
                ctor.parameterTypes.size == 1 && ctor.parameterTypes[0].isAssignableFrom(contextClass)
            }
            if (constructor == null) {
                logInfo("No compatible constructor for ${preferenceClass.name}, context=${contextClass.name}")
                null
            } else {
                constructor.isAccessible = true
                constructor.newInstance(context)
            }
        } catch (t: Throwable) {
            logError("Create preference ${preferenceClass.name} failed", t)
            null
        }
    }

    private fun loadKeepFanSwitchPreferenceClass(classLoader: ClassLoader): Class<*>? {
        val names = arrayOf(
            "com.android.settingslib.miuisettings.preference.SwitchPreference",
            "miuix.preference.SwitchPreference",
            "androidx.preference.SwitchPreferenceCompat",
            "androidx.preference.SwitchPreference"
        )
        for (name in names) {
            try {
                return classLoader.loadClass(name)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun installKeepFanOnScreenOffChangeListener(classLoader: ClassLoader, preference: Any, resolver: ContentResolver) {
        val listenerClass = classLoader.loadClass("androidx.preference.Preference" + "$" + "OnPreferenceChangeListener")
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            classLoader,
            arrayOf(listenerClass)
        ) { _, method, args ->
            when (method.name) {
                "onPreferenceChange" -> {
                    val enabled = args?.getOrNull(1) as? Boolean ?: false
                    Settings.System.putInt(resolver, KEY_KEEP_FAN_ON_SCREEN_OFF, if (enabled) 1 else 0)
                    logInfo("Keep fan on screen off switch changed: $enabled")
                    true
                }
                "toString" -> "MifanKeepFanOnScreenOffListener"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === args?.getOrNull(0)
                else -> null
            }
        }
        callPreferenceVoid(preference, "setOnPreferenceChangeListener", arrayOf(listenerClass), listener)
    }

    private fun callPreferenceVoid(target: Any, methodName: String, parameterTypes: Array<Class<*>>, arg: Any?): Boolean {
        return try {
            val method = target.javaClass.findPublicOrDeclaredMethod(methodName, *parameterTypes) ?: return false
            method.invoke(target, arg)
            true
        } catch (t: Throwable) {
            logError("Call preference $methodName failed", t)
            false
        }
    }

    private fun callPreferenceBoolean(parent: Any, methodName: String, preference: Any): Boolean {
        return try {
            val method = parent.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(preference.javaClass)
            } ?: parent.javaClass.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1 && it.parameterTypes[0].isAssignableFrom(preference.javaClass)
            } ?: return false
            method.isAccessible = true
            method.invoke(parent, preference) as? Boolean ?: true
        } catch (t: Throwable) {
            logError("Call preference group $methodName failed", t)
            false
        }
    }

    private fun isCoolingFanEnabled(resolver: ContentResolver?): Boolean {
        if (resolver == null) return true
        return try {
            Settings.System.getInt(resolver, KEY_COOLING_FAN_ENABLE, 1) == 1
        } catch (_: Throwable) {
            true
        }
    }

    private fun updateKeepFanOnScreenOffSwitchVisibilityFromPreference(preference: Any?, enabledOverride: Boolean?) {
        if (preference == null) return
        try {
            val parent = preference.javaClass.findPublicOrDeclaredMethod("getParent")?.invoke(preference) ?: return
            val keepSwitch = findPreferenceFromScreen(parent, KEY_KEEP_FAN_ON_SCREEN_OFF) ?: return
            val context = preference.javaClass.findPublicOrDeclaredMethod("getContext")?.invoke(preference)
            val resolver = context?.javaClass?.getMethod("getContentResolver")?.invoke(context) as? ContentResolver
            val visible = enabledOverride ?: isCoolingFanEnabled(resolver)
            setPreferenceVisible(keepSwitch, visible)
            logInfo("Keep fan on screen off switch visibility updated: visible=$visible")
        } catch (t: Throwable) {
            logError("Update keep fan on screen off switch visibility failed", t)
        }
    }

    private fun updatePerAppFanConfigEntryVisibilityFromPreference(preference: Any?, visibleOverride: Boolean?) {
        try {
            val context = preference?.javaClass?.findPublicOrDeclaredMethod("getContext")?.invoke(preference)
            val visible = visibleOverride ?: isCustomFanModeSelectedFromContext(context)
            val parent = preference?.javaClass?.findPublicOrDeclaredMethod("getParent")?.invoke(preference)
            val entry = (parent?.let { findPreferenceFromScreen(it, KEY_PER_APP_FAN_CONFIG_ENTRY) }
                ?: perAppFanConfigEntryRef?.get()) ?: run {
                logInfo("Per-app fan config entry visibility update skipped: entry not found, visible=$visible")
                return
            }
            perAppFanConfigEntryRef = WeakReference(entry)
            setPreferenceVisible(entry, visible)
            logInfo("Per-app fan config entry visibility updated: visible=$visible")
        } catch (t: Throwable) {
            logError("Update per-app fan config entry visibility failed", t)
        }
    }

    private fun updatePerAppFanConfigEntryVisibility(screen: Any?) {
        if (screen == null) return
        try {
            val entry = findPreferenceFromScreen(screen, KEY_PER_APP_FAN_CONFIG_ENTRY) ?: return
            val context = screen.javaClass.findPublicOrDeclaredMethod("getContext")?.invoke(screen)
            val visible = isCustomFanModeSelectedFromContext(context)
            setPreferenceVisible(entry, visible)
            logInfo("Per-app fan config entry visibility updated from screen: visible=$visible")
        } catch (t: Throwable) {
            logError("Update per-app fan config entry visibility from screen failed", t)
        }
    }

    private fun updateCustomModePreferenceVisibilityFromPreference(preference: Any?, customOverride: Boolean?) {
        if (preference == null) return
        try {
            val parent = preference.javaClass.findPublicOrDeclaredMethod("getParent")?.invoke(preference) ?: return
            updateCustomModePreferenceVisibility(parent, customOverride)
        } catch (t: Throwable) {
            logError("Update custom mode preference visibility from preference failed", t)
        }
    }

    private fun rememberGameScenePreference(preference: Any?) {
        if (preference != null) {
            gameScenePreferenceRef = WeakReference(preference)
        }
    }

    /**
     * Returns the game scene preference and whether it came directly from the current screen.
     * The weak-reference fallback covers in-page mode switches where MIUI does not re-run
     * FanSceneGameController.updateState(...) and screen lookup is temporarily incomplete.
     */
    private fun resolveGameScenePreference(screen: Any?): Pair<Any?, Boolean> {
        val fromScreen = findPreferenceFromScreen(screen, KEY_SCENE_GAMING)
        val preference = fromScreen ?: gameScenePreferenceRef?.get()
        rememberGameScenePreference(preference)
        return preference to (fromScreen != null)
    }

    private fun updateCustomModePreferenceVisibility(screen: Any?, customOverride: Boolean?) {
        if (screen == null) return
        try {
            val context = screen.javaClass.findPublicOrDeclaredMethod("getContext")?.invoke(screen)
            val custom = customOverride ?: isCustomFanModeSelectedFromContext(context)
            val range = findPreferenceFromScreen(screen, KEY_FAN_MODE_RANGE)
            val (game, gameFromScreen) = resolveGameScenePreference(screen)
            val rapidCharge = findPreferenceFromScreen(screen, KEY_SCENE_RAPID_CHARGE)
            val navigation = findPreferenceFromScreen(screen, KEY_SCENE_NAVIGATION)
            if (custom) {
                range?.let { setPreferenceVisible(it, false) }
                game?.let { setPreferenceVisible(it, false) }
                rapidCharge?.let { setPreferenceVisible(it, true) }
                navigation?.let { setPreferenceVisible(it, true) }
                logInfo("Custom mode scene preferences visibility updated: range=${range != null}->false, game=${game != null}->false(screen=$gameFromScreen), rapidCharge=${rapidCharge != null}->true, navigation=${navigation != null}->true")
            } else {
                // 非自定义模式尽量保持官方原 UI 行为；从自定义切出时恢复使用范围可见，并同步真实 fan_mode_range 到下拉框。
                if (customOverride == false) {
                    range?.let {
                        setPreferenceVisible(it, true)
                        syncFanModeRangePreferenceValue(it)
                    }
                    game?.let {
                        rememberGameScenePreference(it)
                        setPreferenceVisible(it, true)
                    }
                }
                logInfo("Custom mode scene preferences visibility skipped/restored for non-custom: customOverride=$customOverride")
            }
        } catch (t: Throwable) {
            logError("Update custom mode preference visibility failed", t)
        }
    }

    private fun syncFanModeRangePreferenceValue(rangePreference: Any?) {
        if (rangePreference == null) return
        try {
            val resolver = getPreferenceContentResolver(rangePreference) ?: return
            val range = Settings.System.getInt(resolver, KEY_FAN_MODE_RANGE, 0)
            callPreferenceVoid(rangePreference, "setValue", arrayOf(String::class.java), range.toString())
            logInfo("Fan mode range preference value synced to real fan_mode_range=$range")
        } catch (t: Throwable) {
            logError("Sync fan mode range preference value failed", t)
        }
    }

    private fun syncCustomModeVisibilityFromObserver(observer: Any?, preferenceRefField: String, source: String) {
        try {
            val controllerRef = observer?.readFieldOrNull("mControllerRef") as? java.lang.ref.WeakReference<*>
            val controller = controllerRef?.get() ?: return
            val preferenceRef = controller.readFieldOrNull(preferenceRefField) as? java.lang.ref.WeakReference<*>
            val preference = preferenceRef?.get() ?: return
            val resolver = readControllerContentResolver(controller) ?: getPreferenceContentResolver(preference)
            val custom = isCustomFanModeSelected(resolver)
            updatePerAppFanConfigEntryVisibilityFromPreference(preference, custom)
            updateCustomModePreferenceVisibilityFromPreference(preference, custom)
            logInfo("$source synced custom mode page visibility after external settings change: custom=$custom")
        } catch (t: Throwable) {
            logError("$source sync custom mode scene visibility failed", t)
        }
    }

    private fun readControllerContentResolver(controller: Any?): ContentResolver? {
        val context = controller?.readFieldOrNull("mContext") ?: return null
        return try {
            context.javaClass.getMethod("getContentResolver").invoke(context) as? ContentResolver
        } catch (_: Throwable) {
            null
        }
    }

    private fun isCustomFanModeUiEnabled(resolver: ContentResolver?): Boolean {
        if (resolver == null) return false
        return try {
            Settings.System.getInt(resolver, KEY_CUSTOM_FAN_MODE_ENABLED, 0) == 1
        } catch (_: Throwable) {
            false
        }
    }

    private fun isCustomFanModeSelectedFromContext(context: Any?): Boolean {
        val resolver = try {
            context?.javaClass?.getMethod("getContentResolver")?.invoke(context) as? ContentResolver
        } catch (_: Throwable) {
            null
        }
        return isCustomFanModeSelected(resolver)
    }

    private fun getPreferenceContentResolver(preference: Any?): ContentResolver? {
        return try {
            val context = preference?.javaClass?.findPublicOrDeclaredMethod("getContext")?.invoke(preference)
            context?.javaClass?.getMethod("getContentResolver")?.invoke(context) as? ContentResolver
        } catch (_: Throwable) {
            null
        }
    }

    private fun setPreferenceVisible(preference: Any?, visible: Boolean): Boolean {
        if (preference == null) return false
        return callPreferenceVoid(preference, "setVisible", arrayOf(Boolean::class.javaPrimitiveType!!), visible)
    }

    private fun ensureExtremeMode(controller: Any?, preference: Any?) {
        if (preference == null) return
        if (!isDropDownPreference(preference)) return

        try {
            appendEntries(preference)
            appendEntryValues(preference)
            appendSummaries(preference)
            writePreferenceRef(controller, preference)
            logInfo("Extreme/custom fan mode entries ensured")
        } catch (t: Throwable) {
            logError("Failed to ensure extreme/custom fan mode entries", t)
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
        var current = callCharSequenceArray(preference, "getEntries")
        if (current.none { it.toString() == MODE_EXTREME_TITLE }) {
            current = current.append(MODE_EXTREME_TITLE)
        }
        if (current.none { it.toString() == MODE_CUSTOM_TITLE }) {
            current = current.append(MODE_CUSTOM_TITLE)
        }
        callVoid(preference, "setEntries", arrayOf(CharSequence::class.java.arrayType()), current)
    }

    private fun appendEntryValues(preference: Any) {
        var current = callCharSequenceArray(preference, "getEntryValues")
        if (current.none { it.toString() == MODE_EXTREME_VALUE }) {
            current = current.append(MODE_EXTREME_VALUE)
        }
        if (current.none { it.toString() == MODE_CUSTOM_VALUE }) {
            current = current.append(MODE_CUSTOM_VALUE)
        }
        callVoid(preference, "setEntryValues", arrayOf(CharSequence::class.java.arrayType()), current)
    }

    private fun appendSummaries(preference: Any) {
        val entries = callCharSequenceArray(preference, "getEntries")
        val values = callCharSequenceArray(preference, "getEntryValues")
        val existing = readSummariesFromAdapter(preference)
        val result = ArrayList<CharSequence>(entries.size)
        for (i in entries.indices) {
            val value = values.getOrNull(i)?.toString()
            result += when (value) {
                MODE_EXTREME_VALUE -> MODE_EXTREME_SUMMARY
                MODE_CUSTOM_VALUE -> MODE_CUSTOM_SUMMARY
                else -> existing.getOrNull(i) ?: ""
            }
        }
        if (!callVoid(preference, "setSummaries", arrayOf(CharSequence::class.java.arrayType()), result.toTypedArray())) {
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
        val key = ReflectMethodKey(this, name, parameterTypes.toList())
        methodCache[key]?.let { return it }

        var c: Class<*>? = this
        while (c != null) {
            try {
                val method = c.getDeclaredMethod(name, *parameterTypes)
                method.isAccessible = true
                methodCache[key] = method
                return method
            } catch (_: NoSuchMethodException) {
                c = c.superclass
            }
        }
        return try {
            getMethod(name, *parameterTypes).apply {
                isAccessible = true
                methodCache[key] = this
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun Any.readFieldOrNull(fieldName: String): Any? {
        return try {
            javaClass.findCachedField(fieldName)?.get(this)
        } catch (_: Throwable) {
            null
        }
    }

    private fun Class<*>.findCachedField(fieldName: String): Field? {
        val key = ReflectFieldKey(this, fieldName)
        fieldCache[key]?.let { return it }

        var c: Class<*>? = this
        while (c != null) {
            try {
                val field = c.getDeclaredField(fieldName)
                field.isAccessible = true
                fieldCache[key] = field
                return field
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

    private fun logSettingsProbe(
        classLoader: ClassLoader,
        controllerClass: Class<*>,
        preferenceClass: Class<*>,
        preferenceScreenClass: Class<*>
    ) {
        val checks = listOf(
            "controller" to true,
            "Preference" to true,
            "PreferenceScreen" to true,
            "method displayPreference" to controllerClass.hasMethodCached("displayPreference", preferenceScreenClass),
            "method updateState" to controllerClass.hasMethodCached("updateState", preferenceClass),
            "method onPreferenceChange" to controllerClass.hasMethodCached("onPreferenceChange", preferenceClass, Any::class.java),
            "method getCurrentMode" to controllerClass.hasMethodCached("getCurrentMode"),
            "CoolingFanEnableController" to runCatching { classLoader.loadClass(COOLING_FAN_ENABLE_CONTROLLER) }.isSuccess
        )
        logProbe("Settings FanModeController probe", checks)
    }

    private fun logPowerKeeperProbe(classLoader: ClassLoader, handlerClass: Class<*>) {
        val messageClass = runCatching { classLoader.loadClass("android.os.Message") }.getOrNull()
        val stateClass = runCatching { classLoader.loadClass(FAN_STATE_HANDLER + "$" + "g") }.getOrNull()
        val checks = mutableListOf<Pair<String, Boolean>>()
        checks += "FanStateHandler" to true
        checks += "state class g" to (stateClass != null)
        checks += "method J" to handlerClass.hasMethodCached("J", String::class.java, String::class.java)
        checks += "method W" to (stateClass?.let { handlerClass.hasMethodCached("W", it) } == true)
        checks += "method handleMessage" to (messageClass?.let { handlerClass.hasMethodCached("handleMessage", it) } == true)
        checks += "method o" to handlerClass.hasMethodCached("o")
        for (field in arrayOf("g", "h", "x", "p", "w", "y", "z", "A", "B", "C", "D", "F", "L", "M", "N", "m0", "h0", "l")) {
            checks += "field $field" to handlerClass.hasFieldCached(field)
        }
        if (stateClass != null) {
            checks += "state field g" to stateClass.hasFieldCached("g")
        }
        logProbe("PowerKeeper FanStateHandler probe", checks)
    }

    private fun logSystemUiProbe(controllerClass: Class<*>, adapterClass: Class<*>) {
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

    private fun logMiSettingsProbe(
        classLoader: ClassLoader,
        activityClass: Class<*>,
        fragmentClass: Class<*>,
        searchFragmentClass: Class<*>,
        followHolderClass: Class<*>
    ) {
        val checks = listOf(
            "HighRefreshOptionsActivity" to true,
            "HighRefreshOptionsFragment" to true,
            "AppSearchFragment" to true,
            "oa.j" to true,
            "oa.h" to runCatching { classLoader.loadClass(MISETTINGS_APP_ITEM_MODEL) }.isSuccess,
            "androidx.preference.l0" to runCatching { classLoader.loadClass("androidx.preference.l0") }.isSuccess,
            "androidx.preference.m" to runCatching { classLoader.loadClass("androidx.preference.m") }.isSuccess,
            "miuix DropDownPreference" to runCatching { classLoader.loadClass("miuix.preference.DropDownPreference") }.isSuccess,
            "activity field d" to activityClass.hasFieldCached("d"),
            "activity field c" to activityClass.hasFieldCached("c"),
            "fragment field l" to fragmentClass.hasFieldCached("l"),
            "fragment field j" to fragmentClass.hasFieldCached("j"),
            "fragment field k" to fragmentClass.hasFieldCached("k"),
            "search field l" to searchFragmentClass.hasFieldCached("l"),
            "search field j" to searchFragmentClass.hasFieldCached("j"),
            "search field h" to searchFragmentClass.hasFieldCached("h"),
            "holder field e" to followHolderClass.hasFieldCached("e"),
            "holder field c" to followHolderClass.hasFieldCached("c"),
            "method bind a" to followHolderClass.declaredMethods.any { it.name == "a" }
        )
        logProbe("MISettings fan config probe", checks)
    }

    private fun logProbe(title: String, checks: List<Pair<String, Boolean>>) {
        val summary = checks.joinToString(separator = ", ") { (name, ok) -> "$name=${if (ok) "ok" else "missing"}" }
        logInfo("$title: $summary")
    }

    private fun Class<*>.hasFieldCached(name: String): Boolean = findCachedField(name) != null

    private fun Class<*>.hasMethodCached(name: String, vararg parameterTypes: Class<*>): Boolean =
        findPublicOrDeclaredMethod(name, *parameterTypes) != null

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
        private val extremeToSmartBridgeInProgress = AtomicBoolean(false)
        private val powerKeeperStateLock = Any()
        private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
        private val fieldCache = ConcurrentHashMap<ReflectFieldKey, Field>()
        private val methodCache = ConcurrentHashMap<ReflectMethodKey, Method>()

        private data class ReflectFieldKey(val owner: Class<*>, val name: String)
        private data class ReflectMethodKey(val owner: Class<*>, val name: String, val parameterTypes: List<Class<*>>)
        @Volatile
        private var lastPositiveFanLevel = 0
        @Volatile
        private var lastPositiveFanLevelAt = 0L
        @Volatile
        private var lastPerAppAppliedPackage: String? = null
        @Volatile
        private var lastPerAppAppliedLevel = -1
        @Volatile
        private var lastPerAppAppliedAt = 0L
        @Volatile
        private var lastPerAppNoMatchPackage: String? = null
        @Volatile
        private var perAppFanConfigEntryRef: WeakReference<Any>? = null
        @Volatile
        private var gameScenePreferenceRef: WeakReference<Any>? = null
        @Volatile
        private var powerKeeperResolverRef: WeakReference<ContentResolver>? = null
        private val reflectWriteFailureLogged = ConcurrentHashMap.newKeySet<String>()

        private const val TAG = "FanModeHook"
        private const val TARGET_PACKAGE = "com.android.settings"
        private const val POWERKEEPER_PACKAGE = "com.miui.powerkeeper"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
        private const val MISETTINGS_PACKAGE = "com.xiaomi.misettings"
        private const val MISETTINGS_HIGH_REFRESH_OPTIONS_ACTIVITY = "com.xiaomi.misettings.display.RefreshRate.HighRefreshOptionsActivity"
        private const val MISETTINGS_HIGH_REFRESH_OPTIONS_FRAGMENT = "com.xiaomi.misettings.display.RefreshRate.HighRefreshOptionsFragment"
        private const val MISETTINGS_APP_SEARCH_FRAGMENT = "com.xiaomi.misettings.display.RefreshRate.AppSearchFragment"
        private const val MISETTINGS_FOLLOW_VIEW_HOLDER = "oa.j"
        private const val MISETTINGS_APP_ITEM_MODEL = "oa.h"
        private const val EXTRA_MIFAN_FAN_CONFIG_PAGE = "mifan_fan_config_page"
        private const val POWERKEEPER_BASE_CLASS = "com.miui.powerkeeper.unionpower.corehandler.a"
        private const val FIELD_PK_BASE_CONTEXT = "d"
        private const val FIELD_PK_RESOLVER_PRIMARY = "g"
        private const val FIELD_PK_RESOLVER_SECONDARY = "h"
        private const val FIELD_PK_TARGET_LEVEL = "p"
        private const val FIELD_PK_SCREEN_LOCKED = "L"
        private const val FIELD_PK_RECORDING_ACTIVE = "F"
        private const val FIELD_PK_SIM_CALL_ACTIVE = "M"
        private const val FIELD_PK_EARPIECE_ACTIVE = "N"
        private const val FIELD_PK_MIC_SWITCH = "B"
        private const val FIELD_PK_FAST_CHARGE_ACTIVE = "C"
        private const val FIELD_PK_NAVIGATION_SCENE = "D"
        private const val FIELD_PK_GAME_SWITCH = "y"
        private const val FIELD_PK_CHARGE_SWITCH = "z"
        private const val FIELD_PK_NAVIGATION_SWITCH = "A"
        private const val FIELD_PK_FAN_MODE_RANGE = "l"
        private const val FIELD_PK_NAVIGATION_APPS = "m0"
        private const val FAN_MODE_CONTROLLER = "com.android.settings.coolingfan.FanModeController"
        private const val FAN_MODE_CONTENT_OBSERVER_SUFFIX = "\$FanModeContentObserver"
        private const val COOLING_FAN_ENABLE_CONTROLLER = "com.android.settings.coolingfan.CoolingFanEnableController"
        private const val FAN_MODE_USAGE_SCENES_CONTROLLER = "com.android.settings.coolingfan.FanModeUsageScenesController"
        private const val FAN_MODE_USAGE_SCENES_CONTENT_OBSERVER_SUFFIX = "\$FanModeUsageScenesContentObserver"
        private const val FAN_MODE_RANGE_CONTROLLER = "com.android.settings.coolingfan.FanModeRangeController"
        private const val FAN_SCENE_GAME_CONTROLLER = "com.android.settings.coolingfan.FanSceneGameController"
        private const val FAN_SCENE_RAPID_CHARGE_CONTROLLER = "com.android.settings.coolingfan.FanSceneRapidChargeController"
        private const val FAN_SCENE_NAVIGATION_CONTROLLER = "com.android.settings.coolingfan.FanSceneOutdoorController"
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
        private const val KEY_FAN_OTHER_FEATURES_CATEGORY = "fan_other_features_category"
        private const val KEY_EXTREME_TO_SMART_BRIDGE_UNTIL = "mifan_extreme_to_smart_bridge_until"
        private const val KEY_KEEP_FAN_ON_SCREEN_OFF = "mifan_keep_fan_on_screen_off"
        private const val KEY_PER_APP_FAN_RULES = "mifan_per_app_fan_rules"
        private const val KEY_CUSTOM_FAN_MODE_ENABLED = "mifan_custom_fan_mode_enabled"
        private const val KEY_PREVIOUS_FAN_MODE_RANGE = "mifan_previous_fan_mode_range"
        private const val KEY_PER_APP_FAN_CONFIG_ENTRY = "mifan_per_app_fan_config_entry"
        private const val PER_APP_PAGE_TAG = "mifan_per_app_fan_config_page"
        private const val TARGET_LEVEL = "target_level"
        private const val SYSTEMUI_FALLBACK_MODE_SMART_STRING_RES = 0x7f140c70
        private const val SYSTEMUI_FALLBACK_MODE_HIGH_STRING_RES = 0x7f140c6e
        private const val SYSTEMUI_FALLBACK_MODE_QUIET_STRING_RES = 0x7f140c6f
        private const val SYSTEMUI_FALLBACK_MODE_PERFORMANCE_ICON_RES = 0x7f0812c9
        private const val MODE_SMART_VALUE = "-1"
        private const val MODE_HIGH_VALUE = "2"
        private const val MODE_EXTREME_VALUE = "4"
        private const val MODE_CUSTOM_VALUE = "5"
        private const val PREVIOUS_FAN_MODE_RANGE_NONE = -1
        private const val PER_APP_LEVEL_SMART = -1
        private const val PER_APP_LEVEL_OFF = 0
        private val PER_APP_SUPPORTED_LEVELS = setOf(PER_APP_LEVEL_SMART, PER_APP_LEVEL_OFF, 1, 2, 4)
        private const val EXTREME_TO_SMART_PK_DELAY_MS = 800L
        private const val SYSTEMUI_BRIDGE_MASK_EXTRA_MS = 400L
        private const val KEEP_SCREEN_OFF_LEVEL_CACHE_MS = 10 * 60 * 1000L
        private const val PER_APP_ACTIVE_WRITE_MIN_INTERVAL_MS = 2_000L
        private const val MODE_EXTREME_TITLE = "狂暴模式"
        private const val MODE_EXTREME_SUMMARY = "疾速冷却，最大幅度提升散热能力"
        private const val MODE_CUSTOM_TITLE = "自定义"
        private const val MODE_CUSTOM_SUMMARY = "按前台应用的独立配置控制风扇；未配置应用默认智能调频"
        private const val PER_APP_FAN_CONFIG_TITLE = "按应用配置风扇模式"
        // 快充/导航场景保底 2 档，App 的关闭/静谧配置不压低该场景档位，狂暴等高档位可覆盖。
        private const val PER_APP_FAN_CONFIG_SUMMARY = "为每个应用单独选择智能调频、静谧、高速强冷、狂暴或关闭风扇"
        private const val KEEP_FAN_ON_SCREEN_OFF_TITLE = "息屏时保持风扇开启"
        // 快充高热场景可独立保持散热，不完全受普通息屏保持开关约束。
        private const val KEEP_FAN_ON_SCREEN_OFF_SUMMARY = "开启后，息屏或锁屏时各风扇档位仍可继续运行，可能增加功耗和噪音"
    }
}
