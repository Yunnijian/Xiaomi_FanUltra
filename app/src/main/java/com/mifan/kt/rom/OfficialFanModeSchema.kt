package com.mifan.kt.rom

import com.mifan.kt.FanModeConstants.MODE_CUSTOM
import com.mifan.kt.FanModeConstants.MODE_EXTREME
import com.mifan.kt.FanModeConstants.MODE_SMART
import java.util.concurrent.atomic.AtomicReference

/**
 * Official HyperOS [fan_mode] numbering.
 *
 * Historical dual schemes (from reverse + product history):
 *
 * | Scheme | smart | high(高速强冷) | quiet(静谧) |
 * |--------|------:|---------------:|------------:|
 * | [Scheme.CONTROL_CENTER_ALIGNED] (SystemUI + new cloud) | -1 | **0** | 1 |
 * | [Scheme.LEGACY_SETTINGS] (older Settings entryValues) | -1 | **2** | 1 |
 *
 * HyperOS cloud control has aligned Settings with the control center, so the
 * default is [Scheme.CONTROL_CENTER_ALIGNED]. Runtime probes can still select
 * the legacy scheme when entry values clearly expose high=2.
 *
 * Module-owned modes stay fixed: extreme=4, custom UI identity=5.
 * [target_level] (0/1/2/3/4) is a separate hardware namespace — do not conflate
 * it with [fan_mode].
 */
data class OfficialFanModeSchema(
    val scheme: Scheme,
    val smart: Int = MODE_SMART,
    val high: Int,
    val quiet: Int,
    val extreme: Int = MODE_EXTREME,
    val customUi: Int = MODE_CUSTOM,
) {
    enum class Scheme {
        /** SystemUI / cloud-aligned: -1 smart, 0 high, 1 quiet. */
        CONTROL_CENTER_ALIGNED,

        /** Older Settings docs/entryValues: -1 smart, 1 quiet, 2 high. */
        LEGACY_SETTINGS,
    }

    val smartValue: String get() = smart.toString()
    val highValue: String get() = high.toString()
    val quietValue: String get() = quiet.toString()
    val extremeValue: String get() = extreme.toString()
    val customUiValue: String get() = customUi.toString()

    fun isOfficialMode(mode: Int): Boolean =
        mode == smart || mode == high || mode == quiet

    fun isHighMode(mode: Int): Boolean = mode == high

    fun labelForOfficial(mode: Int): String? = when (mode) {
        smart -> "智能调频"
        high -> "高速强冷"
        quiet -> "静谧模式"
        else -> null
    }

    companion object {
        val CONTROL_CENTER_ALIGNED = OfficialFanModeSchema(
            scheme = Scheme.CONTROL_CENTER_ALIGNED,
            high = 0,
            quiet = 1,
        )

        val LEGACY_SETTINGS = OfficialFanModeSchema(
            scheme = Scheme.LEGACY_SETTINGS,
            high = 2,
            quiet = 1,
        )

        /** Default after HyperOS Settings↔控制中心 fan_mode cloud alignment. */
        val DEFAULT = CONTROL_CENTER_ALIGNED

        private val active = AtomicReference(DEFAULT)

        fun current(): OfficialFanModeSchema = active.get()

        fun update(schema: OfficialFanModeSchema): OfficialFanModeSchema {
            active.set(schema)
            return schema
        }

        /**
         * Infer scheme from observed official mode IDs (Settings entryValues
         * or SystemUI SelectableItem identities). Ignores module IDs 4/5.
         */
        fun inferFromOfficialIds(ids: Collection<Int>): OfficialFanModeSchema {
            val official = ids.filter { it != MODE_EXTREME && it != MODE_CUSTOM }.toSet()
            if (official.isEmpty()) return current()

            val hasHigh0 = 0 in official
            val hasHigh2 = 2 in official
            return when {
                // Control-center / cloud: identities include 0 as 高速强冷.
                hasHigh0 && !hasHigh2 -> CONTROL_CENTER_ALIGNED
                // Legacy Settings: entryValues include 2 as 高速强冷.
                hasHigh2 && !hasHigh0 -> LEGACY_SETTINGS
                // Both present (rare / mixed dump): prefer cloud-aligned.
                hasHigh0 -> CONTROL_CENTER_ALIGNED
                else -> current()
            }
        }

        fun noteObservedOfficialIds(ids: Collection<Int>, source: String): OfficialFanModeSchema {
            val inferred = inferFromOfficialIds(ids)
            val previous = current()
            if (inferred.scheme != previous.scheme) {
                update(inferred)
            }
            return current()
        }
    }
}
