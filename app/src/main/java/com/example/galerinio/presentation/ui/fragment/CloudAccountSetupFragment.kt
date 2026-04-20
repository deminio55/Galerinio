package com.example.galerinio.presentation.ui.fragment

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.galerinio.R
import com.example.galerinio.data.cloud.CloudProviderFactory
import com.example.galerinio.databinding.FragmentCloudAccountSetupBinding
import com.example.galerinio.domain.model.*
import com.example.galerinio.presentation.ui.activity.MainActivity
import com.example.galerinio.presentation.viewmodel.CloudSyncViewModel
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified setup/edit fragment for any cloud provider.
 * Provider-specific credential fields are inflated dynamically based on providerType.
 */
class CloudAccountSetupFragment : Fragment() {

    companion object {
        private const val TAG = "CloudAccountSetup"
        private const val ARG_PROVIDER_TYPE = "provider_type"
        private const val ARG_ACCOUNT_ID = "account_id"
        private const val ARG_OAUTH_EMAIL = "oauth_email"
        private const val ARG_OAUTH_TOKEN = "oauth_token"

        fun newInstance(
            providerType: String,
            accountId: Long = 0L,
            oauthEmail: String = "",
            oauthToken: String = ""
        ): CloudAccountSetupFragment {
            return CloudAccountSetupFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PROVIDER_TYPE, providerType)
                    putLong(ARG_ACCOUNT_ID, accountId)
                    putString(ARG_OAUTH_EMAIL, oauthEmail)
                    putString(ARG_OAUTH_TOKEN, oauthToken)
                }
            }
        }
    }

    private var _binding: FragmentCloudAccountSetupBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: CloudSyncViewModel
    private lateinit var providerType: CloudProviderType
    private var editAccountId: Long = 0L

    /** OAuth data received from AddCloudAccountFragment sign-in flow */
    private var oauthEmail: String = ""
    private var oauthToken: String = ""

    // Provider-specific field views
    private var fieldsView: View? = null

    private val gson = Gson()

    /** Launcher for Google Drive consent screen (triggered during test connection) */
    private val googleConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Consent granted — retry test connection
            testConnection()
        } else {
            Toast.makeText(requireContext(), getString(R.string.cloud_connection_failed), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCloudAccountSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[CloudSyncViewModel::class.java]

        providerType = runCatching {
            CloudProviderType.valueOf(arguments?.getString(ARG_PROVIDER_TYPE) ?: "")
        }.getOrDefault(CloudProviderType.WEBDAV)

        editAccountId = arguments?.getLong(ARG_ACCOUNT_ID, 0L) ?: 0L
        oauthEmail = arguments?.getString(ARG_OAUTH_EMAIL).orEmpty()
        oauthToken = arguments?.getString(ARG_OAUTH_TOKEN).orEmpty()

        inflateProviderFields()
        setupListeners()

        if (editAccountId > 0L) {
            loadExistingAccount()
        } else {
            // For OAuth providers, use email as default name if available
            val defaultName = when {
                oauthEmail.isNotBlank() -> oauthEmail
                else -> providerType.name.replace("_", " ")
            }
            binding.etAccountName.setText(defaultName)
        }
    }

    private fun inflateProviderFields() {
        val layoutId = when (providerType) {
            CloudProviderType.WEBDAV -> R.layout.fields_webdav
            CloudProviderType.SMB -> R.layout.fields_smb
            CloudProviderType.SFTP -> R.layout.fields_sftp
            CloudProviderType.GOOGLE_DRIVE -> null
        }

        if (layoutId != null) {
            fieldsView = LayoutInflater.from(requireContext())
                .inflate(layoutId, binding.providerFieldsContainer, false)
            binding.providerFieldsContainer.addView(fieldsView)
        } else {
            // OAuth providers — show connected account info or prompt
            val infoText = TextView(requireContext()).apply {
                text = when {
                    providerType == CloudProviderType.GOOGLE_DRIVE && oauthEmail.isNotBlank() ->
                        getString(R.string.cloud_google_connected, oauthEmail)
                    else ->
                        getString(R.string.cloud_oauth_info, providerType.name.replace("_", " "))
                }
                setTextColor(resources.getColor(R.color.app_on_surface_secondary, null))
                setPadding(0, 16, 0, 16)
                textSize = 14f
            }
            binding.providerFieldsContainer.addView(infoText)
        }
    }

    private fun setupListeners() {
        binding.btnTestConnection.setOnClickListener { testConnection() }
        binding.btnSave.setOnClickListener { saveAccount() }
        binding.btnDelete.setOnClickListener { deleteAccount() }

        if (editAccountId > 0L) {
            binding.btnDelete.visibility = View.VISIBLE
        }
    }

    private fun loadExistingAccount() {
        viewLifecycleOwner.lifecycleScope.launch {
            val account = viewModel.repository.getAccountById(editAccountId) ?: return@launch
            binding.etAccountName.setText(account.displayName)
            binding.etRemoteFolder.setText(account.remoteFolderPath)
            binding.switchWifiOnly.isChecked = account.syncOnlyWifi
            binding.switchChargingOnly.isChecked = account.syncOnlyCharging

            when (account.syncMode) {
                SyncMode.BACKUP -> binding.radioBackup.isChecked = true
                SyncMode.MIRROR -> binding.radioMirror.isChecked = true
            }

            // Fill provider-specific fields
            fillCredentialFields(account.credentialsJson)
            binding.btnDelete.visibility = View.VISIBLE
        }
    }

    private fun fillCredentialFields(credentialsJson: String) {
        if (credentialsJson.isBlank()) return
        try {
            when (providerType) {
                CloudProviderType.WEBDAV -> {
                    val cred = gson.fromJson(credentialsJson, WebDavCredentials::class.java)
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWebDavUrl)
                        ?.setText(cred.url)
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWebDavLogin)
                        ?.setText(cred.login)
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWebDavPassword)
                        ?.setText(cred.password)
                }
                CloudProviderType.SMB -> {
                    val cred = gson.fromJson(credentialsJson, SmbCredentials::class.java)
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSmbHost)
                        ?.setText(cred.host)
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSmbWorkgroup)
                        ?.setText(cred.workgroup)
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSmbLogin)
                        ?.setText(cred.login)
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSmbPassword)
                        ?.setText(cred.password)
                }
                CloudProviderType.SFTP -> {
                    val cred = gson.fromJson(credentialsJson, SftpCredentials::class.java)
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSftpHost)
                        ?.setText(cred.host)
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSftpPort)
                        ?.setText(cred.port.toString())
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSftpLogin)
                        ?.setText(cred.login)
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSftpPassword)
                        ?.setText(cred.password)
                    fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSftpPrivateKey)
                        ?.setText(cred.privateKey)
                }
                else -> { /* OAuth — nothing to fill */ }
            }
        } catch (_: Exception) { }
    }

    private fun buildCredentialsJson(): String {
        return when (providerType) {
            CloudProviderType.WEBDAV -> {
                val cred = WebDavCredentials(
                    url = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWebDavUrl)
                        ?.text?.toString().orEmpty(),
                    login = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWebDavLogin)
                        ?.text?.toString().orEmpty(),
                    password = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWebDavPassword)
                        ?.text?.toString().orEmpty()
                )
                CloudProviderFactory.serializeCredentials(cred)
            }
            CloudProviderType.SMB -> {
                val cred = SmbCredentials(
                    host = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSmbHost)
                        ?.text?.toString().orEmpty(),
                    workgroup = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSmbWorkgroup)
                        ?.text?.toString().orEmpty().ifBlank { "WORKGROUP" },
                    login = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSmbLogin)
                        ?.text?.toString().orEmpty(),
                    password = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSmbPassword)
                        ?.text?.toString().orEmpty()
                )
                CloudProviderFactory.serializeCredentials(cred)
            }
            CloudProviderType.SFTP -> {
                val cred = SftpCredentials(
                    host = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSftpHost)
                        ?.text?.toString().orEmpty(),
                    port = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSftpPort)
                        ?.text?.toString()?.toIntOrNull() ?: 22,
                    login = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSftpLogin)
                        ?.text?.toString().orEmpty(),
                    password = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSftpPassword)
                        ?.text?.toString().orEmpty(),
                    privateKey = fieldsView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSftpPrivateKey)
                        ?.text?.toString().orEmpty()
                )
                CloudProviderFactory.serializeCredentials(cred)
            }
            CloudProviderType.GOOGLE_DRIVE -> {
                CloudProviderFactory.serializeCredentials(
                    GoogleDriveCredentials(
                        accountEmail = oauthEmail,
                        accessToken = oauthToken
                    )
                )
            }
        }
    }

    private fun testConnection() {
        val credJson = buildCredentialsJson()
        binding.btnTestConnection.isEnabled = false
        binding.btnTestConnection.text = getString(R.string.cloud_testing)

        viewLifecycleOwner.lifecycleScope.launch {
            var recoveryIntent: android.content.Intent? = null
            var errorMessage: String? = null
            val success = withContext(Dispatchers.IO) {
                try {
                    val provider = CloudProviderFactory.create(providerType, credJson, requireContext().applicationContext)
                    val result = provider.testConnection()
                    provider.close()
                    result
                } catch (e: UserRecoverableAuthIOException) {
                    recoveryIntent = e.intent
                    false
                } catch (e: Exception) {
                    Log.e(TAG, "Test connection failed: ${e.javaClass.simpleName}: ${e.message}", e)
                    errorMessage = e.message ?: e.javaClass.simpleName
                    // Check wrapped cause for recoverable auth
                    var cause: Throwable? = e.cause
                    repeat(5) {
                        if (cause is UserRecoverableAuthIOException) {
                            recoveryIntent = (cause as UserRecoverableAuthIOException).intent
                            return@repeat
                        }
                        cause = cause?.cause
                    }
                    false
                }
            }
            if (_binding == null) return@launch

            binding.btnTestConnection.isEnabled = true
            binding.btnTestConnection.text = getString(R.string.cloud_test_connection)

            if (recoveryIntent != null) {
                // Need user consent for Google Drive — launch consent screen
                googleConsentLauncher.launch(recoveryIntent)
                return@launch
            }

            if (success) {
                Toast.makeText(requireContext(), getString(R.string.cloud_connection_success), Toast.LENGTH_SHORT).show()
            } else {
                val hint = when {
                    errorMessage != null -> "${getString(R.string.cloud_connection_failed)}: $errorMessage"
                    providerType == CloudProviderType.WEBDAV -> "${getString(R.string.cloud_connection_failed)}. Check URL format (e.g. https://your-server:5006). QuickConnect URLs may not support WebDAV — use direct IP or DDNS."
                    providerType == CloudProviderType.SFTP -> "${getString(R.string.cloud_connection_failed)}. Check host, port and credentials. QuickConnect URLs don't support SFTP — use direct IP or DDNS."
                    else -> getString(R.string.cloud_connection_failed)
                }
                Toast.makeText(requireContext(), hint, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveAccount() {
        val name = binding.etAccountName.text?.toString().orEmpty().trim()
        if (name.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.cloud_name_required), Toast.LENGTH_SHORT).show()
            return
        }

        val syncMode = if (binding.radioMirror.isChecked) SyncMode.MIRROR else SyncMode.BACKUP
        val remoteFolder = binding.etRemoteFolder.text?.toString().orEmpty().trim().ifBlank { "/Galerinio" }

        val account = CloudAccount(
            id = editAccountId,
            providerType = providerType,
            displayName = name,
            syncMode = syncMode,
            syncOnlyWifi = binding.switchWifiOnly.isChecked,
            syncOnlyCharging = binding.switchChargingOnly.isChecked,
            remoteFolderPath = remoteFolder,
            isEnabled = true,
            credentialsJson = buildCredentialsJson()
        )

        viewModel.saveAccount(account) {
            if (_binding == null) return@saveAccount
            Toast.makeText(requireContext(), getString(R.string.cloud_account_saved), Toast.LENGTH_SHORT).show()
            parentFragmentManager.popBackStack()
            (activity as? MainActivity)?.reapplyDrawerThemeColors()
        }
    }

    private fun deleteAccount() {
        if (editAccountId <= 0L) return
        viewModel.deleteAccount(editAccountId)
        Toast.makeText(requireContext(), getString(R.string.cloud_account_deleted), Toast.LENGTH_SHORT).show()
        parentFragmentManager.popBackStack()
        (activity as? MainActivity)?.reapplyDrawerThemeColors()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        fieldsView = null
        _binding = null
    }
}
