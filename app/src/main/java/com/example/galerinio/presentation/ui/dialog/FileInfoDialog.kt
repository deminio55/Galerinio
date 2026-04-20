package com.example.galerinio.presentation.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.example.galerinio.databinding.DialogFileInfoBinding
import com.example.galerinio.data.util.FileManager
import com.example.galerinio.domain.model.MediaModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FileInfoDialog : DialogFragment() {
    
    private var _binding: DialogFileInfoBinding? = null
    private val binding get() = _binding!!
    
    private var media: MediaModel? = null
    private lateinit var fileManager: FileManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            // media = it.getParcelable("media")
        }
        fileManager = FileManager(requireContext())
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogFileInfoBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        media?.let { mediaModel ->
            binding.apply {
                fileName.text = mediaModel.fileName
                filePath.text = mediaModel.filePath
                fileSize.text = fileManager.formatFileSize(mediaModel.size)
                mimeType.text = mediaModel.mimeType
                
                val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
                dateModified.text = dateFormat.format(Date(mediaModel.dateModified))
                
                dimensions.text = "${mediaModel.width}x${mediaModel.height}"
                
                if (mediaModel.isVideo) {
                    durationLabel.visibility = View.VISIBLE
                    duration.visibility = View.VISIBLE
                    val minutes = mediaModel.duration / 60000
                    val seconds = (mediaModel.duration % 60000) / 1000
                    duration.text = String.format("%02d:%02d", minutes, seconds)
                } else {
                    durationLabel.visibility = View.GONE
                    duration.visibility = View.GONE
                }
                
                closeButton.setOnClickListener {
                    dismiss()
                }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

