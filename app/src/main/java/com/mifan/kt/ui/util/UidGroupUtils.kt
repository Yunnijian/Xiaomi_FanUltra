package com.mifan.kt.ui.util

import android.content.Context
import com.mifan.kt.data.model.AppInfo
import java.util.concurrent.ConcurrentHashMap

private val PREFERRED_PKG_BY_SUID = mapOf(
    "android.uid.system" to "android",
    "android.uid.phone" to "com.android.phone",
    "android.uid.bluetooth" to "com.android.bluetooth",
    "android.uid.nfc" to "com.android.nfc",
)

fun pickPrimary(apps: List<AppInfo>): AppInfo {
    if (apps.isEmpty()) throw IllegalArgumentException("apps must not be empty")
    val labeled = apps.filter { it.packageInfo.sharedUserLabel != 0 }
    if (labeled.isNotEmpty()) {
        return labeled.minWith(compareBy({ it.packageName.length }, { it.packageName }))
    }
    val bySuid = apps.groupBy { it.packageInfo.sharedUserId ?: "" }
        .filterKeys { it.startsWith("android.uid.") }
    if (bySuid.isEmpty()) return apps.first()
    val suid = bySuid.keys.minOf { it }
    val group = bySuid[suid] ?: apps
    PREFERRED_PKG_BY_SUID[suid]?.let { pkg ->
        group.firstOrNull { it.packageName == pkg }?.let { return it }
    }
    return group.minWith(compareBy({ it.packageName.length }, { it.packageName }))
}

private val ownerNameCache = ConcurrentHashMap<Int, String>()

fun ownerNameForUid(uid: Int, appSource: List<AppInfo>? = null, context: Context? = null): String {
    ownerNameCache[uid]?.let { return it.ifEmpty { uid.toString() } }
    val apps = appSource.orEmpty()
    val labeledApp = apps.firstOrNull { it.packageInfo.sharedUserLabel != 0 }
    val name = if (labeledApp != null && context != null) {
        runCatching {
            context.packageManager.getText(
                labeledApp.packageName,
                labeledApp.packageInfo.sharedUserLabel,
                labeledApp.packageInfo.applicationInfo,
            )?.toString()
        }.getOrNull().orEmpty()
    } else {
        apps.firstOrNull { !it.packageInfo.sharedUserId.isNullOrEmpty() }?.packageInfo?.sharedUserId.orEmpty()
    }
    ownerNameCache[uid] = name
    return name.ifEmpty { uid.toString() }
}
