package com.mifan.kt.data.model

import android.content.pm.PackageInfo
import com.mifan.kt.FanModeConstants

/**
 * KernelSU AppInfo shape, with [fanLevel] instead of Natives.Profile.
 */
data class AppInfo(
    val label: String,
    val packageInfo: PackageInfo,
    val fanLevel: Int = FanModeConstants.PER_APP_LEVEL_SMART,
) {
    val packageName: String get() = packageInfo.packageName
    val uid: Int get() = packageInfo.applicationInfo!!.uid

    /** Ranking helper (like allowSu/custom in KernelSU). */
    val allowSu: Boolean get() = fanLevel != FanModeConstants.PER_APP_LEVEL_SMART
    val hasCustomProfile: Boolean get() = fanLevel != FanModeConstants.PER_APP_LEVEL_SMART
}
