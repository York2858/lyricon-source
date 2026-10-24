/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.app.compose.theme

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.proify.android.extensions.defaultSharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.darkColorScheme as materialDarkColorScheme
import androidx.compose.material3.lightColorScheme as materialLightColorScheme
import io.github.proify.lyricon.app.compose.MaterialPalette
import io.github.proify.lyricon.app.util.AppThemeUtils
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.theme.platformDynamicColors

object CurrentThemeConfigs {
    var isDark: Boolean = false
    var primary: Color = Color.Transparent
    var primaryContainer: Color = Color.Transparent
}

@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    val activity = view.context as? Activity
    val revision = rememberThemeRevision()
    val configuration = LocalConfiguration.current
    // Dynamic palette providers may remember resource colors; re-read them on theme and wallpaper changes.
    // Keep content outside this key so search, selections and open sheets survive palette updates.
    val colors = key(revision, configuration) { rememberAppColors() }
    CurrentThemeConfigs.isDark = colors.isDark

    SideEffect {
        activity?.window?.let { window ->
            WindowInsetsControllerCompat(window, view)
                .isAppearanceLightStatusBars = !colors.isDark
        }
    }

    MiuixTheme(
        colors = colors.colors,
        content = {
            CurrentThemeConfigs.primary = MiuixTheme.colorScheme.primary
            CurrentThemeConfigs.primaryContainer = MiuixTheme.colorScheme.primaryContainer
            val palette = colors.colors
            val material = (if (colors.isDark) materialDarkColorScheme() else materialLightColorScheme()).copy(
                primary = palette.primary, onPrimary = palette.onPrimary,
                primaryContainer = palette.primaryContainer, onPrimaryContainer = palette.onPrimaryContainer,
                background = palette.surface, onBackground = palette.onSurface,
                surface = palette.surfaceContainer, onSurface = palette.onSurface,
                surfaceVariant = palette.surfaceContainer, onSurfaceVariant = palette.onSurfaceVariantSummary,
                surfaceContainer = palette.surfaceContainer, surfaceContainerLow = palette.surface,
                surfaceContainerHigh = palette.surfaceContainer, surfaceContainerHighest = palette.surfaceContainer,
                outline = palette.onSurfaceVariantSummary.copy(alpha = .45f), outlineVariant = palette.onSurface.copy(alpha = .10f),
                secondary = palette.secondary, onSecondary = palette.onSecondary,
                secondaryContainer = palette.secondaryContainer, onSecondaryContainer = palette.onSecondaryContainer,
                tertiary = palette.primary, onTertiary = palette.onPrimary,
                error = palette.error, onError = palette.onError, errorContainer = palette.errorContainer, onErrorContainer = palette.onErrorContainer,
            )
            MaterialTheme(colorScheme = material) {
                CompositionLocalProvider(
                    top.yukonga.miuix.kmp.theme.LocalContentColor provides palette.onSurface,
                    androidx.compose.material3.LocalContentColor provides palette.onSurface,
                ) { content() }
            }
        }
    )
}

@Composable
private fun rememberThemeRevision(): Int {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, lifecycle) {
        val preferences = context.defaultSharedPreferences
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, name ->
            if (name == AppThemeUtils.KEY_THEME_MODE || name == AppThemeUtils.KEY_MONET_COLOR) revision++
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { revision++ }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        lifecycle.addObserver(observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), ContextCompat.RECEIVER_EXPORTED)
        onDispose {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycle.removeObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return revision
}

@Composable
private fun rememberAppColors(): AppColors {
    val context = LocalContext.current
    val dark = resolveDarkMode(context)

    return when {
        AppThemeUtils.isEnableMonet(context) -> {
            val base = platformDynamicColors(dark)
            val actual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val system = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                base.copy(
                    primary = system.primary, onPrimary = system.onPrimary,
                    primaryVariant = system.primaryContainer, onPrimaryVariant = system.onPrimaryContainer,
                    primaryContainer = system.primaryContainer, onPrimaryContainer = system.onPrimaryContainer,
                    secondary = system.secondary, onSecondary = system.onSecondary,
                    secondaryVariant = system.secondaryContainer, onSecondaryVariant = system.onSecondaryContainer,
                    secondaryContainer = system.secondaryContainer, onSecondaryContainer = system.onSecondaryContainer,
                    tertiaryContainer = system.tertiaryContainer, onTertiaryContainer = system.onTertiaryContainer,
                    background = system.background, onBackground = system.onBackground,
                    surface = system.surface, onSurface = system.onSurface, surfaceVariant = system.surfaceVariant,
                    surfaceContainer = system.surfaceContainerLow, onSurfaceContainer = system.onSurface,
                    onSurfaceSecondary = system.onSurfaceVariant, onSurfaceVariantSummary = system.onSurfaceVariant,
                    onSurfaceVariantActions = system.onSurfaceVariant, dividerLine = system.outlineVariant,
                    error = system.error, onError = system.onError,
                    errorContainer = system.errorContainer, onErrorContainer = system.onErrorContainer,
                )
            } else base
            AppColors(actual, dark)
        }

        dark ->
            AppColors(appDarkColorScheme(), true)

        else ->
            AppColors(appLightColorScheme(), false)
    }
}

@Composable
fun resolveDarkMode(context: android.content.Context): Boolean =
    when (AppThemeUtils.getMode(context)) {
        AppThemeUtils.MODE_LIGHT -> false
        AppThemeUtils.MODE_DARK -> true
        AppThemeUtils.MODE_SYSTEM -> isSystemInDarkTheme()
        else -> isSystemInDarkTheme()
    }

class AppColors(
    val colors: Colors,
    val isDark: Boolean
)

fun appDarkColorScheme(): Colors = darkColorScheme(
    error = MaterialPalette.Red.Primary,
    errorContainer = MaterialPalette.Red.Hue200,
)

fun appLightColorScheme(): Colors {
    val black = Color(0xFF111111)
    return lightColorScheme(
        surface = Color(0xFFF0F1F2),
        onBackground = black,
        onSurface = black,
        onSurfaceContainer = black,
        error = MaterialPalette.Red.Primary,
        errorContainer = MaterialPalette.Red.Hue200,
    )
}
