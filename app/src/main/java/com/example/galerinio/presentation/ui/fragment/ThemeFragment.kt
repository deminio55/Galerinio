package com.example.galerinio.presentation.ui.fragment

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.galerinio.R
import com.example.galerinio.data.util.PreferencesManager
import com.example.galerinio.databinding.FragmentThemeBinding
import com.example.galerinio.presentation.ui.activity.MainActivity
import com.example.galerinio.presentation.ui.util.ThemeManager
import kotlinx.coroutines.launch

class ThemeFragment : Fragment() {

    private var _binding: FragmentThemeBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferencesManager: PreferencesManager
    private var isBindingState = false
    private var isApplyingThemeChange = false
    private val accentOptions = listOf(
        PreferencesManager.ThemeAccent.BLUE,
        PreferencesManager.ThemeAccent.GREEN,
        PreferencesManager.ThemeAccent.ORANGE,
        PreferencesManager.ThemeAccent.PURPLE,
        PreferencesManager.ThemeAccent.ROSE
    )
    private val paletteOptions = listOf(
        PreferencesManager.ThemePalette.DEFAULT,
        PreferencesManager.ThemePalette.GRAPHITE,
        PreferencesManager.ThemePalette.FOREST,
        PreferencesManager.ThemePalette.SAND,
        PreferencesManager.ThemePalette.LAVENDER
    )
    private lateinit var lightPaletteViews: List<View>
    private lateinit var darkPaletteViews: List<View>
    private lateinit var lightAccentViews: List<View>
    private lateinit var darkAccentViews: List<View>
    private var currentLightPalette = PreferencesManager.ThemePalette.DEFAULT
    private var currentDarkPalette = PreferencesManager.ThemePalette.DEFAULT
    private var currentLightAccent = PreferencesManager.ThemeAccent.BLUE
    private var currentDarkAccent = PreferencesManager.ThemeAccent.BLUE

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentThemeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferencesManager = PreferencesManager(requireContext())
        bindColorOptionViews()
        setupColorOptionListeners()
        bindCurrentTheme()
        setupListeners()
    }

    private fun bindColorOptionViews() {
        lightPaletteViews = listOf(
            binding.lightPaletteOption0,
            binding.lightPaletteOption1,
            binding.lightPaletteOption2,
            binding.lightPaletteOption3,
            binding.lightPaletteOption4
        )
        darkPaletteViews = listOf(
            binding.darkPaletteOption0,
            binding.darkPaletteOption1,
            binding.darkPaletteOption2,
            binding.darkPaletteOption3,
            binding.darkPaletteOption4
        )
        lightAccentViews = listOf(
            binding.lightAccentOption0,
            binding.lightAccentOption1,
            binding.lightAccentOption2,
            binding.lightAccentOption3,
            binding.lightAccentOption4
        )
        darkAccentViews = listOf(
            binding.darkAccentOption0,
            binding.darkAccentOption1,
            binding.darkAccentOption2,
            binding.darkAccentOption3,
            binding.darkAccentOption4
        )
    }

    private fun setupColorOptionListeners() {
        lightPaletteViews.forEachIndexed { index, view ->
            view.setOnClickListener { onPaletteSelected(isDarkPalette = false, position = index) }
        }
        darkPaletteViews.forEachIndexed { index, view ->
            view.setOnClickListener { onPaletteSelected(isDarkPalette = true, position = index) }
        }
        lightAccentViews.forEachIndexed { index, view ->
            view.setOnClickListener { onAccentSelected(isDarkPalette = false, position = index) }
        }
        darkAccentViews.forEachIndexed { index, view ->
            view.setOnClickListener { onAccentSelected(isDarkPalette = true, position = index) }
        }
    }

    private fun bindCurrentTheme() {
        viewLifecycleOwner.lifecycleScope.launch {
            isBindingState = true
            val isDarkMode = preferencesManager.isDarkModeEnabled()
            val lightPalette = preferencesManager.getThemePalette(isDarkMode = false)
            val darkPalette = preferencesManager.getThemePalette(isDarkMode = true)
            val lightAccent = preferencesManager.getThemeAccent(isDarkMode = false)
            val darkAccent = preferencesManager.getThemeAccent(isDarkMode = true)
            currentLightPalette = lightPalette
            currentDarkPalette = darkPalette
            currentLightAccent = lightAccent
            currentDarkAccent = darkAccent
            binding.radioThemeDark.isChecked = isDarkMode
            binding.radioThemeLight.isChecked = !isDarkMode
            renderPaletteRow(isDarkPalette = false, selected = lightPalette)
            renderPaletteRow(isDarkPalette = true, selected = darkPalette)
            renderAccentRow(isDarkPalette = false, selected = lightAccent)
            renderAccentRow(isDarkPalette = true, selected = darkAccent)
            updateModeAvailability(isDarkMode)
            applyThemeMenuColors(isDarkMode)
            isBindingState = false
        }
    }

    private fun setupListeners() {
        binding.rgThemeMode.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingState || isApplyingThemeChange) return@setOnCheckedChangeListener
            if (checkedId != R.id.radioThemeDark && checkedId != R.id.radioThemeLight) return@setOnCheckedChangeListener

            val enableDarkMode = checkedId == R.id.radioThemeDark
            val targetMode = if (enableDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            updateModeAvailability(enableDarkMode)
            applyThemeMenuColors(enableDarkMode)

            viewLifecycleOwner.lifecycleScope.launch {
                isApplyingThemeChange = true
                try {
                    val prefAlreadySet = preferencesManager.isDarkModeEnabled() == enableDarkMode
                    val modeAlreadySet = AppCompatDelegate.getDefaultNightMode() == targetMode
                    if (prefAlreadySet && modeAlreadySet) return@launch

                    // First persist the new choice, then trigger UI mode recreation.
                    preferencesManager.setDarkMode(enableDarkMode)
                    if (!modeAlreadySet) {
                        (activity as? MainActivity)?.applyNightModeKeepingThemeDrawer(targetMode)
                            ?: AppCompatDelegate.setDefaultNightMode(targetMode)
                    }
                } finally {
                    isApplyingThemeChange = false
                }
            }
        }
    }

    private fun onPaletteSelected(isDarkPalette: Boolean, position: Int) {
        if (isBindingState || isApplyingThemeChange) return
        if (isDarkPalette != isDarkModeSelected()) return
        val palette = paletteOptions.getOrNull(position) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val currentPalette = preferencesManager.getThemePalette(isDarkPalette)
            if (currentPalette == palette) return@launch

            preferencesManager.setThemePalette(isDarkPalette, palette)
            if (isDarkPalette) currentDarkPalette = palette else currentLightPalette = palette
            renderPaletteRow(isDarkPalette, palette)
            val activeDarkMode = preferencesManager.isDarkModeEnabled()
            applyThemeMenuColors(activeDarkMode)
            if (activeDarkMode == isDarkPalette) {
                (activity as? MainActivity)?.recreateKeepingThemeDrawer()
                    ?: requireActivity().recreate()
            }
        }
    }

    private fun onAccentSelected(isDarkPalette: Boolean, position: Int) {
        if (isBindingState || isApplyingThemeChange) return
        if (isDarkPalette != isDarkModeSelected()) return
        val accent = accentOptions.getOrNull(position) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val currentAccent = preferencesManager.getThemeAccent(isDarkPalette)
            if (currentAccent == accent) return@launch

            preferencesManager.setThemeAccent(isDarkPalette, accent)
            if (isDarkPalette) currentDarkAccent = accent else currentLightAccent = accent
            renderAccentRow(isDarkPalette, accent)
            val activeDarkMode = preferencesManager.isDarkModeEnabled()
            applyThemeMenuColors(activeDarkMode)
            if (activeDarkMode == isDarkPalette) {
                (activity as? MainActivity)?.recreateKeepingThemeDrawer()
                    ?: requireActivity().recreate()
            }
        }
    }

    private fun renderPaletteRow(isDarkPalette: Boolean, selected: PreferencesManager.ThemePalette) {
        val views = if (isDarkPalette) darkPaletteViews else lightPaletteViews
        val onSurface = ThemeManager.resolvePaletteColors(isDarkPalette, selected).onSurface
        views.forEachIndexed { index, view ->
            val palette = paletteOptions[index]
            val color = ThemeManager.resolvePaletteColors(isDarkPalette, palette).background
            applyColorCircle(
                target = view,
                fillColor = color,
                isSelected = (palette == selected),
                strokeColor = onSurface
            )
        }
    }

    private fun renderAccentRow(isDarkPalette: Boolean, selected: PreferencesManager.ThemeAccent) {
        val views = if (isDarkPalette) darkAccentViews else lightAccentViews
        val onSurface = ThemeManager.resolvePaletteColors(isDarkPalette, PreferencesManager.ThemePalette.DEFAULT).onSurface
        views.forEachIndexed { index, view ->
            val accent = accentOptions[index]
            val color = ThemeManager.resolveAccentColors(isDarkPalette, accent).accent
            applyColorCircle(
                target = view,
                fillColor = color,
                isSelected = (accent == selected),
                strokeColor = onSurface
            )
        }
    }

    private fun isDarkModeSelected(): Boolean = binding.radioThemeDark.isChecked

    private fun updateModeAvailability(isDarkMode: Boolean) {
        setGroupEnabled(lightPaletteViews + lightAccentViews, enabled = !isDarkMode)
        setGroupEnabled(darkPaletteViews + darkAccentViews, enabled = isDarkMode)

        binding.lightSectionTitle.alpha = if (!isDarkMode) 1f else 0.5f
        binding.lightBaseLabel.alpha = if (!isDarkMode) 1f else 0.5f
        binding.lightAccentLabel.alpha = if (!isDarkMode) 1f else 0.5f
        binding.lightPaletteRow.alpha = if (!isDarkMode) 1f else 0.5f
        binding.lightAccentRow.alpha = if (!isDarkMode) 1f else 0.5f

        binding.darkSectionTitle.alpha = if (isDarkMode) 1f else 0.5f
        binding.darkBaseLabel.alpha = if (isDarkMode) 1f else 0.5f
        binding.darkAccentLabel.alpha = if (isDarkMode) 1f else 0.5f
        binding.darkPaletteRow.alpha = if (isDarkMode) 1f else 0.5f
        binding.darkAccentRow.alpha = if (isDarkMode) 1f else 0.5f
    }

    private fun setGroupEnabled(views: List<View>, enabled: Boolean) {
        views.forEach { view ->
            view.isEnabled = enabled
            view.isClickable = enabled
        }
    }

    private fun applyThemeMenuColors(isDarkMode: Boolean) {
        val palette = if (isDarkMode) currentDarkPalette else currentLightPalette
        val colors = ThemeManager.resolvePaletteColors(isDarkMode, palette)

        binding.themeRoot.setBackgroundColor(colors.drawerSurface)
        binding.themeTitle.setTextColor(colors.onSurface)
        binding.themeDescription.setTextColor(colors.onSurfaceSecondary)

        listOf(
            binding.lightSectionTitle,
            binding.darkSectionTitle
        ).forEach { it.setTextColor(colors.onSurface) }

        listOf(
            binding.lightBaseLabel,
            binding.lightAccentLabel,
            binding.darkBaseLabel,
            binding.darkAccentLabel
        ).forEach { it.setTextColor(colors.onSurfaceSecondary) }

        binding.radioThemeLight.setTextColor(colors.onSurface)
        binding.radioThemeDark.setTextColor(colors.onSurface)
    }

    private fun applyColorCircle(
        target: View,
        fillColor: Int,
        isSelected: Boolean,
        strokeColor: Int
    ) {
        val density = resources.displayMetrics.density
        val strokePx = if (isSelected) (3f * density).toInt() else (1f * density).toInt().coerceAtLeast(1)
        target.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            val resolvedStroke = if (isSelected) strokeColor else ColorUtils.setAlphaComponent(strokeColor, 90)
            setStroke(strokePx, resolvedStroke)
        }
        target.alpha = if (isSelected) 1f else 0.86f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

