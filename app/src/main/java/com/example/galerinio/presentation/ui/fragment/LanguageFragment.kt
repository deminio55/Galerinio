package com.example.galerinio.presentation.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import com.example.galerinio.R

class LanguageFragment : Fragment() {

    private data class LangOption(val viewId: Int, val tag: String)

    private val languages = listOf(
        LangOption(R.id.langSystem, ""),
        LangOption(R.id.langEn, "en"),
        LangOption(R.id.langDe, "de"),
        LangOption(R.id.langFr, "fr"),
        LangOption(R.id.langEs, "es"),
        LangOption(R.id.langPt, "pt"),
        LangOption(R.id.langIt, "it"),
        LangOption(R.id.langNl, "nl"),
        LangOption(R.id.langEl, "el"),
        LangOption(R.id.langPl, "pl"),
        LangOption(R.id.langRu, "ru"),
        LangOption(R.id.langUk, "uk"),
        LangOption(R.id.langJa, "ja"),
        LangOption(R.id.langZh, "zh"),
        LangOption(R.id.langAr, "ar")
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_language, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val radioGroup = view.findViewById<RadioGroup>(R.id.languageRadioGroup)

        // Determine current locale tag
        val currentLocales = AppCompatDelegate.getApplicationLocales()
        val currentTag = if (currentLocales.isEmpty) "" else currentLocales.toLanguageTags()

        // Select current
        val matchedId = languages.firstOrNull { lo ->
            if (lo.tag.isEmpty()) currentTag.isEmpty()
            else currentTag.startsWith(lo.tag, ignoreCase = true)
        }?.viewId ?: R.id.langSystem

        radioGroup.check(matchedId)

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val selected = languages.firstOrNull { it.viewId == checkedId } ?: return@setOnCheckedChangeListener
            val locales = if (selected.tag.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(selected.tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}

