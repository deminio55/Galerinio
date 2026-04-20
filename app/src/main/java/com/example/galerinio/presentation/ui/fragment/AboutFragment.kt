package com.example.galerinio.presentation.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.galerinio.R
import com.example.galerinio.databinding.FragmentInfoPlaceholderBinding

class AboutFragment : Fragment() {

    private var _binding: FragmentInfoPlaceholderBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentInfoPlaceholderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Title is displayed in drawer header by MainActivity
        binding.tvInfo.text = getString(R.string.about_placeholder)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

