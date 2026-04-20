package com.example.galerinio.presentation.ui.util

import android.content.res.ColorStateList
import androidx.core.graphics.ColorUtils
import com.example.galerinio.data.util.PreferencesManager

object ThemeManager {
    data class PaletteColors(
        val background: Int,
        val surface: Int,
        val drawerSurface: Int,
        val onSurface: Int,
        val onSurfaceSecondary: Int,
        val divider: Int
    )

    data class AccentColors(
        val accent: Int,
        val onAccent: Int
    )

    fun resolvePaletteColors(
        isDarkMode: Boolean,
        palette: PreferencesManager.ThemePalette
    ): PaletteColors {
        return if (isDarkMode) {
            when (palette) {
                PreferencesManager.ThemePalette.DEFAULT -> palette(
                    bg = 0xFF1A1A1AL.toInt(),
                    surface = 0xFF1A1A1AL.toInt(),
                    drawer = 0xFF3C3C3CL.toInt()
                )
                PreferencesManager.ThemePalette.GRAPHITE -> palette(
                    bg = 0xFF121417L.toInt(),
                    surface = 0xFF1B1F24L.toInt(),
                    drawer = 0xFF232931L.toInt()
                )
                PreferencesManager.ThemePalette.FOREST -> palette(
                    bg = 0xFF111C17L.toInt(),
                    surface = 0xFF1A2821L.toInt(),
                    drawer = 0xFF22342BL.toInt()
                )
                PreferencesManager.ThemePalette.SAND -> palette(
                    bg = 0xFF211B14L.toInt(),
                    surface = 0xFF2A231BL.toInt(),
                    drawer = 0xFF332A20L.toInt()
                )
                PreferencesManager.ThemePalette.LAVENDER -> palette(
                    bg = 0xFF191626L.toInt(),
                    surface = 0xFF241F36L.toInt(),
                    drawer = 0xFF2D2742L.toInt()
                )
            }
        } else {
            when (palette) {
                PreferencesManager.ThemePalette.DEFAULT -> palette(
                    bg = 0xFFFFFFFF.toInt(),
                    surface = 0xFFFFFFFF.toInt(),
                    drawer = 0xFFFFFFFF.toInt()
                )
                PreferencesManager.ThemePalette.GRAPHITE -> palette(
                    bg = 0xFFF4F6F8.toInt(),
                    surface = 0xFFF8FAFC.toInt(),
                    drawer = 0xFFEFF2F6.toInt()
                )
                PreferencesManager.ThemePalette.FOREST -> palette(
                    bg = 0xFFF2F8F3.toInt(),
                    surface = 0xFFF4FAF5.toInt(),
                    drawer = 0xFFEAF4ED.toInt()
                )
                PreferencesManager.ThemePalette.SAND -> palette(
                    bg = 0xFFFCF6EC.toInt(),
                    surface = 0xFFFFF8F0.toInt(),
                    drawer = 0xFFF7EEDD.toInt()
                )
                PreferencesManager.ThemePalette.LAVENDER -> palette(
                    bg = 0xFFF7F4FF.toInt(),
                    surface = 0xFFFAF7FF.toInt(),
                    drawer = 0xFFF1ECFF.toInt()
                )
            }
        }
    }

    fun bestTextColor(background: Int): Int {
        return if (ColorUtils.calculateLuminance(background) > 0.5) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    fun drawerTextStateList(primary: Int): ColorStateList {
        val disabled = ColorUtils.setAlphaComponent(primary, 140)
        return ColorStateList(
            arrayOf(
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf(android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(disabled, primary, primary)
        )
    }

    fun resolveAccentColors(
        isDarkMode: Boolean,
        accent: PreferencesManager.ThemeAccent
    ): AccentColors {
        return if (isDarkMode) {
            when (accent) {
                PreferencesManager.ThemeAccent.BLUE -> AccentColors(0xFF64B5F6.toInt(), 0xFF000000.toInt())
                PreferencesManager.ThemeAccent.GREEN -> AccentColors(0xFF81C784.toInt(), 0xFF000000.toInt())
                PreferencesManager.ThemeAccent.ORANGE -> AccentColors(0xFFFFB74D.toInt(), 0xFF000000.toInt())
                PreferencesManager.ThemeAccent.PURPLE -> AccentColors(0xFFCE93D8.toInt(), 0xFF000000.toInt())
                PreferencesManager.ThemeAccent.ROSE -> AccentColors(0xFFF48FB1.toInt(), 0xFF000000.toInt())
            }
        } else {
            when (accent) {
                PreferencesManager.ThemeAccent.BLUE -> AccentColors(0xFF2196F3.toInt(), 0xFFFFFFFF.toInt())
                PreferencesManager.ThemeAccent.GREEN -> AccentColors(0xFF2E7D32.toInt(), 0xFFFFFFFF.toInt())
                PreferencesManager.ThemeAccent.ORANGE -> AccentColors(0xFFEF6C00.toInt(), 0xFFFFFFFF.toInt())
                PreferencesManager.ThemeAccent.PURPLE -> AccentColors(0xFF6A1B9A.toInt(), 0xFFFFFFFF.toInt())
                PreferencesManager.ThemeAccent.ROSE -> AccentColors(0xFFC2185B.toInt(), 0xFFFFFFFF.toInt())
            }
        }
    }

    private fun palette(bg: Int, surface: Int, drawer: Int): PaletteColors {
        val onSurface = bestTextColor(surface)
        val onSurfaceSecondary = ColorUtils.setAlphaComponent(onSurface, 179)
        val dividerBase = bestTextColor(drawer)
        val divider = ColorUtils.setAlphaComponent(dividerBase, 40)
        return PaletteColors(
            background = bg,
            surface = surface,
            drawerSurface = drawer,
            onSurface = onSurface,
            onSurfaceSecondary = onSurfaceSecondary,
            divider = divider
        )
    }
}

