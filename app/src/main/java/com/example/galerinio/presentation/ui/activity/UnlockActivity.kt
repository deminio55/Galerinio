package com.example.galerinio.presentation.ui.activity

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.galerinio.R
import com.example.galerinio.data.util.PreferencesManager
import com.example.galerinio.data.util.SecurityCredentialStore
import com.example.galerinio.databinding.ActivityUnlockBinding
import com.example.galerinio.presentation.ui.util.ThemeManager
import kotlinx.coroutines.launch

class UnlockActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_UNLOCK_SCOPE = "unlock_scope"
        const val SCOPE_APP = "scope_app"
        const val SCOPE_FOLDER = "scope_folder"
        const val SCOPE_SETTINGS = "scope_settings"
    }

    private lateinit var binding: ActivityUnlockBinding
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var credentialStore: SecurityCredentialStore

    private var pinEnabled = false
    private var patternEnabled = false
    private var biometricEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        binding = ActivityUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)

        preferencesManager = PreferencesManager(this)
        credentialStore = SecurityCredentialStore(this)
        applyUnlockTheme()

        binding.btnUnlock.setOnClickListener { onPrimaryActionClicked() }
        binding.etPin.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            onPrimaryActionClicked()
            true
        }
        binding.patternLockView.onPatternDetected = { pattern ->
            onPatternDrawn(pattern)
        }

        lifecycleScope.launch {
            when (intent.getStringExtra(EXTRA_UNLOCK_SCOPE)) {
                SCOPE_FOLDER -> {
                    when (preferencesManager.getFolderLockMethod()) {
                        PreferencesManager.LockMethod.PIN -> pinEnabled = credentialStore.hasPin()
                        PreferencesManager.LockMethod.PATTERN -> patternEnabled = credentialStore.hasPattern()
                        PreferencesManager.LockMethod.BIOMETRIC -> biometricEnabled = true
                        PreferencesManager.LockMethod.NONE -> Unit
                    }
                }
                SCOPE_SETTINGS -> {
                    val appMethod = preferencesManager.getAppLockMethod()
                    val folderMethod = preferencesManager.getFolderLockMethod()
                    val selected = if (appMethod != PreferencesManager.LockMethod.NONE) appMethod else folderMethod
                    when (selected) {
                        PreferencesManager.LockMethod.PIN -> pinEnabled = credentialStore.hasPin()
                        PreferencesManager.LockMethod.PATTERN -> patternEnabled = credentialStore.hasPattern()
                        PreferencesManager.LockMethod.BIOMETRIC -> biometricEnabled = true
                        PreferencesManager.LockMethod.NONE -> Unit
                    }
                }
                else -> {
                    when (preferencesManager.getAppLockMethod()) {
                        PreferencesManager.LockMethod.PIN -> pinEnabled = credentialStore.hasPin()
                        PreferencesManager.LockMethod.PATTERN -> patternEnabled = credentialStore.hasPattern()
                        PreferencesManager.LockMethod.BIOMETRIC -> biometricEnabled = true
                        PreferencesManager.LockMethod.NONE -> Unit
                    }
                }
            }

            renderUi()
            if (biometricEnabled && isBiometricAvailable()) {
                startBiometricAuth()
            }
        }
    }

    private fun renderUi() {
        binding.pinContainer.visibility = if (pinEnabled) android.view.View.VISIBLE else android.view.View.GONE
        binding.patternContainer.visibility = if (patternEnabled) android.view.View.VISIBLE else android.view.View.GONE

        if (!pinEnabled && !patternEnabled && !biometricEnabled) {
            finishWithSuccess()
            return
        }

        binding.btnUnlock.text = when {
            pinEnabled -> getString(R.string.security_unlock_now)
            biometricEnabled -> getString(R.string.security_unlock_biometric)
            else -> getString(R.string.cancel)
        }
    }

    private fun onPrimaryActionClicked() {
        when {
            pinEnabled -> unlockWithCredentials()
            biometricEnabled -> startBiometricAuth()
            else -> finishWithFailure()
        }
    }

    private fun unlockWithCredentials(): Boolean {
        val enteredPin = binding.etPin.text?.toString().orEmpty()

        val pinOk = pinEnabled && enteredPin.length >= 4 && credentialStore.verifyPin(enteredPin)

        if (pinOk) {
            finishWithSuccess()
            return true
        } else {
            Toast.makeText(this, getString(R.string.security_unlock_failed), Toast.LENGTH_SHORT).show()
            binding.etPin.text?.clear()
            binding.etPin.requestFocus()
            return false
        }
    }

    private fun applyUnlockTheme() {
        lifecycleScope.launch {
            val isDarkMode = preferencesManager.isDarkModeEnabled()
            val palette = preferencesManager.getThemePalette(isDarkMode)
            val accent = preferencesManager.getThemeAccent(isDarkMode)
            val colors = ThemeManager.resolvePaletteColors(isDarkMode, palette)
            val accentColors = ThemeManager.resolveAccentColors(isDarkMode, accent)

            binding.root.setBackgroundColor(colors.background)
            binding.unlockCard.backgroundTintList = ColorStateList.valueOf(colors.surface)
            binding.tvUnlockTitle.setTextColor(colors.onSurface)
            binding.tvUnlockDesc.setTextColor(colors.onSurfaceSecondary)
            binding.tvPinLabel.setTextColor(colors.onSurface)
            binding.tvPatternLabel.setTextColor(colors.onSurface)
            binding.tvPatternUnlockHint.setTextColor(colors.onSurfaceSecondary)
            binding.etPin.setTextColor(colors.onSurface)
            binding.etPin.setHintTextColor(colors.onSurfaceSecondary)
            binding.btnUnlock.backgroundTintList = ColorStateList.valueOf(accentColors.accent)
            binding.btnUnlock.setTextColor(accentColors.onAccent)
        }
    }

    private fun onPatternDrawn(pattern: List<Int>) {
        if (!patternEnabled) {
            binding.patternLockView.clearPattern()
            return
        }
        if (pattern.size < 4) {
            Toast.makeText(this, getString(R.string.security_pattern_too_short), Toast.LENGTH_SHORT).show()
            binding.patternLockView.clearPattern()
            return
        }
        val encoded = encodePattern(pattern)
        if (credentialStore.verifyPattern(encoded)) {
            finishWithSuccess()
        } else {
            Toast.makeText(this, getString(R.string.security_unlock_failed), Toast.LENGTH_SHORT).show()
            binding.patternLockView.clearPattern()
        }
    }

    private fun encodePattern(pattern: List<Int>): String = pattern.joinToString("-")

    private fun startBiometricAuth() {
        if (!isBiometricAvailable()) {
            Toast.makeText(this, getString(R.string.security_biometric_unavailable), Toast.LENGTH_SHORT).show()
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    finishWithSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        return
                    }
                    Toast.makeText(this@UnlockActivity, errString, Toast.LENGTH_SHORT).show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.security_unlock_title))
            .setSubtitle(getString(R.string.security_biometric_subtitle))
            .setNegativeButtonText(getString(R.string.cancel))
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(this)
        val result = biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        return result == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun finishWithSuccess() {
        lifecycleScope.launch {
            preferencesManager.setLastUnlockElapsedRealtime(android.os.SystemClock.elapsedRealtime())
            setResult(RESULT_OK, Intent())
            finish()
        }
    }

    private fun finishWithFailure() {
        setResult(RESULT_CANCELED, Intent())
        finish()
    }

    override fun onBackPressed() {
        finishWithFailure()
    }
}

