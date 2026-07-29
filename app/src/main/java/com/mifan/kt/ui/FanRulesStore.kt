package com.mifan.kt.ui

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.mifan.kt.FanModeConstants
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Per-app fan rules storage.
 *
 * Dual write:
 * 1. [SharedPreferences] — always succeeds for the module UI (survives refresh)
 * 2. [Settings.System] — required by PowerKeeper hooks; needs WRITE_SETTINGS or root `settings`
 *
 * Reads merge both sources so UI stays correct even when system write is denied.
 */
internal object FanRulesStore {
    private const val TAG = "FanRulesStore"
    private const val PREFS_NAME = "mifan_fan_rules"
    private const val PREFS_KEY = "rules"

    fun read(context: Context): Map<String, Int> {
        val fromPrefs = parse(prefs(context).getString(PREFS_KEY, null))
        val fromSystem = readSystem(context)
        if (fromPrefs.isEmpty()) return fromSystem
        if (fromSystem.isEmpty()) return fromPrefs
        // Prefer local prefs for keys present there; keep system-only keys (e.g. set via adb / Settings dialog).
        return LinkedHashMap<String, Int>().apply {
            putAll(fromSystem)
            putAll(fromPrefs)
        }
    }

    fun write(context: Context, rules: Map<String, Int>) {
        val serialized = serialize(rules)
        // 1) Local durable store first — UI must not lose selection on refresh.
        // commit() (not apply) so an immediate read-back in the same process sees the value.
        val prefsOk = prefs(context).edit().putString(PREFS_KEY, serialized).commit()
        if (!prefsOk) {
            Log.e(TAG, "prefs commit FAILED len=${serialized.length}")
            throw IllegalStateException("SharedPreferences commit failed for fan rules")
        }
        Log.i(TAG, "prefs write ok len=${serialized.length} rules=${rules.size}")

        // 2) Publish for PowerKeeper (best effort + root fallback).
        putSystemString(context, FanModeConstants.KEY_PER_APP_FAN_RULES, serialized)
    }

    fun setLevel(context: Context, packageName: String, level: Int) {
        val rules = LinkedHashMap(read(context))
        if (level == FanModeConstants.PER_APP_LEVEL_SMART) rules.remove(packageName)
        else rules[packageName] = level
        write(context, rules)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readSystem(context: Context): Map<String, Int> {
        val raw = try {
            Settings.System.getString(context.contentResolver, FanModeConstants.KEY_PER_APP_FAN_RULES)
        } catch (t: Throwable) {
            Log.w(TAG, "Settings.System.getString failed", t)
            null
        }
        return parse(raw)
    }

    private fun serialize(rules: Map<String, Int>): String =
        rules.entries
            .filter {
                it.key.isNotBlank() &&
                    it.value in FanModeConstants.PER_APP_SUPPORTED_LEVELS &&
                    it.value != FanModeConstants.PER_APP_LEVEL_SMART
            }
            .sortedBy { it.key }
            .joinToString(";") { "${it.key}=${it.value}" }

    private fun parse(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, Int>()
        raw.split(';', '\n', ',').forEach { item ->
            val rule = item.trim()
            if (rule.isEmpty()) return@forEach
            val sep = rule.indexOf('=')
            if (sep <= 0 || sep >= rule.length - 1) return@forEach
            val pkg = rule.substring(0, sep).trim()
            val level = rule.substring(sep + 1).trim().toIntOrNull() ?: return@forEach
            if (pkg.isNotEmpty() && level in FanModeConstants.PER_APP_SUPPORTED_LEVELS) {
                result[pkg] = level
            }
        }
        return result
    }

    /**
     * Module uid often lacks WRITE_SETTINGS (special app-op). Try API write, then root `settings`.
     */
    private fun putSystemString(context: Context, key: String, value: String) {
        // Best-effort: allow WRITE_SETTINGS via root so future putString works without su.
        ensureWriteSettingsAllowed(context)

        val resolver = context.contentResolver
        val apiOk = try {
            Settings.System.putString(resolver, key, value)
        } catch (t: Throwable) {
            Log.w(TAG, "Settings.System.putString threw for $key", t)
            false
        }
        if (apiOk) {
            val readBack = try {
                Settings.System.getString(resolver, key).orEmpty()
            } catch (_: Throwable) {
                ""
            }
            if (readBack == value || (value.isEmpty() && readBack.isEmpty())) {
                Log.i(TAG, "system write via API ok key=$key len=${value.length}")
                return
            }
            Log.w(TAG, "system write API returned true but value mismatch; try su")
        } else {
            Log.w(TAG, "system write API denied for $key; try su")
        }

        if (putSystemStringViaSu(key, value)) {
            // Optional verify
            val readBack = try {
                Settings.System.getString(resolver, key).orEmpty()
            } catch (_: Throwable) {
                ""
            }
            if (readBack == value || (value.isEmpty() && readBack.isEmpty())) {
                Log.i(TAG, "system write via su ok key=$key")
            } else {
                Log.w(TAG, "system write via su finished but readBack mismatch (may need delay): got='$readBack'")
            }
        } else {
            // Prefs already saved; PowerKeeper will miss until user grants root / WRITE_SETTINGS.
            Log.e(
                TAG,
                "system write FAILED for $key — UI prefs kept; grant root to ${context.packageName} " +
                    "or allow WRITE_SETTINGS so PowerKeeper can see rules",
            )
        }
    }

    private fun ensureWriteSettingsAllowed(context: Context) {
        if (Settings.System.canWrite(context)) return
        // appops set <pkg> WRITE_SETTINGS allow
        runSu(
            "appops set ${context.packageName} WRITE_SETTINGS allow",
            "cmd appops set ${context.packageName} WRITE_SETTINGS allow",
        )
    }

    private fun putSystemStringViaSu(key: String, value: String): Boolean {
        val cmd = if (value.isEmpty()) {
            "settings delete system $key"
        } else {
            val escaped = value.replace("'", "'\\''")
            "settings put system $key '$escaped'"
        }
        return runSu(cmd)
    }

    private fun runSu(vararg commands: String): Boolean {
        for (cmd in commands) {
            val variants = listOf(
                arrayOf("su", "-c", cmd),
                arrayOf("/system/bin/su", "-c", cmd),
                arrayOf("/system/xbin/su", "-c", cmd),
            )
            for (argv in variants) {
                try {
                    val process = Runtime.getRuntime().exec(argv)
                    val finished = process.waitFor(8, TimeUnit.SECONDS)
                    if (!finished) {
                        process.destroyForcibly()
                        Log.w(TAG, "su timeout: ${argv.joinToString(" ")}")
                        continue
                    }
                    val code = process.exitValue()
                    if (code == 0) return true
                    val err = process.errorStream.bufferedReader().use(BufferedReader::readText).trim()
                    Log.w(TAG, "su exit=$code cmd=$cmd err=$err")
                } catch (t: Throwable) {
                    Log.w(TAG, "su exec failed argv=${argv.joinToString(" ")}", t)
                }
            }
        }
        return false
    }
}
