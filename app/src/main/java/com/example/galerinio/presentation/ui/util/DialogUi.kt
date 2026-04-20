package com.example.galerinio.presentation.ui.util

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.widget.TextView
import com.example.galerinio.R
import com.example.galerinio.data.util.PreferencesManager
import kotlinx.coroutines.runBlocking

object DialogUi {
    fun showWithReadableButtons(
        builder: AlertDialog.Builder,
        context: Context,
        applyDrawerNightStyle: Boolean = false
    ): AlertDialog {
        val dialog = builder.create()
        dialog.setOnShowListener {
            val preferences = PreferencesManager(context)
            val isDarkMode = runBlocking { preferences.isDarkModeEnabled() }
            val palette = runBlocking { preferences.getThemePalette(isDarkMode) }
            val colors = ThemeManager.resolvePaletteColors(isDarkMode, palette)

            if (applyDrawerNightStyle) {
                val bgColor = colors.drawerSurface
                dialog.window?.setBackgroundDrawable(ColorDrawable(bgColor))

                dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(colors.onSurfaceSecondary)

                val titleId = context.resources.getIdentifier("alertTitle", "id", "android")
                if (titleId != 0) {
                    dialog.findViewById<TextView>(titleId)?.setTextColor(colors.onSurface)
                }
            }

            val color = colors.onSurface
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(color)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(color)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(color)
        }
        dialog.show()
        return dialog
    }
}

