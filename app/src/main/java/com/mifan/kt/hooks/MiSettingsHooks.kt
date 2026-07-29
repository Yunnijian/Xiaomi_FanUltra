package com.mifan.kt

import android.content.ContentResolver
import android.content.Intent
import android.provider.Settings
import com.mifan.kt.rom.HostDexResolver
import com.mifan.kt.rom.RomTargets
import com.mifan.kt.util.ReflectCache.findPublicOrDeclaredMethod
import com.mifan.kt.util.ReflectCache.readFieldOrNull
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.Executors

private val fanConfigBgExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "mifan-fan-config").apply { isDaemon = true }
}

@Volatile
private var miuixArrowDrawableIdCache: Int = -1

internal fun HookEntry.installMiSettingsFanConfigHostHooks(classLoader: ClassLoader, apkPath: String? = null) {
    val resolved = HostDexResolver.resolveMiSettings(apkPath) { logInfo(it) }
    logInfo(
        "MISettings targets source=${resolved.source} dexkit=${com.mifan.kt.rom.DexKitSupport.loadSource()}: " +
            "activity=${resolved.highRefreshActivity}, fragment=${resolved.highRefreshFragment}, " +
            "search=${resolved.appSearchFragment}"
    )
    val activityClass = classLoader.loadClass(resolved.highRefreshActivity)
    val fragmentClass = classLoader.loadClass(resolved.highRefreshFragment)

    runCatching {
        val searchFragmentClass = classLoader.loadClass(resolved.appSearchFragment)
        val followHolderClass = classLoader.loadClass(RomTargets.MISETTINGS_FOLLOW_VIEW_HOLDER)
        logMiSettingsProbe(classLoader, activityClass, fragmentClass, searchFragmentClass, followHolderClass)
    }.onFailure { logError("MISettings fan config probe skipped", it) }

    val activityOnCreate = activityClass.getMethod("onCreate", android.os.Bundle::class.java)
    hook(activityOnCreate).intercept(XposedInterface.Hooker { chain ->
        val result = chain.proceed()
        val activity = chain.thisObject as? android.app.Activity
        if (isMifanFanConfigHostActivity(activity) && activity != null) {
            activity.title = FanModeConstants.PER_APP_FAN_CONFIG_TITLE
            // Warm launcher app cache as early as possible (background).
            preloadFanConfigLauncherApps(activity)
            logInfo("MISettings high refresh host opened for fan config")
        }
        result
    })

    val fragmentOnViewCreated = fragmentClass.getMethod("onViewCreated", android.view.View::class.java, android.os.Bundle::class.java)
    hook(fragmentOnViewCreated).intercept(XposedInterface.Hooker { chain ->
        val result = chain.proceed()
        val fragment = chain.thisObject
        if (isMifanFanConfigFragment(fragment)) {
            // Keep native loading spinner visible until async apply finishes.
            scheduleRebuildMiSettingsFanConfigList(fragment, reason = "onViewCreated")
        }
        result
    })

    runCatching {
        val fragmentOnStart = fragmentClass.getMethod("onStart")
        hook(fragmentOnStart).intercept(XposedInterface.Hooker { chain ->
            val result = chain.proceed()
            val fragment = chain.thisObject
            if (isMifanFanConfigFragment(fragment)) {
                // Only re-apply if list is still empty / not yet applied; avoid full rebuild jank.
                scheduleRebuildMiSettingsFanConfigList(fragment, reason = "onStart", onlyIfEmpty = true)
            }
            result
        })
    }.onFailure { logError("Hook MISettings fan fragment onStart failed", it) }

    runCatching {
        val rebuild = fragmentClass.getMethod("y")
        hook(rebuild).intercept(XposedInterface.Hooker { chain ->
            val fragment = chain.thisObject
            if (isMifanFanConfigFragment(fragment)) {
                // Replace native rebuild with our async path (same data, no main-thread scan).
                scheduleRebuildMiSettingsFanConfigList(fragment, reason = "native.y")
                null
            } else {
                chain.proceed()
            }
        })
    }.onFailure { logError("Hook MISettings fan fragment y failed", it) }

    runCatching {
        val searchFragmentClass = classLoader.loadClass(resolved.appSearchFragment)

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
        val followHolderClass = classLoader.loadClass(RomTargets.MISETTINGS_FOLLOW_VIEW_HOLDER)
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


internal fun HookEntry.isMifanFanConfigFragment(fragment: Any?): Boolean {
    return try {
        val activity = fragment?.javaClass?.findPublicOrDeclaredMethod("getActivity")?.invoke(fragment) as? android.app.Activity
            ?: fragment?.javaClass?.findPublicOrDeclaredMethod("n")?.invoke(fragment) as? android.app.Activity
        isMifanFanConfigHostActivity(activity)
    } catch (_: Throwable) {
        false
    }
}


/**
 * Async rebuild of the native HighRefresh list for fan-config mode.
 * Keeps MIUIX RecyclerView / adapters; only moves PackageManager work off the main thread.
 */
internal fun HookEntry.scheduleRebuildMiSettingsFanConfigList(
    fragment: Any,
    reason: String,
    onlyIfEmpty: Boolean = false
) {
    val activity = getMiSettingsSearchHostActivity(fragment) ?: return
    if (onlyIfEmpty) {
        val existing = fragment.readFieldOrNull("j") as? List<*>
        if (existing != null && existing.size > 2) {
            // Already has section headers + apps; skip redundant rebuild on onStart.
            return
        }
    }

    val token = FanSharedState.fanConfigRebuildToken.incrementAndGet()
    val classLoader = fragment.javaClass.classLoader ?: activity.classLoader
    val loading = fragment.readFieldOrNull("f") as? android.view.View
    // Keep spinner visible while building; avoid a blank white content area feeling "stuck".
    loading?.visibility = android.view.View.VISIBLE

    fanConfigBgExecutor.execute {
        try {
            if (token != FanSharedState.fanConfigRebuildToken.get()) return@execute
            val list = buildMiSettingsFanConfigItems(activity, classLoader)
            if (token != FanSharedState.fanConfigRebuildToken.get()) return@execute
            FanSharedState.mainHandler.post {
                if (token != FanSharedState.fanConfigRebuildToken.get()) return@post
                // Activity may have been destroyed while we built the list.
                if (activity.isFinishing || activity.isDestroyed) return@post
                applyMiSettingsFanConfigList(fragment, activity, list, classLoader)
                logInfo("MISettings fan config list applied ($reason): size=${list.size}")
            }
        } catch (t: Throwable) {
            FanSharedState.mainHandler.post {
                logError("Async rebuild MISettings fan config list failed ($reason)", t)
                loading?.visibility = android.view.View.GONE
            }
        }
    }
}

/** Apply already-built list on the main thread into native fragment/activity/adapter fields. */
internal fun HookEntry.applyMiSettingsFanConfigList(
    fragment: Any,
    activity: android.app.Activity,
    list: java.util.ArrayList<Any>,
    classLoader: ClassLoader
) {
    try {
        fragment.writeFieldIfExists("l", list)
        fragment.writeFieldIfExists("j", list)
        activity.writeFieldIfExists("d", list)
        syncMiSettingsFanConfigSearchSource(activity, classLoader, list)
        val adapter = fragment.readFieldOrNull("k")
        val loading = fragment.readFieldOrNull("f") as? android.view.View
        if (adapter != null) {
            adapter.writeFieldIfExists("j", list)
            val notify = adapter.javaClass.findPublicOrDeclaredMethod("notifyDataSetChanged")
            runCatching { notify?.invoke(adapter) }
                .onFailure { logError("Notify MISettings fan config list failed", it) }
        }
        loading?.visibility = android.view.View.GONE
    } catch (t: Throwable) {
        logError("Apply MISettings fan config list failed", t)
    }
}

/** @deprecated Prefer [scheduleRebuildMiSettingsFanConfigList]; kept for call sites that need sync apply after cache hit. */
internal fun HookEntry.rebuildMiSettingsFanConfigList(fragment: Any) {
    scheduleRebuildMiSettingsFanConfigList(fragment, reason = "sync-compat")
}

internal fun HookEntry.preloadFanConfigLauncherApps(activity: android.app.Activity) {
    val now = System.currentTimeMillis()
    if (FanSharedState.fanConfigLauncherApps.isNotEmpty() &&
        now - FanSharedState.fanConfigLauncherAppsAt < FanSharedState.FAN_CONFIG_LAUNCHER_CACHE_MS
    ) {
        return
    }
    fanConfigBgExecutor.execute {
        runCatching {
            val apps = queryLauncherAppsSorted(activity)
            FanSharedState.fanConfigLauncherApps = apps
            FanSharedState.fanConfigLauncherAppsAt = System.currentTimeMillis()
            logInfo("Fan config launcher cache warmed: size=${apps.size}")
        }.onFailure {
            logError("Warm fan config launcher cache failed", it)
        }
    }
}

internal fun HookEntry.queryLauncherAppsSorted(activity: android.app.Activity): List<Pair<String, String>> {
    val pm = activity.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(pkg)
            Pair(label, pkg)
        }
        .distinctBy { it.second }
        .sortedWith(compareBy<Pair<String, String>> { it.first.lowercase() }.thenBy { it.second })
}

internal fun HookEntry.cachedPerAppFanRules(resolver: ContentResolver): Map<String, Int> {
    val raw = try {
        Settings.System.getString(resolver, FanModeConstants.KEY_PER_APP_FAN_RULES)
    } catch (_: Throwable) {
        null
    }
    if (raw == FanSharedState.fanConfigRulesRaw) {
        return FanSharedState.fanConfigRulesCache
    }
    val parsed = readPerAppFanRules(resolver)
    FanSharedState.fanConfigRulesRaw = raw
    FanSharedState.fanConfigRulesCache = parsed
    return parsed
}

internal fun HookEntry.invalidatePerAppFanRulesCache() {
    FanSharedState.fanConfigRulesRaw = null
    FanSharedState.fanConfigRulesCache = emptyMap()
}

internal fun HookEntry.buildMiSettingsFanConfigItems(activity: android.app.Activity, classLoader: ClassLoader): java.util.ArrayList<Any> {
    val itemClass = classLoader.loadClass(RomTargets.MISETTINGS_APP_ITEM_MODEL)
    val titleCtor = itemClass.getConstructor(String::class.java)
    val appCtor = itemClass.getConstructor(
        android.content.Context::class.java,
        String::class.java,
        Boolean::class.javaPrimitiveType,
        Int::class.javaPrimitiveType
    )
    val rules = cachedPerAppFanRules(activity.contentResolver)
    val now = System.currentTimeMillis()
    val apps = if (FanSharedState.fanConfigLauncherApps.isNotEmpty() &&
        now - FanSharedState.fanConfigLauncherAppsAt < FanSharedState.FAN_CONFIG_LAUNCHER_CACHE_MS
    ) {
        FanSharedState.fanConfigLauncherApps
    } else {
        queryLauncherAppsSorted(activity).also {
            FanSharedState.fanConfigLauncherApps = it
            FanSharedState.fanConfigLauncherAppsAt = System.currentTimeMillis()
        }
    }
    val result = java.util.ArrayList<Any>(apps.size + 2)
    result.add(titleCtor.newInstance("已配置应用"))
    apps.filter { rules.containsKey(it.second) }.forEach { (_, pkg) ->
        result.add(appCtor.newInstance(activity, pkg, false, 3))
    }
    result.add(titleCtor.newInstance("智能调频（默认）"))
    apps.filterNot { rules.containsKey(it.second) }.forEach { (_, pkg) ->
        result.add(appCtor.newInstance(activity, pkg, false, 3))
    }
    return result
}


internal fun HookEntry.updateMiSettingsFanFollowRow(holder: Any, item: Any?) {
    try {
        val adapter = holder.readFieldOrNull("e")
        val activity = adapter?.readFieldOrNull("i") as? android.app.Activity ?: return
        if (!isMifanFanConfigHostActivity(activity)) return
        val packageName = item?.readFieldOrNull("a")?.toString()?.takeIf { it.isNotBlank() } ?: return
        val rules = cachedPerAppFanRules(activity.contentResolver)
        val level = rules[packageName] ?: FanModeConstants.PER_APP_LEVEL_SMART
        val summary = holder.readFieldOrNull("c") as? android.widget.TextView
        val titleText = fanLevelTitle(level).replace("（默认）", "")
        if (summary?.text?.toString() != titleText) {
            summary?.text = titleText
        }
        val color = if (level == FanModeConstants.PER_APP_LEVEL_SMART) 0xff8a8a8a.toInt() else 0xffff6900.toInt()
        if (summary?.currentTextColor != color) {
            summary?.setTextColor(color)
        }
        val arrowId = getMiuixArrowUpDownDrawableId(activity)
        // Avoid repeated drawable churn on every bind when already set.
        if (summary?.tag != arrowId) {
            summary?.setCompoundDrawablesWithIntrinsicBounds(0, 0, arrowId, 0)
            summary?.compoundDrawablePadding = dp(activity, 6)
            summary?.tag = arrowId
        }
        val row = findViewHolderItemView(holder)
        if (row != null) {
            row.isClickable = true
            // Reuse click binding when package unchanged to cut bind-time allocations.
            if (row.getTag(0x6d696661) != packageName) {
                row.setTag(0x6d696661, packageName)
                row.setOnClickListener {
                    val label = (holder.readFieldOrNull("b") as? android.widget.TextView)?.text?.toString()
                        ?.takeIf { it.isNotBlank() } ?: packageName
                    showMiSettingsFanModeDialog(activity, label, packageName, adapter, row)
                }
            }
        }
    } catch (t: Throwable) {
        logError("Update MISettings fan follow row failed", t)
    }
}


internal fun HookEntry.showMiSettingsFanModeDialog(activity: android.app.Activity, label: String, pkg: String, adapter: Any?, anchor: android.view.View?) {
    showFanModeDropDownPreferenceChooser(activity, label, pkg, anchor) {
        // After a rule change, invalidate rules cache and rebuild async (native list only).
        invalidatePerAppFanRulesCache()
        FanSharedState.fanConfigRebuildToken.incrementAndGet()
        try {
            // Prefer attaching to the visible fragment if still available via adapter host.
            val fragmentGuess = adapter?.readFieldOrNull("this$0")
            if (fragmentGuess != null && isMifanFanConfigFragment(fragmentGuess)) {
                scheduleRebuildMiSettingsFanConfigList(fragmentGuess, reason = "after-selection")
            } else {
                // Fallback: rebuild list objects and notify adapter without full fragment path.
                fanConfigBgExecutor.execute {
                    val list = buildMiSettingsFanConfigItems(activity, activity.classLoader)
                    FanSharedState.mainHandler.post {
                        if (activity.isFinishing || activity.isDestroyed) return@post
                        activity.writeFieldIfExists("d", list)
                        adapter?.writeFieldIfExists("j", list)
                        runCatching {
                            adapter?.javaClass?.findPublicOrDeclaredMethod("notifyDataSetChanged")?.invoke(adapter)
                        }
                        syncMiSettingsFanConfigSearchSource(activity, activity.classLoader, list)
                        logInfo("MISettings fan config list refreshed after selection: size=${list.size}")
                    }
                }
            }
        } catch (t: Throwable) {
            logError("Refresh MISettings fan config list after selection failed", t)
            runCatching {
                adapter?.javaClass?.findPublicOrDeclaredMethod("notifyDataSetChanged")?.invoke(adapter)
            }
        }
    }
}


internal fun HookEntry.showFanModeDropDownPreferenceChooser(activity: android.app.Activity, label: String, pkg: String, anchor: android.view.View?, onDone: () -> Unit) {
    try {
        val rules = LinkedHashMap(readPerAppFanRules(activity.contentResolver))
        val levels = intArrayOf(FanModeConstants.PER_APP_LEVEL_SMART, 2, 1, 4, FanModeConstants.PER_APP_LEVEL_OFF)
        val current = rules[pkg] ?: FanModeConstants.PER_APP_LEVEL_SMART
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
            if (level == FanModeConstants.PER_APP_LEVEL_SMART) rules.remove(pkg) else rules[pkg] = level
            val serialized = serializePerAppFanRules(rules)
            Settings.System.putString(activity.contentResolver, FanModeConstants.KEY_PER_APP_FAN_RULES, serialized)
            FanSharedState.fanConfigRulesRaw = serialized
            FanSharedState.fanConfigRulesCache = rules.toMap()
            logInfo("Per-app fan rule updated from native MIUI DropDownPreference popup: $pkg=${if (level == FanModeConstants.PER_APP_LEVEL_SMART) "smart/default" else level}, value=$valueText, reason=$reason")
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
            val spinnerId = activity.resources.getIdentifier("spinner", "id", FanModeConstants.MISETTINGS_PACKAGE)
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


internal fun HookEntry.getMiSettingsSearchHostActivity(fragment: Any): android.app.Activity? {
    return try {
        (fragment.readFieldOrNull("e") as? android.app.Activity)
            ?: (fragment.javaClass.findPublicOrDeclaredMethod("getActivity")?.invoke(fragment) as? android.app.Activity)
            ?: (fragment.javaClass.findPublicOrDeclaredMethod("n")?.invoke(fragment) as? android.app.Activity)
    } catch (_: Throwable) {
        null
    }
}


internal fun HookEntry.syncMiSettingsFanConfigSearchSource(activity: android.app.Activity, classLoader: ClassLoader, list: java.util.ArrayList<Any>? = null) {
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


internal fun HookEntry.syncMiSettingsFanConfigSearchFragment(fragment: Any, classLoader: ClassLoader): Boolean {
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


internal fun HookEntry.filterMiSettingsFanConfigSearch(fragment: Any, query: String) {
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


internal fun HookEntry.findViewHolderItemView(holder: Any): android.view.View? {
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


internal fun HookEntry.getMiuixArrowUpDownDrawableId(context: android.content.Context): Int {
    if (miuixArrowDrawableIdCache >= 0) return miuixArrowDrawableIdCache
    val id = context.resources.getIdentifier(
        "miuix_appcompat_arrow_up_down_integrated",
        "drawable",
        FanModeConstants.MISETTINGS_PACKAGE
    ).takeIf { it != 0 } ?: 0
    miuixArrowDrawableIdCache = id
    return id
}


internal fun HookEntry.showFanModeChoiceDialog(activity: android.app.Activity, label: String, pkg: String, onDone: () -> Unit) {
    val rules = LinkedHashMap(readPerAppFanRules(activity.contentResolver))
    val levels = intArrayOf(FanModeConstants.PER_APP_LEVEL_SMART, 2, 1, 4, FanModeConstants.PER_APP_LEVEL_OFF)
    val current = rules[pkg] ?: FanModeConstants.PER_APP_LEVEL_SMART
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
            if (level == FanModeConstants.PER_APP_LEVEL_SMART) rules.remove(pkg) else rules[pkg] = level
            val serialized = serializePerAppFanRules(rules)
            Settings.System.putString(activity.contentResolver, FanModeConstants.KEY_PER_APP_FAN_RULES, serialized)
            FanSharedState.fanConfigRulesRaw = serialized
            FanSharedState.fanConfigRulesCache = rules.toMap()
            logInfo("Per-app fan rule updated from fan-mode style chooser: $pkg=${if (level == FanModeConstants.PER_APP_LEVEL_SMART) "smart/default" else level}")
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


internal fun HookEntry.isMifanFanConfigHostActivity(activity: android.app.Activity?): Boolean {
    return try {
        activity?.intent?.getBooleanExtra(FanModeConstants.EXTRA_MIFAN_FAN_CONFIG_PAGE, false) == true
    } catch (_: Throwable) {
        false
    }
}


internal fun HookEntry.findActivityFromContext(context: android.content.Context?): android.app.Activity? {
    var current = context
    var depth = 0
    while (current != null && depth < 8) {
        if (current is android.app.Activity) return current
        current = (current as? android.content.ContextWrapper)?.baseContext
        depth++
    }
    return null
}


internal fun HookEntry.logMiSettingsProbe(
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
        "oa.h" to runCatching { classLoader.loadClass(RomTargets.MISETTINGS_APP_ITEM_MODEL) }.isSuccess,
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
