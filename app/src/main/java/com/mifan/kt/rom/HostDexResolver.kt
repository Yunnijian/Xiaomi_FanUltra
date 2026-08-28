package com.mifan.kt.rom

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Modifier

/**
 * Runtime resolution of obfuscated host symbols via DexKit, with [RomTargets] fallbacks.
 *
 * Hardcoded reverse-dump names remain the baseline; DexKit only upgrades names when a
 * high-confidence unique match is found for the current ROM APK.
 */
internal object HostDexResolver {

    data class PowerKeeperResolved(
        val handlerClass: String = RomTargets.FAN_STATE_HANDLER,
        val writePathMethod: String = RomTargets.METHOD_PK_WRITE_PATH,
        val updateStateMethod: String = RomTargets.METHOD_PK_UPDATE_STATE,
        val reloadSettingsMethod: String = RomTargets.METHOD_PK_RELOAD_SETTINGS,
        val source: String = "RomTargets",
    )

    data class SettingsResolved(
        val fanModeController: String = RomTargets.FAN_MODE_CONTROLLER,
        val source: String = "RomTargets",
    )

    data class SystemUiResolved(
        val coolingFanController: String = RomTargets.SYSTEMUI_COOLING_FAN_CONTROLLER,
        val detailAdapter: String = RomTargets.SYSTEMUI_COOLING_FAN_DETAIL_ADAPTER,
        val source: String = "RomTargets",
    )

    data class MiSettingsResolved(
        val highRefreshActivity: String = RomTargets.MISETTINGS_HIGH_REFRESH_OPTIONS_ACTIVITY,
        val highRefreshFragment: String = RomTargets.MISETTINGS_HIGH_REFRESH_OPTIONS_FRAGMENT,
        val appSearchFragment: String = RomTargets.MISETTINGS_APP_SEARCH_FRAGMENT,
        val source: String = "RomTargets",
    )

    fun resolvePowerKeeper(apkPath: String?, log: (String) -> Unit): PowerKeeperResolved {
        val fallback = PowerKeeperResolved()
        return DexKitSupport.withBridge(apkPath) { bridge ->
            resolvePowerKeeper(bridge, log)
        } ?: fallback.also {
            log(dexUnavailableMessage("PowerKeeper", apkPath))
        }
    }

    fun resolveSettings(apkPath: String?, log: (String) -> Unit): SettingsResolved {
        val fallback = SettingsResolved()
        return DexKitSupport.withBridge(apkPath) { bridge ->
            resolveSettings(bridge, log)
        } ?: fallback.also {
            log(dexUnavailableMessage("Settings", apkPath))
        }
    }

    fun resolveSystemUi(apkPath: String?, log: (String) -> Unit): SystemUiResolved {
        val fallback = SystemUiResolved()
        return DexKitSupport.withBridge(apkPath) { bridge ->
            resolveSystemUi(bridge, log)
        } ?: fallback.also {
            log(dexUnavailableMessage("SystemUI", apkPath))
        }
    }

    fun resolveMiSettings(apkPath: String?, log: (String) -> Unit): MiSettingsResolved {
        val fallback = MiSettingsResolved()
        return DexKitSupport.withBridge(apkPath) { bridge ->
            resolveMiSettings(bridge, log)
        } ?: fallback.also {
            log(dexUnavailableMessage("MISettings", apkPath))
        }
    }

    private fun resolvePowerKeeper(bridge: DexKitBridge, log: (String) -> Unit): PowerKeeperResolved {
        var handlerClass = RomTargets.FAN_STATE_HANDLER
        var writePath = RomTargets.METHOD_PK_WRITE_PATH
        var updateState = RomTargets.METHOD_PK_UPDATE_STATE
        var reload = RomTargets.METHOD_PK_RELOAD_SETTINGS
        var source = "RomTargets"

        runCatching {
            var classes = bridge.findClass {
                searchPackages("com.miui.powerkeeper")
                matcher {
                    usingStrings(
                        listOf("target_level", "fan_mode", "FanStateHandler"),
                        StringMatchType.Contains,
                    )
                }
            }
            // HyperOS 4 (Android 17) relocates the obfuscated FanStateHandler
            // to package r.h, so a package-scoped query returns no candidates.
            if (classes.isEmpty()) {
                classes = bridge.findClass {
                    matcher {
                        usingStrings(
                            listOf("target_level", "fan_mode", "FanStateHandler"),
                            StringMatchType.Contains,
                        )
                    }
                }
            }
            val preferred = classes.firstOrNull {
                it.name.endsWith("FanStateHandler") || it.name.contains("FanState") || it.name == RomTargets.FAN_STATE_HANDLER_OS4
            } ?: classes.singleOrNull()
            if (preferred != null) {
                handlerClass = preferred.name
                source = "DexKit"
                log("DexKit PowerKeeper handler: $handlerClass (candidates=${classes.size})")
            } else {
                log("DexKit PowerKeeper handler: keep fallback ($handlerClass), candidates=${classes.size}")
            }
        }.onFailure {
            log("DexKit PowerKeeper class search failed: ${it.message}")
        }

        runCatching {
            var methods = bridge.findMethod {
                searchPackages("com.miui.powerkeeper")
                matcher {
                    declaredClass = handlerClass
                    paramTypes("java.lang.String", "java.lang.String")
                    returnType = "boolean"
                }
            }
            if (methods.isEmpty()) {
                methods = bridge.findMethod {
                    matcher {
                        declaredClass = handlerClass
                        paramTypes("java.lang.String", "java.lang.String")
                        returnType = "boolean"
                    }
                }
            }
            // Prefer a short/obfuscated name (e.g. J) that takes (String,String) and returns boolean.
            val hit = methods.firstOrNull { it.name.length <= 2 }
                ?: methods.singleOrNull()
            if (hit != null) {
                writePath = hit.name
                source = "DexKit"
                log("DexKit PowerKeeper writePath method: $handlerClass.$writePath")
            }
        }.onFailure {
            log("DexKit PowerKeeper writePath search failed: ${it.message}")
        }

        runCatching {
            var methods = bridge.findMethod {
                searchPackages("com.miui.powerkeeper")
                matcher {
                    declaredClass = handlerClass
                    paramCount = 1
                    // W(state) updates fan policy from a state object.
                    modifiers = Modifier.PRIVATE
                }
            }
            if (methods.isEmpty()) {
                methods = bridge.findMethod {
                    matcher {
                        declaredClass = handlerClass
                        paramCount = 1
                        modifiers = Modifier.PRIVATE
                    }
                }
            }
            // Keep reverse-dump default unless we find a unique private 1-arg method named like W.
            val hit = methods.firstOrNull { it.name == RomTargets.METHOD_PK_UPDATE_STATE }
                ?: methods.firstOrNull { it.name.length == 1 && it.name[0].isUpperCase() }
            if (hit != null) {
                updateState = hit.name
                source = "DexKit"
                log("DexKit PowerKeeper updateState method: $handlerClass.$updateState")
            }
        }.onFailure {
            log("DexKit PowerKeeper updateState search failed: ${it.message}")
        }

        runCatching {
            var methods = bridge.findMethod {
                searchPackages("com.miui.powerkeeper")
                matcher {
                    declaredClass = handlerClass
                    paramCount = 0
                    modifiers = Modifier.PRIVATE
                    name = RomTargets.METHOD_PK_RELOAD_SETTINGS
                }
            }
            if (methods.isEmpty()) {
                methods = bridge.findMethod {
                    matcher {
                        declaredClass = handlerClass
                        paramCount = 0
                        usingStrings(listOf("fan_mode", "cooling_fan_enable"), StringMatchType.Contains)
                    }
                }
            }
            val hit = methods.singleOrNull()
                ?: bridge.findMethod {
                    searchPackages("com.miui.powerkeeper")
                    matcher {
                        declaredClass = handlerClass
                        paramCount = 0
                        usingStrings(listOf("fan_mode", "cooling_fan_enable"), StringMatchType.Contains)
                    }
                }.firstOrNull { it.name.length <= 2 }
            if (hit != null) {
                reload = hit.name
                source = "DexKit"
                log("DexKit PowerKeeper reloadSettings method: $handlerClass.$reload")
            }
        }.onFailure {
            log("DexKit PowerKeeper reloadSettings search failed: ${it.message}")
        }

        return PowerKeeperResolved(
            handlerClass = handlerClass,
            writePathMethod = writePath,
            updateStateMethod = updateState,
            reloadSettingsMethod = reload,
            source = source,
        )
    }

    private fun resolveSettings(bridge: DexKitBridge, log: (String) -> Unit): SettingsResolved {
        var controller = RomTargets.FAN_MODE_CONTROLLER
        var source = "RomTargets"
        runCatching {
            val classes = bridge.findClass {
                searchPackages("com.android.settings.coolingfan")
                matcher {
                    className = RomTargets.FAN_MODE_CONTROLLER
                }
            }
            val hit = classes.singleOrNull()
                ?: bridge.findClass {
                    searchPackages("com.android.settings")
                    matcher {
                        usingStrings(listOf("fan_mode"), StringMatchType.Equals)
                    }
                }.firstOrNull { it.name.contains("FanMode") || it.name.contains("CoolingFan") }
            if (hit != null) {
                controller = hit.name
                source = "DexKit"
                log("DexKit Settings FanModeController: $controller")
            } else {
                log("DexKit Settings FanModeController: keep fallback ($controller)")
            }
        }.onFailure {
            log("DexKit Settings class search failed: ${it.message}")
        }
        return SettingsResolved(fanModeController = controller, source = source)
    }

    private fun resolveSystemUi(bridge: DexKitBridge, log: (String) -> Unit): SystemUiResolved {
        var controller = RomTargets.SYSTEMUI_COOLING_FAN_CONTROLLER
        var adapter = RomTargets.SYSTEMUI_COOLING_FAN_DETAIL_ADAPTER
        var source = "RomTargets"
        runCatching {
            val classes = bridge.findClass {
                searchPackages("com.android.systemui")
                matcher {
                    usingStrings(listOf("fan_mode", "CoolingFanController"), StringMatchType.Contains)
                }
            }
            val controllerHit = classes.firstOrNull {
                it.name.endsWith("CoolingFanController") || it.name.contains("CoolingFanController")
            } ?: classes.firstOrNull { it.name.contains("CoolingFan") && !it.name.contains("Tile") }
            if (controllerHit != null) {
                controller = controllerHit.name
                source = "DexKit"
                log("DexKit SystemUI CoolingFanController: $controller")
            }

            val adapterClasses = bridge.findClass {
                searchPackages("com.android.systemui")
                matcher {
                    usingStrings(listOf("CoolingFan"), StringMatchType.Contains)
                }
            }
            val adapterHit = adapterClasses.firstOrNull {
                it.name.contains("CoolingFanDetailAdapter") || it.name.contains("DetailAdapter")
            }
            if (adapterHit != null) {
                adapter = adapterHit.name
                source = "DexKit"
                log("DexKit SystemUI detail adapter: $adapter")
            }
        }.onFailure {
            log("DexKit SystemUI class search failed: ${it.message}")
        }
        return SystemUiResolved(
            coolingFanController = controller,
            detailAdapter = adapter,
            source = source,
        )
    }

    private fun resolveMiSettings(bridge: DexKitBridge, log: (String) -> Unit): MiSettingsResolved {
        var activity = RomTargets.MISETTINGS_HIGH_REFRESH_OPTIONS_ACTIVITY
        var fragment = RomTargets.MISETTINGS_HIGH_REFRESH_OPTIONS_FRAGMENT
        var search = RomTargets.MISETTINGS_APP_SEARCH_FRAGMENT
        var source = "RomTargets"
        runCatching {
            val classes = bridge.findClass {
                searchPackages("com.xiaomi.misettings")
                matcher {
                    usingStrings(listOf("HighRefresh", "RefreshRate", "AppSearch"), StringMatchType.Contains)
                }
            }
            classes.firstOrNull { it.name.endsWith("HighRefreshOptionsActivity") }?.let {
                activity = it.name
                source = "DexKit"
            }
            classes.firstOrNull { it.name.endsWith("HighRefreshOptionsFragment") }?.let {
                fragment = it.name
                source = "DexKit"
            }
            classes.firstOrNull { it.name.endsWith("AppSearchFragment") }?.let {
                search = it.name
                source = "DexKit"
            }
            log(
                "DexKit MISettings hosts: activity=$activity, fragment=$fragment, " +
                    "search=$search, source=$source, candidates=${classes.size}"
            )
        }.onFailure {
            log("DexKit MISettings class search failed: ${it.message}")
        }
        return MiSettingsResolved(
            highRefreshActivity = activity,
            highRefreshFragment = fragment,
            appSearchFragment = search,
            source = source,
        )
    }

    private fun dexUnavailableMessage(host: String, apkPath: String?): String {
        val reason = when {
            apkPath.isNullOrBlank() -> "missing apk path"
            DexKitSupport.lastLoadError() != null ->
                "native load failed (source=${DexKitSupport.loadSource()}): ${DexKitSupport.lastLoadError()?.message}"
            else -> "bridge open failed (source=${DexKitSupport.loadSource()})"
        }
        return "DexKit unavailable for $host ($reason); using RomTargets fallback"
    }
}
