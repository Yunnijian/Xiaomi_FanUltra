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

internal fun HookEntry.installFanModeHooks(classLoader: ClassLoader, apkPath: String? = null) {
    val resolved = HostDexResolver.resolveSettings(apkPath) { logInfo(it) }
    logInfo(
        "Settings targets source=${resolved.source} dexkit=${com.mifan.kt.rom.DexKitSupport.loadSource()}: " +
            "fanModeController=${resolved.fanModeController}"
    )
    val controllerClass = classLoader.loadClass(resolved.fanModeController)
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
                FanModeConstants.MODE_CUSTOM_VALUE
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
        val fanModePreference = findPreferenceFromScreen(screen, FanModeConstants.KEY_FAN_MODE)
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
        if (value == FanModeConstants.MODE_CUSTOM_VALUE) {
            resolver?.let {
                enterCustomFanMode(it, "Settings")
            }
            updatePerAppFanConfigEntryVisibilityFromPreference(preference, true)
            updateCustomModePreferenceVisibilityFromPreference(preference, true)
            logInfo("Custom fan mode selected; keep fan_mode=-1 and enable mifan custom mode")
            true
        } else {
            resolver?.let { exitCustomFanMode(it, "Settings") }
            if (value == FanModeConstants.MODE_EXTREME_VALUE) {
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
        val enableControllerClass = classLoader.loadClass(RomTargets.COOLING_FAN_ENABLE_CONTROLLER)
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

internal fun HookEntry.installCustomModeSceneVisibilityControllerHooks(classLoader: ClassLoader, preferenceClass: Class<*>) {
    runCatching {
        val usageScenesClass = classLoader.loadClass(RomTargets.FAN_MODE_USAGE_SCENES_CONTROLLER)
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
        val rangeClass = classLoader.loadClass(RomTargets.FAN_MODE_RANGE_CONTROLLER)
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
        val gameSceneClass = classLoader.loadClass(RomTargets.FAN_SCENE_GAME_CONTROLLER)
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

    for (className in arrayOf(RomTargets.FAN_SCENE_RAPID_CHARGE_CONTROLLER, RomTargets.FAN_SCENE_NAVIGATION_CONTROLLER)) {
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

internal fun HookEntry.installSettingsCustomModeObserverSyncHooks(classLoader: ClassLoader) {
    runCatching {
        val observerClass = classLoader.loadClass(
            "${RomTargets.FAN_MODE_CONTROLLER}${RomTargets.FAN_MODE_CONTENT_OBSERVER_SUFFIX}"
        )
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
        val observerClass = classLoader.loadClass(
            "${RomTargets.FAN_MODE_USAGE_SCENES_CONTROLLER}${RomTargets.FAN_MODE_USAGE_SCENES_CONTENT_OBSERVER_SUFFIX}"
        )
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

internal fun HookEntry.findPreferenceFromScreen(screen: Any?, key: String): Any? {
    if (screen == null) return null
    return try {
        val method = screen.javaClass.findPublicOrDeclaredMethod("findPreference", CharSequence::class.java)
        method?.invoke(screen, key)
    } catch (t: Throwable) {
        logError("findPreference($key) failed", t)
        null
    }
}

internal fun HookEntry.ensureKeepFanOnScreenOffSwitch(classLoader: ClassLoader, screen: Any?) {
    if (screen == null) return
    if (findPreferenceFromScreen(screen, FanModeConstants.KEY_KEEP_FAN_ON_SCREEN_OFF) != null) return

    try {
        val context = screen.javaClass.findPublicOrDeclaredMethod("getContext")?.invoke(screen) ?: return
        val resolver = context.javaClass.getMethod("getContentResolver").invoke(context) as? ContentResolver ?: return
        val switchClass = loadKeepFanSwitchPreferenceClass(classLoader) ?: return
        val preference = createPreferenceInstance(switchClass, context) ?: return

        callPreferenceVoid(preference, "setKey", arrayOf(String::class.java), FanModeConstants.KEY_KEEP_FAN_ON_SCREEN_OFF)
        callPreferenceVoid(preference, "setTitle", arrayOf(CharSequence::class.java), FanModeConstants.KEEP_FAN_ON_SCREEN_OFF_TITLE)
        callPreferenceVoid(preference, "setSummary", arrayOf(CharSequence::class.java), FanModeConstants.KEEP_FAN_ON_SCREEN_OFF_SUMMARY)
        callPreferenceVoid(preference, "setPersistent", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        // 部分系统同款 SwitchPreference 的 setDependency 反射签名不稳定；
        // 可见性已由 CoolingFanEnableController.onPreferenceChange + setVisible 接管，避免无意义错误日志。
        setPreferenceVisible(preference, isCoolingFanEnabled(resolver))
        callPreferenceVoid(preference, "setChecked", arrayOf(Boolean::class.javaPrimitiveType!!), isKeepFanOnScreenOffEnabled(resolver))
        installKeepFanOnScreenOffChangeListener(classLoader, preference, resolver)

        val parent = findPreferenceFromScreen(screen, FanModeConstants.KEY_FAN_OTHER_FEATURES_CATEGORY) ?: screen
        var added = callPreferenceBoolean(parent, "addPreference", preference)
        if (!added && parent !== screen) {
            added = callPreferenceBoolean(screen, "addPreference", preference)
        }
        logInfo("Keep fan on screen off switch ensured, added=$added, class=${switchClass.name}")
    } catch (t: Throwable) {
        logError("Failed to ensure keep fan on screen off switch", t)
    }
}

internal fun HookEntry.ensurePerAppFanConfigEntry(classLoader: ClassLoader, screen: Any?) {
    if (screen == null) return
    findPreferenceFromScreen(screen, FanModeConstants.KEY_PER_APP_FAN_CONFIG_ENTRY)?.let { existing ->
        FanSharedState.perAppFanConfigEntryRef = WeakReference(existing)
        updatePerAppFanConfigEntryVisibility(screen)
        return
    }

    try {
        val context = screen.javaClass.findPublicOrDeclaredMethod("getContext")?.invoke(screen) ?: return
        val preferenceClass = loadBasicPreferenceClass(classLoader) ?: return
        val preference = createPreferenceInstance(preferenceClass, context) ?: return

        callPreferenceVoid(preference, "setKey", arrayOf(String::class.java), FanModeConstants.KEY_PER_APP_FAN_CONFIG_ENTRY)
        callPreferenceVoid(preference, "setTitle", arrayOf(CharSequence::class.java), FanModeConstants.PER_APP_FAN_CONFIG_TITLE)
        callPreferenceVoid(preference, "setSummary", arrayOf(CharSequence::class.java), FanModeConstants.PER_APP_FAN_CONFIG_SUMMARY)
        callPreferenceVoid(preference, "setPersistent", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        val visible = isCustomFanModeSelectedFromContext(context)
        setPreferenceVisible(preference, visible)
        FanSharedState.perAppFanConfigEntryRef = WeakReference(preference)
        logInfo("Per-app fan config entry ensured visibility: visible=$visible")
        installPerAppFanConfigClickListener(classLoader, preference, context)

        val parent = findPreferenceFromScreen(screen, FanModeConstants.KEY_FAN_OTHER_FEATURES_CATEGORY) ?: screen
        var added = callPreferenceBoolean(parent, "addPreference", preference)
        if (!added && parent !== screen) {
            added = callPreferenceBoolean(screen, "addPreference", preference)
        }
        logInfo("Per-app fan config entry ensured, added=$added, class=${preferenceClass.name}")
    } catch (t: Throwable) {
        logError("Failed to ensure per-app fan config entry", t)
    }
}

internal fun HookEntry.loadBasicPreferenceClass(classLoader: ClassLoader): Class<*>? {
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

internal fun HookEntry.installPerAppFanConfigClickListener(classLoader: ClassLoader, preference: Any, context: Any) {
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

internal fun HookEntry.openPerAppFanConfigPageInSettings(contextAny: Any) {
    try {
        val context = contextAny as? android.content.Context ?: return
        // Own Activity (KernelSU SuperUser-style list). No MISettings hook host.
        val intent = Intent().apply {
            // Always target the module package (context is Settings host process).
            setClassName(FanModeConstants.MODULE_PACKAGE, "com.mifan.kt.ui.FanAppsActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        logInfo("Open per-app fan config page: FanAppsActivity")
    } catch (t: Throwable) {
        logError("Open FanAppsActivity failed; fallback to dialog", t)
        showPerAppFanConfigDialog(contextAny)
    }
}

internal fun HookEntry.showPerAppFanConfigPageInActivity(activity: android.app.Activity) {
    val decor = activity.window.decorView as android.view.ViewGroup
    val oldPage = decor.findViewWithTag<android.view.View>(FanModeConstants.PER_APP_PAGE_TAG)
    if (oldPage != null) decor.removeView(oldPage)
    val root = android.widget.LinearLayout(activity).apply {
        tag = FanModeConstants.PER_APP_PAGE_TAG
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
        text = FanModeConstants.PER_APP_FAN_CONFIG_TITLE
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
        text = FanModeConstants.PER_APP_FAN_CONFIG_SUMMARY
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

internal fun HookEntry.renderPerAppFanRows(activity: android.app.Activity, list: android.widget.LinearLayout, query: String) {
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
    apps.filter { rules.containsKey(it.second) }.forEach { addPerAppFanRow(activity, list, it.first, it.second, it.third, rules[it.second] ?: FanModeConstants.PER_APP_LEVEL_SMART) }
    section("智能调频（默认）")
    apps.filterNot { rules.containsKey(it.second) }.forEach { addPerAppFanRow(activity, list, it.first, it.second, it.third, FanModeConstants.PER_APP_LEVEL_SMART) }
}

internal fun HookEntry.addPerAppFanRow(activity: android.app.Activity, list: android.widget.LinearLayout, label: String, pkg: String, icon: android.graphics.drawable.Drawable, level: Int) {
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
        setTextColor(if (level == FanModeConstants.PER_APP_LEVEL_SMART) 0xff888888.toInt() else 0xffff6900.toInt())
    }, android.widget.LinearLayout.LayoutParams(-2, -2))
    list.addView(row, android.widget.LinearLayout.LayoutParams(-1, -2))
}

internal fun HookEntry.showSettingsPageFanModeDialog(activity: android.app.Activity, label: String, pkg: String, onDone: () -> Unit) {
    showFanModeChoiceDialog(activity, label, pkg, onDone)
}

internal fun HookEntry.showPerAppFanConfigDialog(contextAny: Any) {
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
                Triple(label, app.packageName, rules[app.packageName] ?: FanModeConstants.PER_APP_LEVEL_SMART)
            }
            .sortedWith(compareBy<Triple<String, String, Int>> { it.first.lowercase() }.thenBy { it.second })
            .toList()

        if (apps.isEmpty()) {
            android.app.AlertDialog.Builder(context)
                .setTitle(FanModeConstants.PER_APP_FAN_CONFIG_TITLE)
                .setMessage("没有找到可启动的应用")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }

        val labels = apps.map { (label, pkg, level) ->
            label + 10.toChar() + pkg + " · " + fanLevelTitle(level)
        }.toTypedArray()

        android.app.AlertDialog.Builder(context)
            .setTitle(FanModeConstants.PER_APP_FAN_CONFIG_TITLE)
            .setItems(labels) { _, which ->
                val app = apps[which]
                showPerAppFanModeDialog(context, app.first, app.second)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton("清空全部配置") { _, _ ->
                Settings.System.putString(resolver, FanModeConstants.KEY_PER_APP_FAN_RULES, "")
                logInfo("Per-app fan rules cleared from Settings dialog")
            }
            .show()
    } catch (t: Throwable) {
        logError("Show per-app fan config dialog failed", t)
    }
}

internal fun HookEntry.showPerAppFanModeDialog(context: android.content.Context, appLabel: String, packageName: String) {
    val resolver = context.contentResolver
    val rules = LinkedHashMap(readPerAppFanRules(resolver))
    val currentLevel = rules[packageName] ?: FanModeConstants.PER_APP_LEVEL_SMART
    val levels = intArrayOf(FanModeConstants.PER_APP_LEVEL_SMART, 1, 2, 4, FanModeConstants.PER_APP_LEVEL_OFF)
    val titles = levels.map { fanLevelTitle(it) }.toTypedArray()
    val checked = levels.indexOf(currentLevel).takeIf { it >= 0 } ?: 0

    android.app.AlertDialog.Builder(context)
        .setTitle(appLabel)
        .setSingleChoiceItems(titles, checked) { dialog, which ->
            val level = levels[which]
            if (level == FanModeConstants.PER_APP_LEVEL_SMART) {
                rules.remove(packageName)
            } else {
                rules[packageName] = level
            }
            Settings.System.putString(resolver, FanModeConstants.KEY_PER_APP_FAN_RULES, serializePerAppFanRules(rules))
            logInfo("Per-app fan rule updated from Settings dialog: $packageName=${if (level == FanModeConstants.PER_APP_LEVEL_SMART) "smart/default" else level}")
            dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

internal fun HookEntry.createPreferenceInstance(preferenceClass: Class<*>, context: Any): Any? {
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

internal fun HookEntry.loadKeepFanSwitchPreferenceClass(classLoader: ClassLoader): Class<*>? {
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

internal fun HookEntry.installKeepFanOnScreenOffChangeListener(classLoader: ClassLoader, preference: Any, resolver: ContentResolver) {
    val listenerClass = classLoader.loadClass("androidx.preference.Preference" + "$" + "OnPreferenceChangeListener")
    val listener = java.lang.reflect.Proxy.newProxyInstance(
        classLoader,
        arrayOf(listenerClass)
    ) { _, method, args ->
        when (method.name) {
            "onPreferenceChange" -> {
                val enabled = args?.getOrNull(1) as? Boolean ?: false
                Settings.System.putInt(resolver, FanModeConstants.KEY_KEEP_FAN_ON_SCREEN_OFF, if (enabled) 1 else 0)
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

internal fun HookEntry.callPreferenceVoid(target: Any, methodName: String, parameterTypes: Array<Class<*>>, arg: Any?): Boolean {
    return try {
        val method = target.javaClass.findPublicOrDeclaredMethod(methodName, *parameterTypes) ?: return false
        method.invoke(target, arg)
        true
    } catch (t: Throwable) {
        logError("Call preference $methodName failed", t)
        false
    }
}

internal fun HookEntry.callPreferenceBoolean(parent: Any, methodName: String, preference: Any): Boolean {
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

internal fun HookEntry.isCoolingFanEnabled(resolver: ContentResolver?): Boolean {
    if (resolver == null) return true
    return try {
        Settings.System.getInt(resolver, FanModeConstants.KEY_COOLING_FAN_ENABLE, 1) == 1
    } catch (_: Throwable) {
        true
    }
}

internal fun HookEntry.updateKeepFanOnScreenOffSwitchVisibilityFromPreference(preference: Any?, enabledOverride: Boolean?) {
    if (preference == null) return
    try {
        val parent = preference.javaClass.findPublicOrDeclaredMethod("getParent")?.invoke(preference) ?: return
        val keepSwitch = findPreferenceFromScreen(parent, FanModeConstants.KEY_KEEP_FAN_ON_SCREEN_OFF) ?: return
        val context = preference.javaClass.findPublicOrDeclaredMethod("getContext")?.invoke(preference)
        val resolver = context?.javaClass?.getMethod("getContentResolver")?.invoke(context) as? ContentResolver
        val visible = enabledOverride ?: isCoolingFanEnabled(resolver)
        setPreferenceVisible(keepSwitch, visible)
        logInfo("Keep fan on screen off switch visibility updated: visible=$visible")
    } catch (t: Throwable) {
        logError("Update keep fan on screen off switch visibility failed", t)
    }
}

internal fun HookEntry.updatePerAppFanConfigEntryVisibilityFromPreference(preference: Any?, visibleOverride: Boolean?) {
    try {
        val context = preference?.javaClass?.findPublicOrDeclaredMethod("getContext")?.invoke(preference)
        val visible = visibleOverride ?: isCustomFanModeSelectedFromContext(context)
        val parent = preference?.javaClass?.findPublicOrDeclaredMethod("getParent")?.invoke(preference)
        val entry = (parent?.let { findPreferenceFromScreen(it, FanModeConstants.KEY_PER_APP_FAN_CONFIG_ENTRY) }
            ?: FanSharedState.perAppFanConfigEntryRef?.get()) ?: run {
            logInfo("Per-app fan config entry visibility update skipped: entry not found, visible=$visible")
            return
        }
        FanSharedState.perAppFanConfigEntryRef = WeakReference(entry)
        setPreferenceVisible(entry, visible)
        logInfo("Per-app fan config entry visibility updated: visible=$visible")
    } catch (t: Throwable) {
        logError("Update per-app fan config entry visibility failed", t)
    }
}

internal fun HookEntry.updatePerAppFanConfigEntryVisibility(screen: Any?) {
    if (screen == null) return
    try {
        val entry = findPreferenceFromScreen(screen, FanModeConstants.KEY_PER_APP_FAN_CONFIG_ENTRY) ?: return
        val context = screen.javaClass.findPublicOrDeclaredMethod("getContext")?.invoke(screen)
        val visible = isCustomFanModeSelectedFromContext(context)
        setPreferenceVisible(entry, visible)
        logInfo("Per-app fan config entry visibility updated from screen: visible=$visible")
    } catch (t: Throwable) {
        logError("Update per-app fan config entry visibility from screen failed", t)
    }
}

internal fun HookEntry.updateCustomModePreferenceVisibilityFromPreference(preference: Any?, customOverride: Boolean?) {
    if (preference == null) return
    try {
        val parent = preference.javaClass.findPublicOrDeclaredMethod("getParent")?.invoke(preference) ?: return
        updateCustomModePreferenceVisibility(parent, customOverride)
    } catch (t: Throwable) {
        logError("Update custom mode preference visibility from preference failed", t)
    }
}

internal fun HookEntry.rememberGameScenePreference(preference: Any?) {
    if (preference != null) {
        FanSharedState.gameScenePreferenceRef = WeakReference(preference)
    }
}

/**
 * Returns the game scene preference and whether it came directly from the current screen.
 * The weak-reference fallback covers in-page mode switches where MIUI does not re-run
 * FanSceneGameController.updateState(...) and screen lookup is temporarily incomplete.
 */

internal fun HookEntry.resolveGameScenePreference(screen: Any?): Pair<Any?, Boolean> {
    val fromScreen = findPreferenceFromScreen(screen, FanModeConstants.KEY_SCENE_GAMING)
    val preference = fromScreen ?: FanSharedState.gameScenePreferenceRef?.get()
    rememberGameScenePreference(preference)
    return preference to (fromScreen != null)
}

internal fun HookEntry.updateCustomModePreferenceVisibility(screen: Any?, customOverride: Boolean?) {
    if (screen == null) return
    try {
        val context = screen.javaClass.findPublicOrDeclaredMethod("getContext")?.invoke(screen)
        val custom = customOverride ?: isCustomFanModeSelectedFromContext(context)
        val range = findPreferenceFromScreen(screen, FanModeConstants.KEY_FAN_MODE_RANGE)
        val (game, gameFromScreen) = resolveGameScenePreference(screen)
        val rapidCharge = findPreferenceFromScreen(screen, FanModeConstants.KEY_SCENE_RAPID_CHARGE)
        val navigation = findPreferenceFromScreen(screen, FanModeConstants.KEY_SCENE_NAVIGATION)
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

internal fun HookEntry.syncFanModeRangePreferenceValue(rangePreference: Any?) {
    if (rangePreference == null) return
    try {
        val resolver = getPreferenceContentResolver(rangePreference) ?: return
        val range = Settings.System.getInt(resolver, FanModeConstants.KEY_FAN_MODE_RANGE, 0)
        callPreferenceVoid(rangePreference, "setValue", arrayOf(String::class.java), range.toString())
        logInfo("Fan mode range preference value synced to real fan_mode_range=$range")
    } catch (t: Throwable) {
        logError("Sync fan mode range preference value failed", t)
    }
}

internal fun HookEntry.syncCustomModeVisibilityFromObserver(observer: Any?, preferenceRefField: String, source: String) {
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

internal fun HookEntry.readControllerContentResolver(controller: Any?): ContentResolver? {
    val context = controller?.readFieldOrNull("mContext") ?: return null
    return try {
        context.javaClass.getMethod("getContentResolver").invoke(context) as? ContentResolver
    } catch (_: Throwable) {
        null
    }
}

internal fun HookEntry.isCustomFanModeUiEnabled(resolver: ContentResolver?): Boolean {
    if (resolver == null) return false
    return try {
        Settings.System.getInt(resolver, FanModeConstants.KEY_CUSTOM_FAN_MODE_ENABLED, 0) == 1
    } catch (_: Throwable) {
        false
    }
}

internal fun HookEntry.isCustomFanModeSelectedFromContext(context: Any?): Boolean {
    val resolver = try {
        context?.javaClass?.getMethod("getContentResolver")?.invoke(context) as? ContentResolver
    } catch (_: Throwable) {
        null
    }
    return isCustomFanModeSelected(resolver)
}

internal fun HookEntry.getPreferenceContentResolver(preference: Any?): ContentResolver? {
    return try {
        val context = preference?.javaClass?.findPublicOrDeclaredMethod("getContext")?.invoke(preference)
        context?.javaClass?.getMethod("getContentResolver")?.invoke(context) as? ContentResolver
    } catch (_: Throwable) {
        null
    }
}

internal fun HookEntry.setPreferenceVisible(preference: Any?, visible: Boolean): Boolean {
    if (preference == null) return false
    return callPreferenceVoid(preference, "setVisible", arrayOf(Boolean::class.javaPrimitiveType!!), visible)
}

internal fun HookEntry.ensureExtremeMode(controller: Any?, preference: Any?) {
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

internal fun HookEntry.isDropDownPreference(preference: Any): Boolean {
    var c: Class<*>? = preference.javaClass
    while (c != null) {
        if (c.name == "miuix.preference.DropDownPreference") return true
        c = c.superclass
    }
    return false
}

internal fun HookEntry.appendEntries(preference: Any) {
    var current = callCharSequenceArray(preference, "getEntries")
    if (current.none { it.toString() == FanModeConstants.MODE_EXTREME_TITLE }) {
        current = current.append(FanModeConstants.MODE_EXTREME_TITLE)
    }
    if (current.none { it.toString() == FanModeConstants.MODE_CUSTOM_TITLE }) {
        current = current.append(FanModeConstants.MODE_CUSTOM_TITLE)
    }
    callVoid(preference, "setEntries", arrayOf(CharSequence::class.java.arrayType()), current)
}

internal fun HookEntry.appendEntryValues(preference: Any) {
    var current = callCharSequenceArray(preference, "getEntryValues")
    val officialIds = current.mapNotNull { it.toString().toIntOrNull() }
        .filter { it != FanModeConstants.MODE_EXTREME && it != FanModeConstants.MODE_CUSTOM }
    if (officialIds.isNotEmpty()) {
        val schema = OfficialFanModeSchema.noteObservedOfficialIds(officialIds, "Settings.entryValues")
        logInfo(
            "Official fan_mode schema from Settings: ${schema.scheme} " +
                "high=${schema.high} quiet=${schema.quiet} ids=$officialIds"
        )
    }
    if (current.none { it.toString() == FanModeConstants.MODE_EXTREME_VALUE }) {
        current = current.append(FanModeConstants.MODE_EXTREME_VALUE)
    }
    if (current.none { it.toString() == FanModeConstants.MODE_CUSTOM_VALUE }) {
        current = current.append(FanModeConstants.MODE_CUSTOM_VALUE)
    }
    callVoid(preference, "setEntryValues", arrayOf(CharSequence::class.java.arrayType()), current)
}

internal fun HookEntry.appendSummaries(preference: Any) {
    val entries = callCharSequenceArray(preference, "getEntries")
    val values = callCharSequenceArray(preference, "getEntryValues")
    val existing = readSummariesFromAdapter(preference)
    val result = ArrayList<CharSequence>(entries.size)
    for (i in entries.indices) {
        val value = values.getOrNull(i)?.toString()
        result += when (value) {
            FanModeConstants.MODE_EXTREME_VALUE -> FanModeConstants.MODE_EXTREME_SUMMARY
            FanModeConstants.MODE_CUSTOM_VALUE -> FanModeConstants.MODE_CUSTOM_SUMMARY
            else -> existing.getOrNull(i) ?: ""
        }
    }
    if (!callVoid(preference, "setSummaries", arrayOf(CharSequence::class.java.arrayType()), result.toTypedArray())) {
        logInfo("DropDownPreference#setSummaries not available; title/value still appended")
    }
}

internal fun HookEntry.readSummariesFromAdapter(preference: Any): Array<CharSequence> {
    val adapter = preference.readFieldOrNull("mContentAdapter") ?: preference.readFieldOrNull("mAdapter")
    if (adapter != null) {
        val fromField = adapter.readFirstCharSequenceArrayField(arrayOf("mSummaries", "mEntrySummaries", "mSummary"))
        if (fromField != null) return fromField
    }
    return emptyArray()
}

internal fun HookEntry.normalizeSummaryCount(existing: Array<CharSequence>, expectedCount: Int): Array<CharSequence> {
    val result = ArrayList<CharSequence>(expectedCount.coerceAtLeast(0))
    for (i in 0 until expectedCount.coerceAtLeast(0)) {
        result += existing.getOrNull(i) ?: ""
    }
    return result.toTypedArray()
}

internal fun HookEntry.writePreferenceRef(controller: Any?, preference: Any) {
    if (controller == null) return
    try {
        val field = controller.javaClass.getDeclaredField("mDropDownPreferenceRef")
        field.isAccessible = true
        field.set(controller, WeakReference(preference))
    } catch (_: Throwable) {
        // displayPreference 原方法已设置该字段；这里失败不影响新增条目。
    }
}

internal fun HookEntry.callCharSequenceArray(target: Any, methodName: String): Array<CharSequence> {
    return try {
        val value = target.javaClass.findPublicOrDeclaredMethod(methodName)?.invoke(target)
        (value as? Array<*>)?.map { it?.toString().orEmpty() }?.toTypedArray() ?: emptyArray()
    } catch (_: Throwable) {
        emptyArray()
    }
}

internal fun HookEntry.callVoid(target: Any, methodName: String, parameterTypes: Array<Class<*>>, arg: Any): Boolean {
    return try {
        val method = target.javaClass.findPublicOrDeclaredMethod(methodName, *parameterTypes) ?: return false
        method.invoke(target, arg)
        true
    } catch (t: Throwable) {
        logError("Call $methodName failed", t)
        false
    }
}

internal fun HookEntry.logSettingsProbe(
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
        "CoolingFanEnableController" to runCatching { classLoader.loadClass(RomTargets.COOLING_FAN_ENABLE_CONTROLLER) }.isSuccess
    )
    logProbe("Settings FanModeController probe", checks)
}
