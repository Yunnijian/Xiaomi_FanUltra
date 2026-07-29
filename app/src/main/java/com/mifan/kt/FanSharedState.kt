package com.mifan.kt

import android.content.ContentResolver
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Process-wide hook state shared across Settings / PowerKeeper / SystemUI /
 * MISettings injectors (each runs in its own host process, so this is per-process).
 */
internal object FanSharedState {
    @Volatile
    var wasExtremeModeActive: Boolean = false

    @Volatile
    var lastFanModeSeen: Int = -1

    val extremeToSmartBridgeInProgress = AtomicBoolean(false)
    val powerKeeperStateLock = Any()
    val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile
    var lastPositiveFanLevel: Int = 0

    @Volatile
    var lastPositiveFanLevelAt: Long = 0L

    @Volatile
    var lastPerAppAppliedPackage: String? = null

    @Volatile
    var lastPerAppAppliedLevel: Int = -1

    @Volatile
    var lastPerAppAppliedAt: Long = 0L

    @Volatile
    var lastPerAppNoMatchPackage: String? = null

    @Volatile
    var perAppFanConfigEntryRef: WeakReference<Any>? = null

    @Volatile
    var gameScenePreferenceRef: WeakReference<Any>? = null

    @Volatile
    var powerKeeperResolverRef: WeakReference<ContentResolver>? = null

    // --- MISettings per-app fan list cache (host process only) ---
    /** Sorted launcher apps: label to packageName. */
    @Volatile
    var fanConfigLauncherApps: List<Pair<String, String>> = emptyList()

    @Volatile
    var fanConfigLauncherAppsAt: Long = 0L

    @Volatile
    var fanConfigRulesRaw: String? = null

    @Volatile
    var fanConfigRulesCache: Map<String, Int> = emptyMap()

    /** Generation token for in-flight async list rebuilds. */
    val fanConfigRebuildToken = java.util.concurrent.atomic.AtomicInteger(0)

    val fanConfigRebuildInFlight = AtomicBoolean(false)

    const val FAN_CONFIG_LAUNCHER_CACHE_MS = 60_000L
}
