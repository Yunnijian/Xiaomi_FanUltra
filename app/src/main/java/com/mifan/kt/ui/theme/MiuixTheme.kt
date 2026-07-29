package com.mifan.kt.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.mifan.kt.ui.LocalUiMode
import com.mifan.kt.ui.UiMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** Same LocalEnableBlur as KernelSU SuperUserMiuix. */
val LocalEnableBlur = staticCompositionLocalOf { true }

@Composable
fun isInDarkTheme(): Boolean = isSystemInDarkTheme()

/**
 * Miuix theme setup aligned with KernelSU [MiuixKernelSUTheme]:
 * ThemeController is required for OverlayListPopup / OverlayDropdownPreference hosts.
 */
@Composable
fun KernelSuMiuixTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val controller = ThemeController(
        colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        isDark = darkTheme,
    )

    CompositionLocalProvider(
        LocalEnableBlur provides true,
        LocalUiMode provides UiMode.Miuix,
    ) {
        MiuixTheme(
            controller = controller,
            content = {
                LaunchedEffect(darkTheme) {
                    val window = (context as? Activity)?.window ?: return@LaunchedEffect
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                CompositionLocalProvider(
                    LocalContentColor provides MiuixTheme.colorScheme.onBackground,
                ) {
                    content()
                }
            },
        )
    }
}
// rebuild Fri Jul 24 12:36:39 CST 2026
