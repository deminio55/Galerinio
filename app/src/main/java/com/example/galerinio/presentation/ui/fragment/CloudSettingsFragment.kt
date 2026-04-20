package com.example.galerinio.presentation.ui.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.galerinio.R
import com.example.galerinio.databinding.FragmentCloudSettingsBinding
import com.example.galerinio.domain.model.CloudAccount
import com.example.galerinio.presentation.adapter.CloudAccountAdapter
import com.example.galerinio.presentation.ui.activity.MainActivity
import com.example.galerinio.presentation.viewmodel.CloudSyncViewModel
import kotlinx.coroutines.launch

/**
 * Main cloud settings screen shown inside the drawer.
 * Displays the list of configured cloud accounts and provides
 * buttons to add a new account or trigger manual sync.
 */
class CloudSettingsFragment : Fragment() {

    private var _binding: FragmentCloudSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: CloudSyncViewModel
    private lateinit var adapter: CloudAccountAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCloudSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[CloudSyncViewModel::class.java]

        adapter = CloudAccountAdapter(
            onItemClick = { account ->
                openAccountForEditing(account)
            },
            onToggleEnabled = { account ->
                viewModel.toggleAccountEnabled(account)
            },
            onItemLongClick = { account ->
                showAccountContextMenu(account)
            }
        )

        binding.rvCloudAccounts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvCloudAccounts.adapter = adapter

        binding.btnAddAccount.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.drawerDetailContainer, AddCloudAccountFragment())
                .addToBackStack(MainActivity.DRAWER_CLOUD_NAV)
                .commit()
            view.post { (activity as? MainActivity)?.reapplyDrawerThemeColors() }
            (activity as? MainActivity)?.updateDrawerDetailTitle(
                getString(R.string.cloud_add_account)
            )
        }

        binding.btnSyncAll.setOnClickListener {
            viewModel.syncAllNow()
            Toast.makeText(requireContext(), getString(R.string.cloud_sync_started), Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.accounts.collect { accounts ->
                    adapter.submitList(accounts)
                }
            }
        }
    }

    private fun openAccountForEditing(account: CloudAccount) {
        val fragment = CloudAccountSetupFragment.newInstance(
            account.providerType.name,
            account.id
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.drawerDetailContainer, fragment)
            .addToBackStack(MainActivity.DRAWER_CLOUD_NAV)
            .commit()
        view?.post { (activity as? MainActivity)?.reapplyDrawerThemeColors() }
        (activity as? MainActivity)?.updateDrawerDetailTitle(
            account.displayName.ifBlank { account.providerType.name }
        )
    }

    private fun showAccountContextMenu(account: CloudAccount) {
        val items = arrayOf(
            getString(R.string.cloud_edit_account),
            getString(R.string.cloud_delete_account)
        )
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Galerinio_CloudDialog)
            .setTitle(account.displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openAccountForEditing(account)
                    1 -> confirmDeleteAccount(account)
                }
            }
            .show()
    }

    private fun confirmDeleteAccount(account: CloudAccount) {
        MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Galerinio_CloudDialog)
            .setTitle(R.string.cloud_delete_confirm_title)
            .setMessage(getString(R.string.cloud_delete_confirm_message, account.displayName))
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.deleteAccount(account.id)
                Toast.makeText(requireContext(), getString(R.string.cloud_account_deleted), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
