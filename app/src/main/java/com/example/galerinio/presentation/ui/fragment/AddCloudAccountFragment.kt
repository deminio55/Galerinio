package com.example.galerinio.presentation.ui.fragment

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.galerinio.R
import com.example.galerinio.databinding.FragmentAddCloudAccountBinding
import com.example.galerinio.domain.model.CloudProviderType
import com.example.galerinio.presentation.ui.activity.MainActivity
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for choosing which cloud provider to add.
 * Google Drive launches OAuth sign-in flow.
 * Custom providers (WebDAV, SMB, SFTP) go directly to the setup form.
 */
class AddCloudAccountFragment : Fragment() {

    companion object {
        private const val TAG = "AddCloudAccount"
        private const val DRIVE_SCOPE = "oauth2:${DriveScopes.DRIVE_FILE}"
    }

    private var _binding: FragmentAddCloudAccountBinding? = null
    private val binding get() = _binding!!


    /** Selected Google account email (persists across consent screen) */
    private var pendingGoogleEmail: String = ""

    /** GoogleAccountCredential used for Drive access */
    private val googleCredential by lazy {
        GoogleAccountCredential.usingOAuth2(
            requireContext().applicationContext,
            listOf(DriveScopes.DRIVE_FILE)
        )
    }

    /** Launcher: Google account chooser */
    private val googleAccountPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val accountName = result.data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)
            if (!accountName.isNullOrBlank()) {
                pendingGoogleEmail = accountName
                googleCredential.selectedAccountName = accountName
                fetchGoogleTokenAndNavigate(accountName)
            } else {
                showToast(getString(R.string.cloud_google_sign_in_failed))
            }
        }
    }

    /** Launcher: Google consent / authorization recovery */
    private val googleConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // Consent granted — retry token acquisition
            if (pendingGoogleEmail.isNotBlank()) {
                fetchGoogleTokenAndNavigate(pendingGoogleEmail)
            }
        } else {
            Log.w(TAG, "Google consent denied or cancelled, resultCode=${result.resultCode}")
            showToast(getString(R.string.cloud_google_sign_in_failed))
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddCloudAccountBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnGoogleDrive.setOnClickListener {
            launchGoogleSignIn()
        }
        binding.btnWebDav.setOnClickListener {
            openSetup(CloudProviderType.WEBDAV)
        }
        binding.btnSmb.setOnClickListener {
            openSetup(CloudProviderType.SMB)
        }
        binding.btnSftp.setOnClickListener {
            openSetup(CloudProviderType.SFTP)
        }
    }

    // ─── Google Drive ──────────────────────────────────────────────

    private fun launchGoogleSignIn() {
        try {
            googleAccountPickerLauncher.launch(googleCredential.newChooseAccountIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch account picker", e)
            showToast(getString(R.string.cloud_google_sign_in_failed))
        }
    }

    /**
     * Attempt to obtain an OAuth token using GoogleAuthUtil directly.
     * Handles UserRecoverableAuthException by launching the consent screen.
     */
    private fun fetchGoogleTokenAndNavigate(email: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    val account = android.accounts.Account(email, "com.google")
                    GoogleAuthUtil.getToken(
                        requireContext().applicationContext,
                        account,
                        DRIVE_SCOPE
                    )
                }
                if (_binding == null) return@launch

                Log.i(TAG, "Google Drive token obtained for $email")
                navigateToSetup(CloudProviderType.GOOGLE_DRIVE, email, token.orEmpty())

            } catch (e: UserRecoverableAuthException) {
                // Need user consent — show Google's consent screen
                Log.i(TAG, "Launching Google consent screen for $email")
                pendingGoogleEmail = email
                googleConsentLauncher.launch(e.intent)

            } catch (e: GoogleAuthException) {
                Log.e(TAG, "GoogleAuthException: ${e.message}", e)
                if (_binding == null) return@launch
                showToast("Google Auth error: ${e.message}")

            } catch (e: Exception) {
                Log.e(TAG, "Google token error: ${e.javaClass.simpleName}: ${e.message}", e)
                if (_binding == null) return@launch
                // Check if wrapped UserRecoverableAuthException
                val recoverable = findRecoverableException(e)
                if (recoverable != null) {
                    pendingGoogleEmail = email
                    googleConsentLauncher.launch(recoverable.intent)
                } else {
                    showToast("Google Drive error: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        }
    }

    /** Walk the exception cause chain looking for UserRecoverableAuthException */
    private fun findRecoverableException(e: Throwable): UserRecoverableAuthException? {
        var current: Throwable? = e
        repeat(5) {
            when (current) {
                is UserRecoverableAuthException -> return current as UserRecoverableAuthException
                else -> current = current?.cause
            }
        }
        return null
    }


    // ─── Navigation helpers ────────────────────────────────────────

    private fun navigateToSetup(
        providerType: CloudProviderType,
        oauthEmail: String = "",
        oauthToken: String = ""
    ) {
        val fragment = CloudAccountSetupFragment.newInstance(
            providerType = providerType.name,
            oauthEmail = oauthEmail,
            oauthToken = oauthToken
        )
        parentFragmentManager.beginTransaction()
            .replace(R.id.drawerDetailContainer, fragment)
            .addToBackStack(MainActivity.DRAWER_CLOUD_NAV)
            .commit()
        view?.post { (activity as? MainActivity)?.reapplyDrawerThemeColors() }
        (activity as? MainActivity)?.updateDrawerDetailTitle(
            providerType.name.replace("_", " ")
        )
    }

    private fun openSetup(providerType: CloudProviderType) {
        navigateToSetup(providerType)
    }

    private fun showToast(message: String) {
        if (_binding != null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
