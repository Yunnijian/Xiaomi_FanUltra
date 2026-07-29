package com.mifan.kt

import android.util.Log
import com.mifan.kt.util.ReflectCache.findCachedField
import com.mifan.kt.util.ReflectCache.findPublicOrDeclaredMethod
import com.mifan.kt.util.ReflectCache.readFieldOrNull
import com.mifan.kt.util.ReflectCache.shouldLogWriteFailure
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * LSPosed / libxposed entry. Package routing only — host-side installers live in
 * [installFanModeHooks], [installPowerKeeperHooks], [installSystemUiHooks].
 *
 * Per-app fan UI is the module's own [com.mifan.kt.ui.FanAppsActivity] (no MISettings hook).
 * Official fan_mode numbering is ROM/cloud adaptive via
 * [com.mifan.kt.rom.OfficialFanModeSchema].
 */
class HookEntry : XposedModule() {
    override fun onPackageReady(param: PackageReadyParam) {
        try {
            val apkPath = param.applicationInfo.sourceDir
            when (param.packageName) {
                FanModeConstants.TARGET_PACKAGE -> {
                    installFanModeHooks(param.classLoader, apkPath)
                    logInfo("FanModeController hooks installed in ${param.packageName}")
                }
                FanModeConstants.POWERKEEPER_PACKAGE -> {
                    installPowerKeeperHooks(param.classLoader, apkPath)
                    logInfo("FanStateHandler hooks installed in ${param.packageName}")
                }
                FanModeConstants.SYSTEMUI_PACKAGE -> {
                    installSystemUiHooks(param.classLoader, apkPath)
                    logInfo("CoolingFanTile hooks installed in ${param.packageName}")
                }
            }
        } catch (t: Throwable) {
            logError("Failed to install hooks for ${param.packageName}", t)
        }
    }

    internal fun Any?.selectableIdentityAsInt(): Int? {
        if (this == null) return null
        return (readFieldOrNull("identity") as? Number)?.toInt()
    }

    internal fun android.content.Context.resourceId(name: String, type: String): Int {
        return resources.getIdentifier(name, type, packageName)
    }

    internal fun android.content.Context.stringByName(name: String): String? {
        val id = resourceId(name, "string")
        return if (id != 0) getString(id) else null
    }

    internal fun logReflectWriteFailure(owner: Class<*>, fieldName: String, operation: String, throwable: Throwable) {
        if (shouldLogWriteFailure(owner, fieldName, operation, throwable)) {
            logError("$operation failed for ${owner.name}.$fieldName", throwable)
        }
    }

    internal fun logProbe(title: String, checks: List<Pair<String, Boolean>>) {
        val summary = checks.joinToString(separator = ", ") { (name, ok) ->
            "$name=${if (ok) "ok" else "missing"}"
        }
        logInfo("$title: $summary")
    }

    internal fun Any.writeFieldIfExists(fieldName: String, value: Any?): Boolean {
        return try {
            val field = javaClass.findCachedField(fieldName) ?: return false
            field.set(this, value)
            true
        } catch (t: Throwable) {
            // Type mismatches are common when probing optional fields; only log once per key.
            logReflectWriteFailure(javaClass, fieldName, "writeField", t)
            false
        }
    }

    internal fun writeIntFieldIfExists(target: Any, fieldName: String, value: Int) {
        try {
            val field = target.javaClass.findCachedField(fieldName) ?: return
            field.setInt(target, value)
        } catch (t: Throwable) {
            logReflectWriteFailure(target.javaClass, fieldName, "writeIntField", t)
        }
    }

    internal fun Class<*>.hasFieldCached(name: String): Boolean = findCachedField(name) != null

    internal fun Class<*>.hasMethodCached(name: String, vararg parameterTypes: Class<*>): Boolean =
        findPublicOrDeclaredMethod(name, *parameterTypes) != null

    internal fun logInfo(message: String) {
        // Dual log: LSPosed bridge + plain logcat (plain survives when framework tags are filtered).
        Log.i(FanModeConstants.TAG, message)
        runCatching { log(Log.INFO, FanModeConstants.TAG, message) }
    }

    internal fun logError(message: String, throwable: Throwable) {
        Log.e(FanModeConstants.TAG, message, throwable)
        runCatching { log(Log.ERROR, FanModeConstants.TAG, message, throwable) }
    }
}
