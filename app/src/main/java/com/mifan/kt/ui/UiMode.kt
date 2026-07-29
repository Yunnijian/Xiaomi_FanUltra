package com.mifan.kt.ui

import androidx.compose.runtime.staticCompositionLocalOf

enum class UiMode { Miuix, Material }

val LocalUiMode = staticCompositionLocalOf { UiMode.Miuix }
