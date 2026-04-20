package com.example.galerinio.presentation.ui.fragment

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.galerinio.R
import com.example.galerinio.data.local.GalerioDatabase
import com.example.galerinio.data.repository.MediaRepositoryImpl
import com.example.galerinio.data.repository.TrashRepositoryImpl
import com.example.galerinio.data.util.PreferencesManager
import com.example.galerinio.databinding.FragmentTrashBinding
import com.example.galerinio.domain.model.TrashModel
import com.example.galerinio.presentation.adapter.TrashAdapter
import com.example.galerinio.presentation.ui.activity.MainActivity
import com.example.galerinio.presentation.ui.util.DialogUi
import com.example.galerinio.presentation.ui.util.ThemeManager
import kotlinx.coroutines.launch

class TrashFragment : Fragment() {

    private var _binding: FragmentTrashBinding? = null
    private val binding get() = _binding!!

    private lateinit var trashAdapter: TrashAdapter
    private lateinit var trashRepository: TrashRepositoryImpl
    private lateinit var mediaRepository: MediaRepositoryImpl
    private lateinit var prefsManager: PreferencesManager
    private val cleanupDayOptions = listOf(7, 14, 30, 60, 90)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTrashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // binding.toolbar.setNavigationOnClickListener {
        //     (activity as? MainActivity)?.onBackPressedDispatcher?.onBackPressed()
        // }

        prefsManager = PreferencesManager(requireContext())

        val db = GalerioDatabase.getInstance(requireContext())
        trashRepository = TrashRepositoryImpl(
            db.trashDao(),
            requireContext().filesDir.resolve("trash_media"),
            requireContext().applicationContext
        )
        mediaRepository = MediaRepositoryImpl(db.mediaDao())

        setupList()
        setupSettingsSection()
        observeTrash()
        observeSettings()
        setupActions()
        viewLifecycleOwner.lifecycleScope.launch { applyRuntimeTrashAccentTint() }
    }

    private fun setupList() {
        trashAdapter = TrashAdapter(
            onRestoreClick = { item -> confirmRestore(item) },
            onDeleteForeverClick = { item -> confirmDeleteForever(item) },
            onSelectionChanged = { ids -> updateBatchBar(ids) }
        )
        binding.recyclerTrash.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = trashAdapter
        }
    }

    private fun updateBatchBar(selectedIds: Set<Long>) {
        val inSelection = trashAdapter.isSelectionMode
        // binding.normalBar.visibility = if (inSelection) View.GONE else View.VISIBLE
        binding.batchActionBar.visibility = if (inSelection) View.VISIBLE else View.GONE
        binding.tvBatchCount.text = getString(R.string.n_selected, selectedIds.size)
        binding.trashSettings.visibility = if (inSelection) View.GONE else View.VISIBLE
    }

    private fun observeTrash() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                trashRepository.getTrashItems().collect { items ->
                    trashAdapter.submitList(items)
                    binding.tvEmptyTrash.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                    binding.btnEmptyTrash.isEnabled = items.isNotEmpty()
                }
            }
        }
    }

    private fun setupSettingsSection() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item,
            cleanupDayOptions.map { getString(R.string.settings_days_label, it) })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCleanupDays.adapter = adapter

        binding.switchAutoCleanup.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                prefsManager.setTrashAutoCleanupEnabled(isChecked)
            }
        }

        binding.spinnerCleanupDays.setOnItemSelectedListener(object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                lifecycleScope.launch {
                    prefsManager.setTrashAutoCleanupDays(cleanupDayOptions[pos])
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

    }

    private fun observeSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                prefsManager.trashAutoCleanupEnabledFlow.collect { enabled ->
                    binding.switchAutoCleanup.isChecked = enabled
                    binding.layoutCleanupDays.alpha = if (enabled) 1f else 0.4f
                    binding.spinnerCleanupDays.isEnabled = enabled
                    applyRuntimeTrashAccentTint()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                prefsManager.trashAutoCleanupDaysFlow.collect { days ->
                    val idx = cleanupDayOptions.indexOf(days).takeIf { it >= 0 } ?: 2
                    binding.spinnerCleanupDays.setSelection(idx)
                    applyRuntimeTrashAccentTint()
                }
            }
        }
    }

    private suspend fun applyRuntimeTrashAccentTint() {
        if (_binding == null) return
        val isDarkMode = prefsManager.isDarkModeEnabled()
        val palette = prefsManager.getThemePalette(isDarkMode)
        val colors = ThemeManager.resolvePaletteColors(isDarkMode, palette)
        val accent = prefsManager.getThemeAccent(isDarkMode)
        val accentColor = ThemeManager.resolveAccentColors(isDarkMode, accent).accent

        val thumbState = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accentColor, ColorUtils.setAlphaComponent(colors.onSurface, 170))
        )
        val trackState = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(accentColor, 130), ColorUtils.setAlphaComponent(colors.onSurface, 70))
        )

        binding.switchAutoCleanup.thumbTintList = thumbState
        binding.switchAutoCleanup.trackTintList = trackState
        binding.spinnerCleanupDays.backgroundTintList = ColorStateList.valueOf(accentColor)
    }

    private fun setupActions() {
        binding.btnEmptyTrash.setOnClickListener {
            val builder = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.empty_trash))
                .setMessage(getString(R.string.empty_trash_confirm))
                .setPositiveButton(getString(R.string.delete_forever)) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val removed = trashRepository.emptyTrash()
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.trash_cleared_count, removed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
            DialogUi.showWithReadableButtons(builder, requireContext())
        }

        binding.btnBatchCancel.setOnClickListener {
            trashAdapter.exitSelectionMode()
        }

        binding.btnBatchSelectAll.setOnClickListener {
            trashAdapter.selectAll()
        }

        binding.btnBatchRestore.setOnClickListener {
            val ids = trashAdapter.getSelectedIds().toList()
            if (ids.isEmpty()) return@setOnClickListener
            val builder = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.restore))
                .setMessage(getString(R.string.restore_batch_confirm, ids.size))
                .setPositiveButton(getString(R.string.restore)) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val (restored, failed) = trashRepository.restoreBatch(ids)
                        trashAdapter.exitSelectionMode()
                        val msg = if (failed == 0)
                            getString(R.string.batch_restored_success, restored)
                        else
                            getString(R.string.batch_restored_partial, restored, failed)
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
            DialogUi.showWithReadableButtons(builder, requireContext())
        }

        binding.btnBatchDeleteForever.setOnClickListener {
            val ids = trashAdapter.getSelectedIds().toList()
            if (ids.isEmpty()) return@setOnClickListener
            val builder = AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.delete_forever))
                .setMessage(getString(R.string.delete_forever_batch_confirm, ids.size))
                .setPositiveButton(getString(R.string.delete_forever)) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val deleted = trashRepository.deleteForeverBatch(ids)
                        trashAdapter.exitSelectionMode()
                        Toast.makeText(requireContext(), getString(R.string.batch_deleted_forever, deleted), Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(getString(R.string.cancel), null)
            DialogUi.showWithReadableButtons(builder, requireContext())
        }
    }

    private fun confirmRestore(item: TrashModel) {
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.restore))
            .setMessage(getString(R.string.restore_confirm, item.fileName))
            .setPositiveButton(getString(R.string.restore)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val restored = trashRepository.restore(item.id)
                    if (restored) {
                        Toast.makeText(requireContext(), getString(R.string.restored_success), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.restore_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
        DialogUi.showWithReadableButtons(builder, requireContext())
    }

    private fun confirmDeleteForever(item: TrashModel) {
        val builder = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_forever))
            .setMessage(getString(R.string.delete_forever_confirm, item.fileName))
            .setPositiveButton(getString(R.string.delete_forever)) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val deleted = trashRepository.deleteForever(item.id)
                    if (deleted) {
                        Toast.makeText(requireContext(), getString(R.string.deleted_forever), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
        DialogUi.showWithReadableButtons(builder, requireContext())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
