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
import com.mifan.kt.util.ReflectCache.writeFieldIfExists
import io.github.libxposed.api.XposedInterface
import java.lang.ref.WeakReference
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method

/**
 * HyperOS 4 moved FanStateHandler from a named package to the obfuscated
 * `r.h` class and renamed all of its instance fields. Keep the logical
 * RomTargets names in hook code and resolve the OS4 aliases at runtime so the
 * same module remains compatible with older PowerKeeper builds.
 */
private fun Any.isOs4PowerKeeperObject(): Boolean {
    val name = javaClass.name
    return name == RomTargets.FAN_STATE_HANDLER_OS4 ||
        name.startsWith("${RomTargets.FAN_STATE_HANDLER_OS4}$") ||
        javaClass.findCachedField("f5029o") != null ||
        javaClass.findCachedField("f5020f") != null
}

private fun Any.readPowerKeeperField(logicalName: String): Any? {
    val candidates = if (isOs4PowerKeeperObject()) {
        listOfNotNull(RomTargets.POWERKEEPER_OS4_FIELD_ALIASES[logicalName], logicalName)
    } else {
        listOf(logicalName)
    }
    for (candidate in candidates.distinct()) {
        val value = readFieldOrNull(candidate)
        if (value != null) return value
    }
    return null
}

private fun Any.writePowerKeeperFieldIfExists(logicalName: String, value: Any?): Boolean {
    val candidates = if (isOs4PowerKeeperObject()) {
        listOfNotNull(RomTargets.POWERKEEPER_OS4_FIELD_ALIASES[logicalName], logicalName)
    } else {
        listOf(logicalName)
    }
    for (candidate in candidates.distinct()) {
        if (javaClass.findCachedField(candidate) != null && writeFieldIfExists(candidate, value)) return true
    }
    return false
}

private fun findPowerKeeperMethod(owner: Class<*>, names: List<String>, vararg parameterTypes: Class<*>): Method? {
    for (name in names.distinct()) {
        val method = runCatching { owner.getDeclaredMethod(name, *parameterTypes) }.getOrNull()
            ?: runCatching { owner.getMethod(name, *parameterTypes) }.getOrNull()
        if (method != null) return method
    }
    return null
}

private fun findPowerKeeperStateClass(handlerClass: Class<*>, classLoader: ClassLoader): Class<*>? {
    // Nested classes are the most reliable source on obfuscated builds.
    handlerClass.declaredClasses.firstOrNull { nested ->
        nested.declaredFields.any { it.name == "f5053g" || it.name == "f5047a" }
    }?.let { return it }

    val names = listOf(
        "${handlerClass.name}\$${RomTargets.PK_STATE_INNER_CLASS}",
        "${handlerClass.name}\$${RomTargets.PK_STATE_INNER_CLASS_OS4}",
    )
    for (name in names.distinct()) {
        runCatching { classLoader.loadClass(name) }.getOrNull()?.let { return it }
    }
    return null
}

internal fun HookEntry.installPowerKeeperHooks(classLoader: ClassLoader, apkPath: String? = null) {
    val resolved = HostDexResolver.resolvePowerKeeper(apkPath) { logInfo(it) }
    logInfo(
        "PowerKeeper targets source=${resolved.source} dexkit=${com.mifan.kt.rom.DexKitSupport.loadSource()}: " +
            "handler=${resolved.handlerClass}, write=${resolved.writePathMethod}, " +
            "update=${resolved.updateStateMethod}, reload=${resolved.reloadSettingsMethod}"
    )
    val handlerClass = sequenceOf(resolved.handlerClass, RomTargets.FAN_STATE_HANDLER_OS4)
        .distinct()
        .mapNotNull { name -> runCatching { classLoader.loadClass(name) }.getOrNull() }
        .firstOrNull()
    if (handlerClass == null) {
        logError("PowerKeeper FanStateHandler class not found: ${resolved.handlerClass}", IllegalStateException("class not found"))
        return
    }
    logPowerKeeperProbe(classLoader, handlerClass)
    val writePathMethod = findPowerKeeperMethod(
        handlerClass,
        listOf(resolved.writePathMethod, RomTargets.METHOD_PK_WRITE_PATH, RomTargets.METHOD_PK_WRITE_PATH_OS4),
        String::class.java,
        String::class.java,
    )
    if (writePathMethod == null) {
        logError("PowerKeeper target-level writer not found in ${handlerClass.name}", IllegalStateException("method not found"))
        return
    }
    writePathMethod.isAccessible = true

    // 官方会在 FanStateHandler.W(state) 中把息屏/锁屏视为高优先级禁用场景，
    // 非快充状态下最终写 target_level=0。开关开启时临时屏蔽 state.g(screenLock)，
    // 让智能调频/静谧/高速强冷/狂暴模式都继续走官方亮屏策略。
    runCatching {
        val stateClass = findPowerKeeperStateClass(handlerClass, classLoader)
            ?: error("FanState snapshot class not found")
        val updateFanState = findPowerKeeperMethod(
            handlerClass,
            listOf(resolved.updateStateMethod, RomTargets.METHOD_PK_UPDATE_STATE, RomTargets.METHOD_PK_UPDATE_STATE_OS4),
            stateClass,
        ) ?: error("FanState update method not found")
        updateFanState.isAccessible = true
        hook(updateFanState).intercept(XposedInterface.Hooker { chain ->
            val handler = chain.thisObject
            val state = chain.getArg(0)
            val resolver = readPowerKeeperResolver(handler)
            val keepOnScreenOff = isKeepFanOnScreenOffEnabled(resolver)
            val coolingFanEnabled = resolver?.let { Settings.System.getInt(it, FanModeConstants.KEY_COOLING_FAN_ENABLE, 1) == 1 } ?: true
            if (!keepOnScreenOff || !coolingFanEnabled || state == null) {
                chain.proceed()
            } else {
                val oldScreenLocked = readBooleanField(state, RomTargets.FIELD_PK_STATE_SCREEN_LOCK, false)
                try {
                    if (oldScreenLocked) {
                        state.writePowerKeeperFieldIfExists(RomTargets.FIELD_PK_STATE_SCREEN_LOCK, false)
                        logInfo("Keep fan on screen off enabled; mask FanStateHandler screenLock=false")
                    }
                    chain.proceed()
                } finally {
                    if (oldScreenLocked) {
                        state.writePowerKeeperFieldIfExists(RomTargets.FIELD_PK_STATE_SCREEN_LOCK, oldScreenLocked)
                    }
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
        if (key == FanModeConstants.TARGET_LEVEL) {
            val customDecision = resolveCustomFanPolicy(chain.thisObject)
            if (customDecision.blocked) {
                if (requestedValue != FanModeConstants.PER_APP_LEVEL_OFF.toString()) {
                    logInfo(
                        "Custom fan policy blocked target_level=$requestedValue -> 0: " +
                            customDecision.reason
                    )
                    chain.proceed(arrayOf(FanModeConstants.TARGET_LEVEL, FanModeConstants.PER_APP_LEVEL_OFF.toString()))
                } else {
                    chain.proceed()
                }
            } else if (customDecision.level != null) {
                val finalValue = customDecision.level.toString()
                updateLastPositiveFanLevel(key, finalValue)
                if (requestedValue != finalValue) {
                    logInfo("Custom per-app fan rule matched; override target_level=$requestedValue -> $finalValue")
                }
                chain.proceed(arrayOf(FanModeConstants.TARGET_LEVEL, finalValue))
            } else {
                updateLastPositiveFanLevel(key, requestedValue)
                // The unified custom decision has already checked master/scene/stop gates.
                // Only allow the historical screen-off preservation after that check.
                val keepLevel = if (!customDecision.blocked) {
                    keepCurrentFanLevelOnScreenOff(chain.thisObject, requestedValue)
                } else {
                    null
                }
                if (keepLevel != null) {
                    logInfo("Keep fan on screen off; preserve target_level=$requestedValue -> $keepLevel")
                    chain.proceed(arrayOf(FanModeConstants.TARGET_LEVEL, keepLevel.toString()))
                } else if (shouldPromoteOfficialLevelToExtreme(chain.thisObject, requestedValue)) {
                    logInfo("Extreme fan mode follows official policy; promote target_level=$requestedValue -> 4")
                    chain.proceed(arrayOf(FanModeConstants.TARGET_LEVEL, FanModeConstants.MODE_EXTREME_VALUE))
                } else if (shouldFloorCustomSmartTargetLevel(chain.thisObject, requestedValue)) {
                    val floor = FanModeConstants.PER_APP_SMART_TARGET_LEVEL
                    logInfo(
                        "Custom smart/default floor; clamp target_level=$requestedValue -> $floor"
                    )
                    chain.proceed(arrayOf(FanModeConstants.TARGET_LEVEL, floor.toString()))
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
    val reloadSettings = findPowerKeeperMethod(
        handlerClass,
        listOf(resolved.reloadSettingsMethod, RomTargets.METHOD_PK_RELOAD_SETTINGS),
    ) ?: error("PowerKeeper settings reload method not found")
    reloadSettings.isAccessible = true
    hook(reloadSettings).intercept(XposedInterface.Hooker { chain ->
        val result = chain.proceed()
        if (!applyPerAppFanPolicy(chain.thisObject, writePathMethod)) {
            applyExtremePolicy(chain.thisObject, writePathMethod)
        }
        result
    })
}

internal fun HookEntry.isExtremeModeSelected(handler: Any?): Boolean {
    return try {
        val resolver = readPowerKeeperResolver(handler) ?: return false
        Settings.System.getInt(resolver, FanModeConstants.KEY_FAN_MODE, -1) == FanModeConstants.MODE_EXTREME
    } catch (t: Throwable) {
        logError("Read fan_mode failed", t)
        false
    }
}

internal fun HookEntry.shouldPromoteOfficialLevelToExtreme(handler: Any?, requestedValue: String?): Boolean {
    if (!isExtremeModeSelected(handler)) return false
    val requestedLevel = requestedValue?.toIntOrNull() ?: return false
    return requestedLevel != FanModeConstants.MODE_EXTREME_VALUE.toInt() &&
        (requestedLevel > 0 || shouldForceExtremeLevel(handler))
}

internal fun HookEntry.readPowerKeeperResolver(handler: Any?): ContentResolver? {
    FanSharedState.powerKeeperResolverRef?.get()?.let { return it }
    if (handler != null) {
        val direct = handler.readPowerKeeperField(RomTargets.FIELD_PK_RESOLVER_PRIMARY)
            ?: handler.readPowerKeeperField(RomTargets.FIELD_PK_RESOLVER_SECONDARY)
        if (direct is ContentResolver) return rememberPowerKeeperResolver(direct)

        // OS4's static context lives in r.a.f4957d; older builds expose it from
        // the named corehandler base class. Try both, then scan the hierarchy.
        val classLoader = handler.javaClass.classLoader
        val contextClasses = listOf(RomTargets.POWERKEEPER_BASE_CLASS, "r.a")
        for (className in contextClasses) {
            val context = runCatching {
                classLoader?.loadClass(className)
                    ?.getDeclaredField(RomTargets.FIELD_PK_BASE_CONTEXT)
                    ?.apply { isAccessible = true }
                    ?.get(null)
            }.getOrNull()
            val resolver = runCatching { context?.javaClass?.getMethod("getContentResolver")?.invoke(context) }.getOrNull()
            if (resolver is ContentResolver) return rememberPowerKeeperResolver(resolver)
        }
        var current: Class<*>? = handler.javaClass
        while (current != null) {
            for (field in current.declaredFields) {
                if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                if (!android.content.Context::class.java.isAssignableFrom(field.type)) continue
                val context = runCatching { field.isAccessible = true; field.get(null) }.getOrNull()
                val resolver = runCatching { context?.javaClass?.getMethod("getContentResolver")?.invoke(context) }.getOrNull()
                if (resolver is ContentResolver) return rememberPowerKeeperResolver(resolver)
            }
            current = current.superclass
        }
    }
    return null
}

internal fun HookEntry.rememberPowerKeeperResolver(resolver: ContentResolver): ContentResolver {
    FanSharedState.powerKeeperResolverRef = WeakReference(resolver)
    return resolver
}

internal data class CustomFanPolicyDecision(
    val customMode: Boolean = false,
    val applicable: Boolean = false,
    val blocked: Boolean = false,
    val level: Int? = null,
    val packageName: String? = null,
    val reason: String = ""
)

/**
 * Resolves the complete custom-mode policy before any default floor or screen-off
 * preservation is considered. A blocked decision is explicit so it cannot be
 * mistaken for an unconfigured app (which is allowed to use the smart floor).
 */
internal fun HookEntry.resolveCustomFanPolicy(handler: Any?): CustomFanPolicyDecision {
    if (handler == null) return CustomFanPolicyDecision()
    val resolver = readPowerKeeperResolver(handler) ?: return CustomFanPolicyDecision()
    if (!isCustomFanModeSelected(resolver)) return CustomFanPolicyDecision()

    val packageName = handler.readPowerKeeperField(RomTargets.FIELD_PK_FOREGROUND_PACKAGE)
        ?.toString()
        ?.takeIf { it.isNotBlank() }
    val coolingFanEnabled = Settings.System.getInt(
        resolver,
        FanModeConstants.KEY_COOLING_FAN_ENABLE,
        1
    ) == 1
    if (!coolingFanEnabled) {
        return CustomFanPolicyDecision(
            customMode = true,
            applicable = true,
            blocked = true,
            packageName = packageName,
            reason = "cooling fan master switch is disabled"
        )
    }

    val micSwitch = Settings.System.getInt(
        resolver,
        FanModeConstants.KEY_SMART_STOP_RECORDING,
        if (readBooleanField(handler, RomTargets.FIELD_PK_MIC_SWITCH, true)) 1 else 0
    ) == 1
    val recordingActive = readBooleanField(handler, RomTargets.FIELD_PK_RECORDING_ACTIVE, false)
    if (micSwitch && recordingActive) {
        return CustomFanPolicyDecision(
            customMode = true,
            applicable = true,
            blocked = true,
            packageName = packageName,
            reason = "recording smart-stop is active"
        )
    }

    val simCall = readBooleanField(handler, RomTargets.FIELD_PK_SIM_CALL_ACTIVE, false)
    val earpiece = readBooleanField(handler, RomTargets.FIELD_PK_EARPIECE_ACTIVE, false)
    if (simCall && earpiece) {
        return CustomFanPolicyDecision(
            customMode = true,
            applicable = true,
            blocked = true,
            packageName = packageName,
            reason = "SIM call is using the earpiece"
        )
    }

    val fastChargeActive = readBooleanField(handler, RomTargets.FIELD_PK_FAST_CHARGE_ACTIVE, false)
    if (isScreenOffOrLocked(handler) && !isKeepFanOnScreenOffEnabled(resolver) && !fastChargeActive) {
        return CustomFanPolicyDecision(
            customMode = true,
            applicable = true,
            blocked = true,
            packageName = packageName,
            reason = "screen-off fan keep is disabled"
        )
    }

    val gameActive = isGameLikeSceneActive(handler)
    val chargeActive = fastChargeActive
    val navigationActive = packageName != null && isNavigationSceneActive(handler, packageName)
    val gameSwitch = Settings.System.getInt(
        resolver,
        FanModeConstants.KEY_SCENE_GAMING,
        if (readBooleanField(handler, RomTargets.FIELD_PK_GAME_SWITCH, true)) 1 else 0
    ) == 1
    val chargeSwitch = Settings.System.getInt(
        resolver,
        FanModeConstants.KEY_SCENE_RAPID_CHARGE,
        if (readBooleanField(handler, RomTargets.FIELD_PK_CHARGE_SWITCH, false)) 1 else 0
    ) == 1
    val navigationSwitch = Settings.System.getInt(
        resolver,
        FanModeConstants.KEY_SCENE_NAVIGATION,
        if (readBooleanField(handler, RomTargets.FIELD_PK_NAVIGATION_SWITCH, false)) 1 else 0
    ) == 1
    val blockedScene = when {
        gameActive && !gameSwitch -> "gaming scene switch is disabled"
        chargeActive && !chargeSwitch -> "rapid-charge scene switch is disabled"
        navigationActive && !navigationSwitch -> "navigation scene switch is disabled"
        else -> null
    }
    if (blockedScene != null) {
        return CustomFanPolicyDecision(
            customMode = true,
            applicable = true,
            blocked = true,
            packageName = packageName,
            reason = blockedScene
        )
    }

    if (packageName == null) {
        return CustomFanPolicyDecision(customMode = true)
    }

    val appLevel = readPerAppFanRules(resolver)[packageName]
    val sceneLevel = resolveCustomSceneLevel(handler, resolver, packageName)
    val mergedLevel = mergeCustomFanLevels(appLevel, sceneLevel)
        ?.takeIf { it != FanModeConstants.PER_APP_LEVEL_SMART }
    return CustomFanPolicyDecision(
        customMode = true,
        applicable = true,
        level = mergedLevel,
        packageName = packageName,
        reason = when {
            sceneLevel != null && appLevel != null -> "app and scene policy"
            sceneLevel != null -> "scene policy"
            appLevel != null -> "app policy"
            else -> "smart/default policy"
        }
    )
}

internal fun HookEntry.isGameLikeSceneActive(handler: Any): Boolean {
    val thermal = handler.readPowerKeeperField(RomTargets.FIELD_PK_THERMAL)?.toString()
    return containsInSetField(handler, RomTargets.FIELD_PK_GAME_THERMAL_SET_A, thermal) ||
        containsInSetField(handler, RomTargets.FIELD_PK_GAME_THERMAL_SET_B, thermal)
}

internal fun HookEntry.isNavigationSceneActive(handler: Any, packageName: String): Boolean {
    if (!readBooleanField(handler, RomTargets.FIELD_PK_NAVIGATION_SCENE, false)) return false
    val navigationApps = handler.readPowerKeeperField(RomTargets.FIELD_PK_NAVIGATION_APPS)
    return if (navigationApps is Map<*, *>) {
        navigationApps.containsKey(packageName)
    } else {
        // Keep the scene usable on ROMs that expose the active flag but not the app map.
        true
    }
}

internal fun HookEntry.resolveEffectivePerAppFanLevel(handler: Any?): Int? {
    val decision = resolveCustomFanPolicy(handler)
    if (!decision.customMode || !decision.applicable) return null
    return if (decision.blocked) FanModeConstants.PER_APP_LEVEL_OFF else decision.level
}

/**
 * In custom mode, apps on UI「智能调频」must not accept official target_level below the floor (1).
 * Explicit「关闭风扇」(0) is handled as a real per-app rule and never reaches here.
 */
internal fun HookEntry.shouldFloorCustomSmartTargetLevel(handler: Any?, requestedValue: String?): Boolean {
    val requested = requestedValue?.toIntOrNull() ?: return false
    if (requested >= FanModeConstants.PER_APP_SMART_TARGET_LEVEL) return false
    val decision = resolveCustomFanPolicy(handler)
    return decision.customMode && decision.applicable && !decision.blocked && decision.level == null
}

internal fun HookEntry.applyPerAppFanPolicy(handler: Any?, writePathMethod: Method): Boolean {
    if (handler == null) return false
    val resolver = readPowerKeeperResolver(handler) ?: return false
    if (!isCustomFanModeSelected(resolver)) return false

    val decision = resolveCustomFanPolicy(handler)
    if (!decision.applicable) return false
    val packageName = decision.packageName
    val cachePackage = packageName ?: "<custom-policy>"
    val level = if (decision.blocked) FanModeConstants.PER_APP_LEVEL_OFF else decision.level

    val now = System.currentTimeMillis()
    val hasAnyRule = Settings.System.getString(resolver, FanModeConstants.KEY_PER_APP_FAN_RULES)?.isNotBlank() == true
    if (decision.blocked) {
        val recentlyAppliedOff = synchronized(FanSharedState.powerKeeperStateLock) {
            cachePackage == FanSharedState.lastPerAppAppliedPackage &&
                FanSharedState.lastPerAppAppliedLevel == FanModeConstants.PER_APP_LEVEL_OFF &&
                now - FanSharedState.lastPerAppAppliedAt < FanModeConstants.PER_APP_ACTIVE_WRITE_MIN_INTERVAL_MS
        }
        if (!recentlyAppliedOff) {
            writeTargetLevelByPowerKeeper(
                handler,
                writePathMethod,
                FanModeConstants.PER_APP_LEVEL_OFF,
                "Custom fan policy blocked: pkg=${packageName ?: "unknown"}, reason=${decision.reason}"
            )
            synchronized(FanSharedState.powerKeeperStateLock) {
                FanSharedState.lastPerAppAppliedPackage = cachePackage
                FanSharedState.lastPerAppAppliedLevel = FanModeConstants.PER_APP_LEVEL_OFF
                FanSharedState.lastPerAppAppliedAt = now
                FanSharedState.lastPerAppNoMatchPackage = null
            }
        }
        return true
    }
    if (packageName == null) return false

    // UI default remains「智能调频」, but hardware floor is target_level=1 so the fan is not stuck at 0.
    if (level == null) {
        val floorLevel = FanModeConstants.PER_APP_SMART_TARGET_LEVEL
        var shouldLogSmartDefault = false
        synchronized(FanSharedState.powerKeeperStateLock) {
            if (hasAnyRule && packageName != FanSharedState.lastPerAppNoMatchPackage) {
                FanSharedState.lastPerAppNoMatchPackage = packageName
                shouldLogSmartDefault = true
            }
        }
        if (shouldLogSmartDefault) {
            logInfo(
                "Custom per-app fan check: pkg=$packageName, smart/default UI mode; " +
                    "floor target_level=$floorLevel (avoid official smart stuck at 0)"
            )
        }

        val recentlyAppliedFloor = synchronized(FanSharedState.powerKeeperStateLock) {
            packageName == FanSharedState.lastPerAppAppliedPackage &&
                FanSharedState.lastPerAppAppliedLevel == floorLevel &&
                now - FanSharedState.lastPerAppAppliedAt < FanModeConstants.PER_APP_ACTIVE_WRITE_MIN_INTERVAL_MS
        }
        if (recentlyAppliedFloor) return true

        writeTargetLevelByPowerKeeper(
            handler,
            writePathMethod,
            floorLevel,
            "Custom smart/default floor target_level=$floorLevel: pkg=$packageName"
        )
        synchronized(FanSharedState.powerKeeperStateLock) {
            FanSharedState.lastPerAppAppliedPackage = packageName
            FanSharedState.lastPerAppAppliedLevel = floorLevel
            FanSharedState.lastPerAppAppliedAt = now
        }
        return true
    }

    val recentlyApplied = synchronized(FanSharedState.powerKeeperStateLock) {
        packageName == FanSharedState.lastPerAppAppliedPackage &&
            level == FanSharedState.lastPerAppAppliedLevel &&
            now - FanSharedState.lastPerAppAppliedAt < FanModeConstants.PER_APP_ACTIVE_WRITE_MIN_INTERVAL_MS
    }
    if (recentlyApplied) return true

    val reason = "Custom per-app fan ${decision.reason} applied: pkg=$packageName, level=$level"
    writeTargetLevelByPowerKeeper(handler, writePathMethod, level, reason)
    synchronized(FanSharedState.powerKeeperStateLock) {
        FanSharedState.lastPerAppAppliedPackage = packageName
        FanSharedState.lastPerAppAppliedLevel = level
        FanSharedState.lastPerAppAppliedAt = now
        FanSharedState.lastPerAppNoMatchPackage = null
    }
    return true
}

internal fun HookEntry.applyExtremePolicy(handler: Any?, writePathMethod: Method) {
    if (handler == null) return
    val resolver = readPowerKeeperResolver(handler)
    val mode = resolver?.let { Settings.System.getInt(it, FanModeConstants.KEY_FAN_MODE, -1) } ?: -1

    // 非狂暴模式原则上交还 PowerKeeper 原生逻辑处理。
    // 只有“刚从狂暴模式切回智能调频”这一种场景，先桥接到官方高速强冷，
    // 让底层 target_level 从 4 回到官方档位，再延迟切回 fan_mode=-1。
    if (mode != FanModeConstants.MODE_EXTREME) {
        val shouldBridge = synchronized(FanSharedState.powerKeeperStateLock) {
            val bridge = mode == FanModeConstants.MODE_SMART_VALUE.toInt() &&
                (FanSharedState.wasExtremeModeActive || FanSharedState.lastFanModeSeen == FanModeConstants.MODE_EXTREME_VALUE.toInt())
            FanSharedState.wasExtremeModeActive = false
            FanSharedState.lastFanModeSeen = mode
            bridge
        }
        if (shouldBridge) {
            bridgeExtremeToSmartInPowerKeeper(handler, writePathMethod, resolver)
        }
        return
    }

    synchronized(FanSharedState.powerKeeperStateLock) {
        FanSharedState.lastFanModeSeen = FanModeConstants.MODE_EXTREME
    }
    if (shouldForceExtremeLevel(handler)) {
        synchronized(FanSharedState.powerKeeperStateLock) {
            FanSharedState.wasExtremeModeActive = true
        }
        writeTargetLevelByPowerKeeper(handler, writePathMethod, FanModeConstants.MODE_EXTREME)
    } else {
        writeTargetLevelByPowerKeeper(handler, writePathMethod, 0)
    }
}

internal fun HookEntry.bridgeExtremeToSmartInPowerKeeper(handler: Any?, writePathMethod: Method, resolver: ContentResolver?) {
    if (handler == null || resolver == null) return
    if (!FanSharedState.extremeToSmartBridgeInProgress.compareAndSet(false, true)) return
    try {
        val schema = OfficialFanModeSchema.current()
        logInfo("Extreme -> smart bridge in PowerKeeper: target_level=2, fan_mode=${schema.high} (${schema.scheme}), then fan_mode=-1")
        Settings.System.putLong(
            resolver,
            FanModeConstants.KEY_EXTREME_TO_SMART_BRIDGE_UNTIL,
            System.currentTimeMillis() + FanModeConstants.EXTREME_TO_SMART_PK_DELAY_MS + FanModeConstants.SYSTEMUI_BRIDGE_MASK_EXTRA_MS
        )
        writeTargetLevelByPowerKeeper(handler, writePathMethod, 2)
        val highMode = OfficialFanModeSchema.current().high
        Settings.System.putInt(resolver, FanModeConstants.KEY_FAN_MODE, highMode)
        FanSharedState.mainHandler.postDelayed({
            try {
                Settings.System.putInt(resolver, FanModeConstants.KEY_FAN_MODE, FanModeConstants.MODE_SMART_VALUE.toInt())
                logInfo("Extreme -> smart PowerKeeper bridge completed: fan_mode=-1")
            } catch (t: Throwable) {
                logError("Extreme -> smart PowerKeeper bridge failed", t)
            } finally {
                FanSharedState.extremeToSmartBridgeInProgress.set(false)
            }
        }, FanModeConstants.EXTREME_TO_SMART_PK_DELAY_MS)
    } catch (t: Throwable) {
        FanSharedState.extremeToSmartBridgeInProgress.set(false)
        logError("Start extreme -> smart PowerKeeper bridge failed", t)
    }
}

internal fun HookEntry.maskTransientBridgeFanModeForSystemUi(resolver: ContentResolver?, fanMode: Int): Int {
    if (resolver == null || !OfficialFanModeSchema.current().isHighMode(fanMode)) return fanMode
    return try {
        val bridgeUntil = Settings.System.getLong(resolver, FanModeConstants.KEY_EXTREME_TO_SMART_BRIDGE_UNTIL, 0L)
        if (bridgeUntil > System.currentTimeMillis()) FanModeConstants.MODE_SMART_VALUE.toInt() else fanMode
    } catch (_: Throwable) {
        fanMode
    }
}

internal fun HookEntry.updateLastPositiveFanLevel(key: String?, requestedValue: String?) {
    if (key != FanModeConstants.TARGET_LEVEL) return
    val level = requestedValue?.toIntOrNull() ?: return
    if (level > 0) {
        synchronized(FanSharedState.powerKeeperStateLock) {
            FanSharedState.lastPositiveFanLevel = level
            FanSharedState.lastPositiveFanLevelAt = System.currentTimeMillis()
        }
    }
}

internal fun HookEntry.keepCurrentFanLevelOnScreenOff(handler: Any?, requestedValue: String?): Int? {
    if (handler == null || requestedValue != "0") return null
    val resolver = readPowerKeeperResolver(handler) ?: return null
    val fastChargeActive = readBooleanField(handler, RomTargets.FIELD_PK_FAST_CHARGE_ACTIVE, false)
    if (!isKeepFanOnScreenOffEnabled(resolver) && !fastChargeActive) return null
    if (Settings.System.getInt(resolver, FanModeConstants.KEY_COOLING_FAN_ENABLE, 1) != 1) return null

    // 不再按 fan_mode 白名单过滤。部分 HyperOS 场景策略会使用 0/3 等内部模式值，
    // 这里以官方实际输出过的 target_level>0 为准，息屏时只保留这个官方已给出的正档位。
    if (!isScreenOffOrLocked(handler)) return null

    // 保留录音/通话听筒等高优先级停转场景，避免绕过安全/体验限制。
    val micSwitch = Settings.System.getInt(resolver, FanModeConstants.KEY_SMART_STOP_RECORDING, if (readBooleanField(handler, RomTargets.FIELD_PK_MIC_SWITCH, true)) 1 else 0) == 1
    val recordingActive = readBooleanField(handler, RomTargets.FIELD_PK_RECORDING_ACTIVE, false)
    val simCall = readBooleanField(handler, RomTargets.FIELD_PK_SIM_CALL_ACTIVE, false)
    val earpiece = readBooleanField(handler, RomTargets.FIELD_PK_EARPIECE_ACTIVE, false)
    if (micSwitch && recordingActive) return null
    if (simCall && earpiece) return null

    if (isExtremePartialSceneInactiveForScreenOffKeep(handler, resolver)) {
        logInfo("Keep fan on screen off skipped for extreme partial range: current scene inactive")
        return null
    }

    val currentLevel = readIntField(handler, RomTargets.FIELD_PK_TARGET_LEVEL, 0).takeIf { it > 0 }
    val cachedLevel = synchronized(FanSharedState.powerKeeperStateLock) {
        FanSharedState.lastPositiveFanLevel.takeIf {
            it > 0 && System.currentTimeMillis() - FanSharedState.lastPositiveFanLevelAt <= FanModeConstants.KEEP_SCREEN_OFF_LEVEL_CACHE_MS
        }
    }
    return currentLevel ?: cachedLevel
}

internal fun HookEntry.resolveEffectiveCustomFanLevel(
    handler: Any,
    resolver: ContentResolver,
    packageName: String,
    appLevel: Int?
): Int? {
    val sceneLevel = resolveCustomSceneLevel(handler, resolver, packageName)
    return mergeCustomFanLevels(appLevel, sceneLevel)
}

internal fun HookEntry.isCustomSceneFallbackAllowed(handler: Any, resolver: ContentResolver): Boolean {
    val decision = resolveCustomFanPolicy(handler)
    return decision.customMode && decision.applicable && !decision.blocked
}

internal fun HookEntry.mergeCustomFanLevels(appLevel: Int?, sceneLevel: Int?): Int? {
    val normalizedAppLevel = appLevel?.takeIf { it != FanModeConstants.PER_APP_LEVEL_SMART }
    return when {
        normalizedAppLevel != null && sceneLevel != null -> maxOf(normalizedAppLevel, sceneLevel)
        normalizedAppLevel != null -> normalizedAppLevel
        sceneLevel != null -> sceneLevel
        else -> appLevel
    }
}

internal fun HookEntry.resolveCustomSceneLevel(handler: Any, resolver: ContentResolver, packageName: String): Int? {
    val sceneLevel = 2 // target_level floor for rapid-charge/navigation scenes
    val chargeSwitch = Settings.System.getInt(resolver, FanModeConstants.KEY_SCENE_RAPID_CHARGE, if (readBooleanField(handler, RomTargets.FIELD_PK_CHARGE_SWITCH, false)) 1 else 0) == 1
    val navigationSwitch = Settings.System.getInt(resolver, FanModeConstants.KEY_SCENE_NAVIGATION, if (readBooleanField(handler, RomTargets.FIELD_PK_NAVIGATION_SWITCH, false)) 1 else 0) == 1
    val fastChargeActive = readBooleanField(handler, RomTargets.FIELD_PK_FAST_CHARGE_ACTIVE, false)
    val navigationScene = isNavigationSceneActive(handler, packageName)
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

internal fun HookEntry.resolvePerAppFanLevel(handler: Any?): Int? {
    val decision = resolveCustomFanPolicy(handler)
    if (!decision.customMode || !decision.applicable || decision.blocked) return null
    val resolver = readPowerKeeperResolver(handler) ?: return null
    val packageName = decision.packageName ?: return null
    return readPerAppFanRules(resolver)[packageName]
}

internal fun HookEntry.readPerAppFanRules(resolver: ContentResolver): Map<String, Int> {
    val raw = try {
        Settings.System.getString(resolver, FanModeConstants.KEY_PER_APP_FAN_RULES)
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
        if (packageName.isNotEmpty() && level in FanModeConstants.PER_APP_SUPPORTED_LEVELS) {
            result[packageName] = level
        }
    }
    return result
}

internal fun HookEntry.isScreenOffOrLocked(handler: Any): Boolean {
    if (readBooleanField(handler, RomTargets.FIELD_PK_SCREEN_LOCKED, false)) return true
    return try {
        val powerManager = handler.readPowerKeeperField(RomTargets.FIELD_PK_POWER_MANAGER)
        val interactive = powerManager?.javaClass?.getMethod("isInteractive")?.invoke(powerManager) as? Boolean
        interactive == false
    } catch (_: Throwable) {
        false
    }
}

internal fun HookEntry.isKeepFanOnScreenOffEnabled(resolver: ContentResolver?): Boolean {
    if (resolver == null) return false
    return try {
        Settings.System.getInt(resolver, FanModeConstants.KEY_KEEP_FAN_ON_SCREEN_OFF, 0) == 1
    } catch (_: Throwable) {
        false
    }
}

internal fun HookEntry.isExtremePartialSceneInactiveForScreenOffKeep(handler: Any, resolver: ContentResolver): Boolean {
    return try {
        val mode = Settings.System.getInt(resolver, FanModeConstants.KEY_FAN_MODE, -1)
        if (mode != FanModeConstants.MODE_EXTREME_VALUE.toInt()) return false
        val range = Settings.System.getInt(resolver, FanModeConstants.KEY_FAN_MODE_RANGE, readIntField(handler, RomTargets.FIELD_PK_FAN_MODE_RANGE, 0))
        if (range != 0) return false
        !shouldForceExtremeLevel(handler)
    } catch (_: Throwable) {
        false
    }
}

internal fun HookEntry.enterCustomFanMode(resolver: ContentResolver, source: String) {
    try {
        val wasCustom = Settings.System.getInt(resolver, FanModeConstants.KEY_CUSTOM_FAN_MODE_ENABLED, 0) == 1
        val previousStored = Settings.System.getInt(resolver, FanModeConstants.KEY_PREVIOUS_FAN_MODE_RANGE, FanModeConstants.PREVIOUS_FAN_MODE_RANGE_NONE)
        if (!wasCustom || previousStored == FanModeConstants.PREVIOUS_FAN_MODE_RANGE_NONE) {
            val currentRange = Settings.System.getInt(resolver, FanModeConstants.KEY_FAN_MODE_RANGE, 0)
            Settings.System.putInt(resolver, FanModeConstants.KEY_PREVIOUS_FAN_MODE_RANGE, currentRange)
            logInfo("$source enter custom mode: save previous fan_mode_range=$currentRange")
        }
        Settings.System.putInt(resolver, FanModeConstants.KEY_CUSTOM_FAN_MODE_ENABLED, 1)
        // 方案 B：UI 显示“自定义”，但底层 fan_mode 保持官方智能调频 -1。
        Settings.System.putInt(resolver, FanModeConstants.KEY_FAN_MODE, FanModeConstants.MODE_SMART_VALUE.toInt())
        // 自定义模式内部临时使用“部分场景”，让官方 UsageScenes/场景开关立即创建并显示；UI 再隐藏使用范围本身。
        Settings.System.putInt(resolver, FanModeConstants.KEY_FAN_MODE_RANGE, 0)
        logInfo("$source enter custom mode: fan_mode=-1, custom enabled=1, temporary fan_mode_range=0")
    } catch (t: Throwable) {
        logError("$source enter custom mode failed", t)
    }
}

internal fun HookEntry.exitCustomFanMode(resolver: ContentResolver, source: String) {
    try {
        val wasCustom = Settings.System.getInt(resolver, FanModeConstants.KEY_CUSTOM_FAN_MODE_ENABLED, 0) == 1
        if (wasCustom) {
            val previousRange = Settings.System.getInt(resolver, FanModeConstants.KEY_PREVIOUS_FAN_MODE_RANGE, FanModeConstants.PREVIOUS_FAN_MODE_RANGE_NONE)
            if (previousRange != FanModeConstants.PREVIOUS_FAN_MODE_RANGE_NONE) {
                Settings.System.putInt(resolver, FanModeConstants.KEY_FAN_MODE_RANGE, previousRange)
                Settings.System.putInt(resolver, FanModeConstants.KEY_PREVIOUS_FAN_MODE_RANGE, FanModeConstants.PREVIOUS_FAN_MODE_RANGE_NONE)
                logInfo("$source exit custom mode: restore previous fan_mode_range=$previousRange")
            }
        }
        Settings.System.putInt(resolver, FanModeConstants.KEY_CUSTOM_FAN_MODE_ENABLED, 0)
        logInfo("$source exit custom mode: custom enabled=0")
    } catch (t: Throwable) {
        logError("$source exit custom mode failed", t)
    }
}

internal fun HookEntry.isCustomFanModeSelected(resolver: ContentResolver?): Boolean {
    if (resolver == null) return false
    return try {
        if (Settings.System.getInt(resolver, FanModeConstants.KEY_CUSTOM_FAN_MODE_ENABLED, 0) == 1) return true
        // 兼容旧版真实 fan_mode=5：首次读到后迁移到方案 B。
        if (Settings.System.getInt(resolver, FanModeConstants.KEY_FAN_MODE, -1) == FanModeConstants.MODE_CUSTOM_VALUE.toInt()) {
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

internal fun HookEntry.shouldForceExtremeLevel(handler: Any?): Boolean {
    if (handler == null) return false
    val resolver = readPowerKeeperResolver(handler) ?: return false
    if (Settings.System.getInt(resolver, FanModeConstants.KEY_FAN_MODE, -1) != 4) return false
    if (Settings.System.getInt(resolver, FanModeConstants.KEY_COOLING_FAN_ENABLE, 1) != 1) return false

    val range = Settings.System.getInt(resolver, FanModeConstants.KEY_FAN_MODE_RANGE, readIntField(handler, RomTargets.FIELD_PK_FAN_MODE_RANGE, 0))
    val gameSwitch = Settings.System.getInt(resolver, FanModeConstants.KEY_SCENE_GAMING, if (readBooleanField(handler, RomTargets.FIELD_PK_GAME_SWITCH, true)) 1 else 0) == 1
    val chargeSwitch = Settings.System.getInt(resolver, FanModeConstants.KEY_SCENE_RAPID_CHARGE, if (readBooleanField(handler, RomTargets.FIELD_PK_CHARGE_SWITCH, false)) 1 else 0) == 1
    val navigationSwitch = Settings.System.getInt(resolver, FanModeConstants.KEY_SCENE_NAVIGATION, if (readBooleanField(handler, RomTargets.FIELD_PK_NAVIGATION_SWITCH, false)) 1 else 0) == 1
    val micSwitch = Settings.System.getInt(resolver, FanModeConstants.KEY_SMART_STOP_RECORDING, if (readBooleanField(handler, RomTargets.FIELD_PK_MIC_SWITCH, true)) 1 else 0) == 1

    // 和官方逻辑一致：通话/录音/锁屏等高优先级场景应允许风扇停止。
    val screenLocked = readBooleanField(handler, RomTargets.FIELD_PK_SCREEN_LOCKED, false)
    val fastChargeActive = readBooleanField(handler, RomTargets.FIELD_PK_FAST_CHARGE_ACTIVE, false)
    val keepOnScreenOff = isKeepFanOnScreenOffEnabled(resolver)
    val simCall = readBooleanField(handler, RomTargets.FIELD_PK_SIM_CALL_ACTIVE, false)
    val earpiece = readBooleanField(handler, RomTargets.FIELD_PK_EARPIECE_ACTIVE, false)
    if (screenLocked && !fastChargeActive && !keepOnScreenOff) return false
    if (simCall && earpiece) return false

    // 使用范围为全场景/其它场景时，官方会走 other 场景；这里允许狂暴模式生效。
    if (range == 1) return true

    val thermal = handler.readPowerKeeperField(RomTargets.FIELD_PK_THERMAL)?.toString()
    val foregroundPackage = handler.readPowerKeeperField(RomTargets.FIELD_PK_FOREGROUND_PACKAGE)?.toString()
    val gameLikeScene = containsInSetField(handler, RomTargets.FIELD_PK_GAME_THERMAL_SET_A, thermal) ||
        containsInSetField(handler, RomTargets.FIELD_PK_GAME_THERMAL_SET_B, thermal)
    // Align extreme mode with official modes: entering a navigation app alone is not enough.
    // Also guard against stale navigation-scene state after returning home.
    val navigationScene = readBooleanField(handler, RomTargets.FIELD_PK_NAVIGATION_SCENE, false) &&
        containsKeyInMapField(handler, RomTargets.FIELD_PK_NAVIGATION_APPS, foregroundPackage)
    val recordingActive = readBooleanField(handler, RomTargets.FIELD_PK_RECORDING_ACTIVE, false)
    // Navigation apps may hold the recording flag while real navigation is active; do not let that
    // transient audio state cancel the navigation scene itself.
    if (micSwitch && recordingActive && !navigationScene) return false

    return (gameSwitch && gameLikeScene) ||
        (chargeSwitch && fastChargeActive) ||
        (navigationSwitch && navigationScene)
}

internal fun HookEntry.writeTargetLevelByPowerKeeper(
    handler: Any?,
    writePathMethod: Method,
    level: Int,
    reason: String = "Extreme fan policy applied"
) {
    if (handler == null) return
    try {
        val ok = writePathMethod.invoke(handler, FanModeConstants.TARGET_LEVEL, level.toString())
        handler.writePowerKeeperFieldIfExists(RomTargets.FIELD_PK_TARGET_LEVEL, level)
        logInfo("$reason by PowerKeeper target-level writer ($level), result=$ok")
    } catch (t: Throwable) {
        logError("Apply target_level=$level by PowerKeeper failed", t)
    }
}

internal fun HookEntry.readIntField(target: Any, fieldName: String, defaultValue: Int): Int {
    val value = target.readPowerKeeperField(fieldName)
    return when (value) {
        is Int -> value
        is Number -> value.toInt()
        else -> defaultValue
    }
}

internal fun HookEntry.readBooleanField(target: Any, fieldName: String, defaultValue: Boolean): Boolean {
    val value = target.readPowerKeeperField(fieldName)
    return value as? Boolean ?: defaultValue
}

internal fun HookEntry.containsInSetField(target: Any, fieldName: String, value: String?): Boolean {
    if (value == null) return false
    return (target.readPowerKeeperField(fieldName) as? Set<*>)?.contains(value) == true
}

internal fun HookEntry.containsKeyInMapField(target: Any, fieldName: String, key: String?): Boolean {
    if (key == null) return false
    return (target.readPowerKeeperField(fieldName) as? Map<*, *>)?.containsKey(key) == true
}

internal fun HookEntry.logPowerKeeperProbe(classLoader: ClassLoader, handlerClass: Class<*>) {
    val messageClass = runCatching { classLoader.loadClass("android.os.Message") }.getOrNull()
    val stateClass = findPowerKeeperStateClass(handlerClass, classLoader)
    val checks = mutableListOf<Pair<String, Boolean>>()
    checks += "FanStateHandler ${handlerClass.name}" to true
    checks += "state snapshot class" to (stateClass != null)
    checks += "method target-level writer" to listOf("J", "K").any { handlerClass.hasMethodCached(it, String::class.java, String::class.java) }
    checks += "method state update" to (stateClass?.let { listOf("W", "X").any { name -> handlerClass.hasMethodCached(name, it) } } == true)
    checks += "method handleMessage" to (messageClass?.let { handlerClass.hasMethodCached("handleMessage", it) } == true)
    checks += "method o" to handlerClass.hasMethodCached("o")
    for (field in arrayOf("g", "h", "x", "p", "w", "y", "z", "A", "B", "C", "D", "F", "L", "M", "N", "m0", "h0", "l", "f5020f", "f5029o", "f5037w", "f5038x")) {
        checks += "field $field" to handlerClass.hasFieldCached(field)
    }
    if (stateClass != null) {
        checks += "state screen-lock field" to (stateClass.hasFieldCached("g") || stateClass.hasFieldCached("f5053g"))
    }
    logProbe("PowerKeeper FanStateHandler probe", checks)
}
