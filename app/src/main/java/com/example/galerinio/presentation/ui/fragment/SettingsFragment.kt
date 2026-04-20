package com.example.galerinio.presentation.ui.fragment

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.galerinio.R
import com.example.galerinio.data.util.PreferencesManager
import com.example.galerinio.data.util.SecurityCredentialStore
import com.example.galerinio.databinding.FragmentSettingsBinding
import com.example.galerinio.presentation.ui.activity.UnlockActivity
import com.example.galerinio.presentation.ui.util.ThemeManager
import com.example.galerinio.presentation.ui.widget.PatternLockView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private enum class PendingVerifiedAction {
        DISABLE_APP_LOCK,
        DISABLE_FOLDER_PROTECTION,
        SELECT_APP_PIN,
        SELECT_APP_PATTERN,
        SELECT_APP_BIOMETRIC,
        SELECT_FOLDER_PIN,
        SELECT_FOLDER_PATTERN,
        SELECT_FOLDER_BIOMETRIC
    }

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var preferencesManager: PreferencesManager
    private lateinit var credentialStore: SecurityCredentialStore

    private var isBindingState = false
    private var pendingVerifiedAction: PendingVerifiedAction? = null

    private val unlockForSettingsChangeLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            executePendingVerifiedAction()
        } else {
            pendingVerifiedAction = null
            viewLifecycleOwner.lifecycleScope.launch { bindState() }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preferencesManager = PreferencesManager(requireContext())
        credentialStore = SecurityCredentialStore(requireContext())
        setupListeners()
        loadState()
        viewLifecycleOwner.lifecycleScope.launch { applyRuntimeSecurityAccentTint() }
    }

    private fun setupListeners() {
        binding.switchAppLock.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingState) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                if (!isChecked) {
                    requestVerification(
                        action = PendingVerifiedAction.DISABLE_APP_LOCK,
                        scope = UnlockActivity.SCOPE_APP
                    )
                    bindState()
                    return@launch
                }
                preferencesManager.setAppLockEnabled(true)
                bindState()
            }
        }

        binding.switchFolderProtection.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingState) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                if (!isChecked) {
                    requestVerification(
                        action = PendingVerifiedAction.DISABLE_FOLDER_PROTECTION,
                        scope = UnlockActivity.SCOPE_FOLDER
                    )
                    bindState()
                    return@launch
                }
                preferencesManager.setFolderProtectionEnabled(true)
                bindState()
            }
        }

        binding.rgAppMethod.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingState) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                if (!preferencesManager.isAppLockEnabled()) {
                    bindState()
                    return@launch
                }
                handleAppMethodSelection(checkedId)
            }
        }

        binding.rgFolderMethod.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingState) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                if (!preferencesManager.isFolderProtectionEnabled()) {
                    bindState()
                    return@launch
                }
                handleFolderMethodSelection(checkedId)
            }
        }

        binding.rgProtectedMediaMode.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingState) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                if (!preferencesManager.isFolderProtectionEnabled()) {
                    bindState()
                    return@launch
                }
                val mode = if (checkedId == R.id.radioProtectedMediaBlur) {
                    PreferencesManager.ProtectedMediaDisplayMode.BLUR
                } else {
                    PreferencesManager.ProtectedMediaDisplayMode.HIDE
                }
                preferencesManager.setProtectedMediaDisplayMode(mode)
            }
        }

        listOf(
            binding.radioLockInstant,
            binding.radioLockAfter30Sec,
            binding.radioLockAfter1Min,
            binding.radioLockAfter5Min
        ).forEach { radio ->
            radio.setOnClickListener {
                val seconds = when (radio.id) {
                    R.id.radioLockAfter30Sec -> 30
                    R.id.radioLockAfter1Min -> 60
                    R.id.radioLockAfter5Min -> 300
                    else -> 0
                }
                lifecycleScope.launch { preferencesManager.setAppLockTimeoutSeconds(seconds) }
            }
        }
    }

    private suspend fun handleAppMethodSelection(checkedId: Int) {
        val targetAction = when (checkedId) {
            R.id.radioAppPin -> PendingVerifiedAction.SELECT_APP_PIN
            R.id.radioAppPattern -> PendingVerifiedAction.SELECT_APP_PATTERN
            R.id.radioAppBiometric -> PendingVerifiedAction.SELECT_APP_BIOMETRIC
            else -> null
        } ?: return

        val currentMethod = preferencesManager.getAppLockMethod()
        val targetMethod = when (targetAction) {
            PendingVerifiedAction.SELECT_APP_PIN -> PreferencesManager.LockMethod.PIN
            PendingVerifiedAction.SELECT_APP_PATTERN -> PreferencesManager.LockMethod.PATTERN
            PendingVerifiedAction.SELECT_APP_BIOMETRIC -> PreferencesManager.LockMethod.BIOMETRIC
            else -> PreferencesManager.LockMethod.NONE
        }
        if (currentMethod == targetMethod) return

        if (currentMethod != PreferencesManager.LockMethod.NONE) {
            requestVerification(targetAction, UnlockActivity.SCOPE_APP)
            return
        }
        startMethodConfiguration(targetAction)
    }

    private suspend fun handleFolderMethodSelection(checkedId: Int) {
        val targetAction = when (checkedId) {
            R.id.radioFolderPin -> PendingVerifiedAction.SELECT_FOLDER_PIN
            R.id.radioFolderPattern -> PendingVerifiedAction.SELECT_FOLDER_PATTERN
            R.id.radioFolderBiometric -> PendingVerifiedAction.SELECT_FOLDER_BIOMETRIC
            else -> null
        } ?: return

        val currentMethod = preferencesManager.getFolderLockMethod()
        val targetMethod = when (targetAction) {
            PendingVerifiedAction.SELECT_FOLDER_PIN -> PreferencesManager.LockMethod.PIN
            PendingVerifiedAction.SELECT_FOLDER_PATTERN -> PreferencesManager.LockMethod.PATTERN
            PendingVerifiedAction.SELECT_FOLDER_BIOMETRIC -> PreferencesManager.LockMethod.BIOMETRIC
            else -> PreferencesManager.LockMethod.NONE
        }
        if (currentMethod == targetMethod) return

        if (currentMethod != PreferencesManager.LockMethod.NONE) {
            requestVerification(targetAction, UnlockActivity.SCOPE_FOLDER)
            return
        }
        startMethodConfiguration(targetAction)
    }

    private fun loadState() {
        lifecycleScope.launch { bindState() }
    }

    private fun requestVerification(action: PendingVerifiedAction, scope: String) {
        pendingVerifiedAction = action
        Toast.makeText(requireContext(), getString(R.string.security_unlock_to_change_settings), Toast.LENGTH_SHORT).show()
        val intent = Intent(requireContext(), UnlockActivity::class.java).putExtra(UnlockActivity.EXTRA_UNLOCK_SCOPE, scope)
        unlockForSettingsChangeLauncher.launch(intent)
    }

    private fun executePendingVerifiedAction() {
        val action = pendingVerifiedAction ?: return
        pendingVerifiedAction = null
        startMethodConfiguration(action)
    }

    private fun startMethodConfiguration(action: PendingVerifiedAction) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (action) {
                PendingVerifiedAction.DISABLE_APP_LOCK -> {
                    preferencesManager.setAppLockEnabled(false)
                    preferencesManager.setAppLockMethod(PreferencesManager.LockMethod.NONE)
                    preferencesManager.setAppLockTimeoutSeconds(0)
                    preferencesManager.setLastUnlockElapsedRealtime(0L)
                    preferencesManager.setLastBackgroundElapsedRealtime(0L)
                    if (!preferencesManager.isFolderProtectionEnabled()) {
                        credentialStore.clearPin()
                        credentialStore.clearPattern()
                    }
                    bindState()
                }
                PendingVerifiedAction.DISABLE_FOLDER_PROTECTION -> {
                    preferencesManager.setFolderProtectionEnabled(false)
                    preferencesManager.setFolderLockMethod(PreferencesManager.LockMethod.NONE)
                    preferencesManager.setProtectedMediaDisplayMode(PreferencesManager.ProtectedMediaDisplayMode.HIDE)
                    preferencesManager.setProtectedAlbumIds(emptySet())
                    if (!preferencesManager.isAppLockEnabled()) {
                        credentialStore.clearPin()
                        credentialStore.clearPattern()
                    }
                    bindState()
                }
                PendingVerifiedAction.SELECT_APP_PIN -> {
                    showSetPinDialog {
                        preferencesManager.setAppLockMethod(PreferencesManager.LockMethod.PIN)
                    }
                }
                PendingVerifiedAction.SELECT_APP_PATTERN -> {
                    showSetPatternDialog {
                        preferencesManager.setAppLockMethod(PreferencesManager.LockMethod.PATTERN)
                    }
                }
                PendingVerifiedAction.SELECT_APP_BIOMETRIC -> {
                    startBiometricActivation {
                        lifecycleScope.launch {
                            preferencesManager.setAppLockMethod(PreferencesManager.LockMethod.BIOMETRIC)
                            bindState()
                        }
                    }
                }
                PendingVerifiedAction.SELECT_FOLDER_PIN -> {
                    showSetPinDialog {
                        preferencesManager.setFolderLockMethod(PreferencesManager.LockMethod.PIN)
                    }
                }
                PendingVerifiedAction.SELECT_FOLDER_PATTERN -> {
                    showSetPatternDialog {
                        preferencesManager.setFolderLockMethod(PreferencesManager.LockMethod.PATTERN)
                    }
                }
                PendingVerifiedAction.SELECT_FOLDER_BIOMETRIC -> {
                    startBiometricActivation {
                        lifecycleScope.launch {
                            preferencesManager.setFolderLockMethod(PreferencesManager.LockMethod.BIOMETRIC)
                            bindState()
                        }
                    }
                }
            }
        }
    }

    private suspend fun bindState() {
        isBindingState = true
        val appLock = preferencesManager.isAppLockEnabled()
        val folderProtection = preferencesManager.isFolderProtectionEnabled()
        val appMethod = preferencesManager.getAppLockMethod()
        val folderMethod = preferencesManager.getFolderLockMethod()
        val protectedMediaMode = preferencesManager.getProtectedMediaDisplayMode()
        val timeoutSeconds = preferencesManager.getAppLockTimeoutSeconds()

        binding.switchAppLock.isChecked = appLock
        binding.switchFolderProtection.isChecked = folderProtection

        binding.appMethodsContainer.isVisible = appLock
        binding.folderMethodsContainer.isVisible = folderProtection
        binding.protectedMediaModeContainer.isVisible = folderProtection

        binding.tvAppLockHelper.isVisible = appLock && appMethod == PreferencesManager.LockMethod.NONE
        binding.tvFolderProtectionHelper.isVisible = folderProtection && folderMethod == PreferencesManager.LockMethod.NONE

        binding.radioAppPin.isChecked = appMethod == PreferencesManager.LockMethod.PIN
        binding.radioAppPattern.isChecked = appMethod == PreferencesManager.LockMethod.PATTERN
        binding.radioAppBiometric.isChecked = appMethod == PreferencesManager.LockMethod.BIOMETRIC

        binding.radioFolderPin.isChecked = folderMethod == PreferencesManager.LockMethod.PIN
        binding.radioFolderPattern.isChecked = folderMethod == PreferencesManager.LockMethod.PATTERN
        binding.radioFolderBiometric.isChecked = folderMethod == PreferencesManager.LockMethod.BIOMETRIC

        binding.radioProtectedMediaHide.isChecked = protectedMediaMode == PreferencesManager.ProtectedMediaDisplayMode.HIDE
        binding.radioProtectedMediaBlur.isChecked = protectedMediaMode == PreferencesManager.ProtectedMediaDisplayMode.BLUR

        val appLockActive = appLock && appMethod != PreferencesManager.LockMethod.NONE
        binding.tvAutoLockTimeoutTitle.isEnabled = appLockActive
        binding.rgAutoLockTimeout.isEnabled = appLockActive
        binding.radioLockInstant.isEnabled = appLockActive
        binding.radioLockAfter30Sec.isEnabled = appLockActive
        binding.radioLockAfter1Min.isEnabled = appLockActive
        binding.radioLockAfter5Min.isEnabled = appLockActive

        binding.radioLockInstant.isChecked = timeoutSeconds == 0
        binding.radioLockAfter30Sec.isChecked = timeoutSeconds == 30
        binding.radioLockAfter1Min.isChecked = timeoutSeconds == 60
        binding.radioLockAfter5Min.isChecked = timeoutSeconds == 300

        applyRuntimeSecurityAccentTint()

        isBindingState = false
    }

    private suspend fun applyRuntimeSecurityAccentTint() {
        val isDarkMode = preferencesManager.isDarkModeEnabled()
        val palette = preferencesManager.getThemePalette(isDarkMode)
        val colors = ThemeManager.resolvePaletteColors(isDarkMode, palette)
        val accent = preferencesManager.getThemeAccent(isDarkMode)
        val accentColor = ThemeManager.resolveAccentColors(isDarkMode, accent).accent

        val thumbState = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accentColor, ColorUtils.setAlphaComponent(colors.onSurface, 170))
        )
        val trackState = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(ColorUtils.setAlphaComponent(accentColor, 130), ColorUtils.setAlphaComponent(colors.onSurface, 70))
        )

        binding.switchAppLock.thumbTintList = thumbState
        binding.switchAppLock.trackTintList = trackState
        binding.switchFolderProtection.thumbTintList = thumbState
        binding.switchFolderProtection.trackTintList = trackState

        listOf(
            binding.radioAppPin,
            binding.radioAppPattern,
            binding.radioAppBiometric,
            binding.radioFolderPin,
            binding.radioFolderPattern,
            binding.radioFolderBiometric,
            binding.radioProtectedMediaHide,
            binding.radioProtectedMediaBlur,
            binding.radioLockInstant,
            binding.radioLockAfter30Sec,
            binding.radioLockAfter1Min,
            binding.radioLockAfter5Min
        ).forEach { radio ->
            radio.buttonTintList = ColorStateList.valueOf(accentColor)
        }
    }

    private fun showSetPinDialog(onConfigured: suspend () -> Unit) {
        val content = layoutInflater.inflate(R.layout.dialog_pin_setup, null)
        val input = content.findViewById<EditText>(R.id.etPinDialog)
        val confirm = content.findViewById<EditText>(R.id.etPinDialogConfirm)
        var configured = false

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.security_set_pin))
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        val submit = submit@{
            val pin = input.text?.toString().orEmpty().trim()
            val pinConfirm = confirm.text?.toString().orEmpty().trim()
            if (pin.length < 4) {
                Toast.makeText(requireContext(), getString(R.string.security_pin_too_short), Toast.LENGTH_SHORT).show()
                return@submit
            }
            if (pin != pinConfirm) {
                Toast.makeText(requireContext(), getString(R.string.security_pin_mismatch), Toast.LENGTH_SHORT).show()
                return@submit
            }
            lifecycleScope.launch {
                credentialStore.savePin(pin)
                onConfigured.invoke()
                configured = true
                bindState()
                dialog.dismiss()
            }
        }

        dialog.setOnShowListener {
            applyReadableDialogButtonColors(dialog)
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okButton.setOnClickListener { submit.invoke() }

            confirm.setOnEditorActionListener { _, actionId, event ->
                val isDone = actionId == EditorInfo.IME_ACTION_DONE ||
                    (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                if (!isDone) return@setOnEditorActionListener false
                submit.invoke()
                true
            }
        }
        dialog.setOnDismissListener {
            if (!configured) {
                lifecycleScope.launch { bindState() }
            }
        }
        dialog.show()
    }

    private fun showSetPatternDialog(onConfigured: suspend () -> Unit) {
        val content = layoutInflater.inflate(R.layout.dialog_pattern_setup, null)
        val hintText = content.findViewById<TextView>(R.id.tvPatternDialogHint)
        val patternView = content.findViewById<PatternLockView>(R.id.patternDialogView)
        val clearButton = content.findViewById<View>(R.id.btnClearPattern)

        var firstPattern: String? = null
        var confirmedPattern: String? = null
        var configured = false

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.security_set_pattern))
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(getString(R.string.cancel), null)
            .create()

        patternView.onPatternDetected = { pattern ->
            if (pattern.size < 4) {
                Toast.makeText(requireContext(), getString(R.string.security_pattern_too_short), Toast.LENGTH_SHORT).show()
                patternView.clearPattern()
            } else {
                val encoded = encodePattern(pattern)
                if (firstPattern == null) {
                    firstPattern = encoded
                    confirmedPattern = null
                    hintText.text = getString(R.string.security_pattern_confirm)
                    patternView.clearPattern()
                } else if (firstPattern == encoded) {
                    confirmedPattern = encoded
                    hintText.text = getString(R.string.security_configured)
                    patternView.clearPattern()
                } else {
                    firstPattern = null
                    confirmedPattern = null
                    hintText.text = getString(R.string.security_pattern_draw_new)
                    Toast.makeText(requireContext(), getString(R.string.security_pattern_mismatch), Toast.LENGTH_SHORT).show()
                    patternView.clearPattern()
                }
            }
        }

        clearButton.setOnClickListener {
            firstPattern = null
            confirmedPattern = null
            hintText.text = getString(R.string.security_pattern_draw_new)
            patternView.clearPattern()
        }

        dialog.setOnShowListener {
            applyReadableDialogButtonColors(dialog)
            val okButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            okButton.setOnClickListener {
                val encoded = confirmedPattern
                if (encoded == null) {
                    Toast.makeText(requireContext(), getString(R.string.security_pattern_confirm), Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    credentialStore.savePattern(encoded)
                    onConfigured.invoke()
                    configured = true
                    bindState()
                    dialog.dismiss()
                }
            }
        }

        dialog.setOnDismissListener {
            if (!configured) {
                lifecycleScope.launch { bindState() }
            }
        }

        dialog.show()
    }

    private fun startBiometricActivation(onSuccess: () -> Unit) {
        if (!isBiometricAvailable()) {
            Toast.makeText(requireContext(), getString(R.string.security_biometric_unavailable), Toast.LENGTH_SHORT).show()
            lifecycleScope.launch { bindState() }
            return
        }

        val executor = ContextCompat.getMainExecutor(requireContext())
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess.invoke()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                ) {
                    lifecycleScope.launch { bindState() }
                    return
                }
                Toast.makeText(requireContext(), errString, Toast.LENGTH_SHORT).show()
                lifecycleScope.launch { bindState() }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Toast.makeText(requireContext(), getString(R.string.security_unlock_failed), Toast.LENGTH_SHORT).show()
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.security_unlock_title))
            .setSubtitle(getString(R.string.security_biometric_subtitle))
            .setNegativeButtonText(getString(R.string.cancel))
            .build()

        prompt.authenticate(promptInfo)
    }

    private fun isBiometricAvailable(): Boolean {
        val result = BiometricManager.from(requireContext())
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun applyReadableDialogButtonColors(dialog: AlertDialog) {
        val isDarkMode = runBlocking { preferencesManager.isDarkModeEnabled() }
        val palette = runBlocking { preferencesManager.getThemePalette(isDarkMode) }
        val colors = ThemeManager.resolvePaletteColors(isDarkMode, palette)

        dialog.window?.setBackgroundDrawable(ColorDrawable(colors.drawerSurface))
        dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(colors.onSurfaceSecondary)

        val titleId = requireContext().resources.getIdentifier("alertTitle", "id", "android")
        if (titleId != 0) {
            dialog.findViewById<TextView>(titleId)?.setTextColor(colors.onSurface)
        }

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(colors.onSurface)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(colors.onSurface)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(colors.onSurface)
    }

    private fun encodePattern(pattern: List<Int>): String = pattern.joinToString("-")

    override fun onDestroyView() {
        super.onDestroyView()
        pendingVerifiedAction = null
        _binding = null
    }
}
