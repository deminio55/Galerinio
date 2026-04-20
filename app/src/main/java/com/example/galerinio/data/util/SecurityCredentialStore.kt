package com.example.galerinio.data.util

import android.content.Context
import android.util.Base64
import java.security.MessageDigest

/**
 * Stores hashed credentials in private SharedPreferences.
 * Hashing is used to avoid storing raw PIN/pattern text.
 */
class SecurityCredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun hasPin(): Boolean = !prefs.getString(KEY_PIN_HASH, null).isNullOrEmpty()

    fun hasPattern(): Boolean = !prefs.getString(KEY_PATTERN_HASH, null).isNullOrEmpty()

    fun savePin(pin: String) {
        prefs.edit().putString(KEY_PIN_HASH, hash(pin)).apply()
    }

    fun savePattern(pattern: String) {
        prefs.edit().putString(KEY_PATTERN_HASH, hash(pattern)).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val current = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return current == hash(pin)
    }

    fun verifyPattern(pattern: String): Boolean {
        val current = prefs.getString(KEY_PATTERN_HASH, null) ?: return false
        return current == hash(pattern)
    }

    fun clearPin() {
        prefs.edit().remove(KEY_PIN_HASH).apply()
    }

    fun clearPattern() {
        prefs.edit().remove(KEY_PATTERN_HASH).apply()
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.trim().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private companion object {
        private const val PREF_NAME = "security_credentials"
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_PATTERN_HASH = "pattern_hash"
    }
}

