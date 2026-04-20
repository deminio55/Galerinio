package com.example.galerinio.presentation.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.galerinio.R
import com.example.galerinio.databinding.ItemCloudAccountBinding
import com.example.galerinio.domain.model.CloudAccount
import com.example.galerinio.domain.model.CloudProviderType
import com.example.galerinio.domain.model.SyncMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CloudAccountAdapter(
    private val onItemClick: (CloudAccount) -> Unit,
    private val onToggleEnabled: (CloudAccount) -> Unit,
    private val onItemLongClick: ((CloudAccount) -> Unit)? = null
) : ListAdapter<CloudAccount, CloudAccountAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemCloudAccountBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(getItem(position))
    }

    inner class VH(private val binding: ItemCloudAccountBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(account: CloudAccount) {
            binding.tvAccountName.text = account.displayName
            binding.tvAccountDetails.text = buildDetailsText(account)
            binding.tvLastSync.text = formatLastSync(account.lastSyncTimestamp)
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = account.isEnabled
            binding.switchEnabled.setOnCheckedChangeListener { _, _ ->
                onToggleEnabled(account)
            }
            binding.root.setOnClickListener { onItemClick(account) }
            binding.root.setOnLongClickListener {
                onItemLongClick?.invoke(account)
                true
            }
        }

        private fun buildDetailsText(account: CloudAccount): String {
            val provider = when (account.providerType) {
                CloudProviderType.GOOGLE_DRIVE -> "Google Drive"
                CloudProviderType.WEBDAV -> "WebDAV"
                CloudProviderType.SMB -> "SMB"
                CloudProviderType.SFTP -> "SFTP"
            }
            val mode = when (account.syncMode) {
                SyncMode.BACKUP -> binding.root.context.getString(R.string.cloud_sync_mode_backup_short)
                SyncMode.MIRROR -> binding.root.context.getString(R.string.cloud_sync_mode_mirror_short)
            }
            return "$provider · $mode"
        }

        private fun formatLastSync(timestamp: Long): String {
            if (timestamp <= 0L) return binding.root.context.getString(R.string.cloud_never_synced)
            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            return binding.root.context.getString(R.string.cloud_last_sync, fmt.format(Date(timestamp)))
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<CloudAccount>() {
            override fun areItemsTheSame(a: CloudAccount, b: CloudAccount) = a.id == b.id
            override fun areContentsTheSame(a: CloudAccount, b: CloudAccount) = a == b
        }
    }
}
