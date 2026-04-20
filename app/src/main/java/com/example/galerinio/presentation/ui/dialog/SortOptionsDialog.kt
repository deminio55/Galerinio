package com.example.galerinio.presentation.ui.dialog

import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.fragment.app.DialogFragment
import com.example.galerinio.R
import com.example.galerinio.data.util.PreferencesManager
import com.example.galerinio.databinding.DialogSortOptionsBinding
import com.example.galerinio.domain.model.SortOptions
import com.example.galerinio.domain.model.SortType
import com.example.galerinio.domain.model.GroupingType
import com.example.galerinio.presentation.ui.util.ThemeManager
import kotlinx.coroutines.runBlocking

class SortOptionsDialog : DialogFragment() {

    private var _binding: DialogSortOptionsBinding? = null
    private val binding get() = _binding!!

    private val currentOptions: SortOptions by lazy {
        val args = requireArguments()
        SortOptions(
            sortType = SortType.valueOf(args.getString(ARG_SORT_TYPE, SortType.DATE_TAKEN.name)),
            isDescending = args.getBoolean(ARG_IS_DESCENDING, true),
            groupingType = GroupingType.valueOf(args.getString(ARG_GROUPING_TYPE, GroupingType.NONE.name))
        )
    }

    private val isAlbumMode: Boolean by lazy {
        arguments?.getBoolean(ARG_IS_ALBUM_MODE, false) ?: false
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogSortOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onStart() {
        super.onStart()
        val window = dialog?.window ?: return
        val metrics = resources.displayMetrics
        val dialogWidth = (metrics.widthPixels * 0.94f).toInt()
        val dialogHeight = (metrics.heightPixels * 0.9f).toInt()
        window.setLayout(dialogWidth, dialogHeight)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSortOptions()
        setupGroupingOptions()
        setupButtons()

        loadCurrentOptions()
        applyRuntimeThemeColors()
    }

    private fun applyRuntimeThemeColors() {
        val prefs = PreferencesManager(requireContext())
        val isDarkMode = runBlocking { prefs.isDarkModeEnabled() }
        val palette = runBlocking { prefs.getThemePalette(isDarkMode) }
        val accent = runBlocking { prefs.getThemeAccent(isDarkMode) }
        val colors = ThemeManager.resolvePaletteColors(isDarkMode, palette)
        val accentColors = ThemeManager.resolveAccentColors(isDarkMode, accent)

        binding.sortDialogRoot.setBackgroundColor(colors.surface)
        dialog?.window?.setBackgroundDrawable(ColorDrawable(colors.surface))

        binding.tvSortSectionTitle.setTextColor(accentColors.accent)
        binding.tvGroupingSectionTitle.setTextColor(accentColors.accent)
        binding.viewGroupingDivider.setBackgroundColor(colors.divider)

        listOf(
            binding.sortByName,
            binding.sortByDateTaken,
            binding.sortByDateModified,
            binding.sortBySize,
            binding.sortByCustom,
            binding.groupingNone,
            binding.groupingByDay,
            binding.groupingByWeek,
            binding.groupingByMonth,
            binding.groupingByYear
        ).forEach { radio ->
            radio.setTextColor(colors.onSurface)
            radio.buttonTintList = ColorStateList.valueOf(accentColors.accent)
        }

        binding.sortDescending.setTextColor(colors.onSurface)
        binding.sortDescending.buttonTintList = ColorStateList.valueOf(accentColors.accent)
        binding.btnCancel.setTextColor(colors.onSurface)
        binding.btnApply.setTextColor(accentColors.accent)
    }

    private fun setupSortOptions() {
        binding.sortByCustom.visibility = if (isAlbumMode) View.VISIBLE else View.GONE

        binding.sortRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val isCustom = checkedId == R.id.sortByCustom
            binding.sortDescending.isEnabled = !isCustom
            if (isCustom) {
                binding.sortDescending.isChecked = false
            }
        }
    }

    private fun setupGroupingOptions() {
        if (isAlbumMode) {
            binding.groupingContainer.visibility = View.GONE
            binding.groupingNone.isChecked = true
        } else {
            binding.groupingContainer.visibility = View.VISIBLE
        }
    }

    private fun setupButtons() {
        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnApply.setOnClickListener {
            val selectedSortType = getSelectedSortType()
            val newOptions = SortOptions(
                sortType = selectedSortType,
                isDescending = if (selectedSortType == SortType.CUSTOM) false else binding.sortDescending.isChecked,
                groupingType = if (isAlbumMode) GroupingType.NONE else getSelectedGroupingType()
            )
            parentFragmentManager.setFragmentResult(
                REQUEST_KEY_SORT_OPTIONS,
                Bundle().apply {
                    putString(ARG_SORT_TYPE, newOptions.sortType.name)
                    putBoolean(ARG_IS_DESCENDING, newOptions.isDescending)
                    putString(ARG_GROUPING_TYPE, newOptions.groupingType.name)
                }
            )
            dismiss()
        }
    }

    private fun loadCurrentOptions() {
        // Set sort type
        when (currentOptions.sortType) {
            SortType.NAME -> binding.sortByName.isChecked = true
            SortType.DATE_TAKEN -> binding.sortByDateTaken.isChecked = true
            SortType.DATE_MODIFIED -> binding.sortByDateModified.isChecked = true
            SortType.SIZE -> binding.sortBySize.isChecked = true
            SortType.CUSTOM -> binding.sortByCustom.isChecked = true
        }

        // Set sort order
        binding.sortDescending.isChecked = currentOptions.isDescending
        binding.sortDescending.isEnabled = currentOptions.sortType != SortType.CUSTOM

        // Set grouping type
        when (currentOptions.groupingType) {
            GroupingType.NONE -> binding.groupingNone.isChecked = true
            GroupingType.DAY -> binding.groupingByDay.isChecked = true
            GroupingType.WEEK -> binding.groupingByWeek.isChecked = true
            GroupingType.MONTH -> binding.groupingByMonth.isChecked = true
            GroupingType.YEAR -> binding.groupingByYear.isChecked = true
        }
    }

    private fun getSelectedSortType(): SortType {
        return when (binding.sortRadioGroup.checkedRadioButtonId) {
            R.id.sortByName -> SortType.NAME
            R.id.sortByDateTaken -> SortType.DATE_TAKEN
            R.id.sortByDateModified -> SortType.DATE_MODIFIED
            R.id.sortBySize -> SortType.SIZE
            R.id.sortByCustom -> SortType.CUSTOM
            else -> SortType.DATE_TAKEN
        }
    }

    private fun getSelectedGroupingType(): GroupingType {
        return when (binding.groupingRadioGroup.checkedRadioButtonId) {
            R.id.groupingNone -> GroupingType.NONE
            R.id.groupingByDay -> GroupingType.DAY
            R.id.groupingByWeek -> GroupingType.WEEK
            R.id.groupingByMonth -> GroupingType.MONTH
            R.id.groupingByYear -> GroupingType.YEAR
            else -> GroupingType.NONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val REQUEST_KEY_SORT_OPTIONS = "request_key_sort_options"
        private const val ARG_SORT_TYPE = "arg_sort_type"
        private const val ARG_IS_DESCENDING = "arg_is_descending"
        private const val ARG_GROUPING_TYPE = "arg_grouping_type"
        private const val ARG_IS_ALBUM_MODE = "arg_is_album_mode"

        fun newInstance(currentOptions: SortOptions, isAlbumMode: Boolean): SortOptionsDialog {
            return SortOptionsDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_SORT_TYPE, currentOptions.sortType.name)
                    putBoolean(ARG_IS_DESCENDING, currentOptions.isDescending)
                    putString(ARG_GROUPING_TYPE, currentOptions.groupingType.name)
                    putBoolean(ARG_IS_ALBUM_MODE, isAlbumMode)
                }
            }
        }
    }
}

