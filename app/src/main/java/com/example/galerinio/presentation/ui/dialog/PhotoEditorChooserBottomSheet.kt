package com.example.galerinio.presentation.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.example.galerinio.R
import com.example.galerinio.data.util.PreferencesManager
import com.example.galerinio.databinding.DialogPhotoEditorChooserBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class PhotoEditorChooserBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogPhotoEditorChooserBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogPhotoEditorChooserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val initialEditor = parseEditor(
            arguments?.getString(ARG_INITIAL_EDITOR)
        )

        binding.radioInAppEditor.isChecked = initialEditor == PreferencesManager.PhotoEditorChoice.IN_APP
        binding.radioSystemEditor.isChecked = initialEditor == PreferencesManager.PhotoEditorChoice.SYSTEM

        binding.btnUseOnce.setOnClickListener {
            publishSelection(alwaysUse = false)
        }
        binding.btnUseAlways.setOnClickListener {
            publishSelection(alwaysUse = true)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun publishSelection(alwaysUse: Boolean) {
        val selectedEditor = when (binding.radioGroupEditors.checkedRadioButtonId) {
            R.id.radioSystemEditor -> PreferencesManager.PhotoEditorChoice.SYSTEM
            else -> PreferencesManager.PhotoEditorChoice.IN_APP
        }

        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply {
                putString(RESULT_EDITOR, selectedEditor.name)
                putBoolean(RESULT_ALWAYS, alwaysUse)
            }
        )
        dismissAllowingStateLoss()
    }

    private fun parseEditor(raw: String?): PreferencesManager.PhotoEditorChoice {
        return runCatching { PreferencesManager.PhotoEditorChoice.valueOf(raw.orEmpty()) }
            .getOrDefault(PreferencesManager.PhotoEditorChoice.IN_APP)
    }

    companion object {
        const val REQUEST_KEY = "photo_editor_chooser_request"
        const val RESULT_EDITOR = "photo_editor_result_editor"
        const val RESULT_ALWAYS = "photo_editor_result_always"
        private const val ARG_INITIAL_EDITOR = "arg_initial_editor"

        fun newInstance(initialEditor: PreferencesManager.PhotoEditorChoice): PhotoEditorChooserBottomSheet {
            return PhotoEditorChooserBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_INITIAL_EDITOR, initialEditor.name)
                }
            }
        }
    }
}

