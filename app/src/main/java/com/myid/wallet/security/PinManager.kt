package com.myid.wallet.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Stores a salted PBKDF2 verifier, never the PIN itself.
 * Production release should add persistent lockout/backoff and security review.
 */
class PinManager(context: Context) {
    private val prefs = context.getSharedPreferences("wallet_pin", Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = prefs.contains("salt") && prefs.contains("verifier")

    fun create(pin: String): Boolean {
        if (pin.length < 8 || pin.any { !it.isDigit() }) return false
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val verifier = derive(pin, salt)
        prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("verifier", Base64.encodeToString(verifier, Base64.NO_WRAP))
            .apply()
        return true
    }

    fun verify(pin: String): Boolean {
        val saltText = prefs.getString("salt", null) ?: return false
        val verifierText = prefs.getString("verifier", null) ?: return false
        val salt = Base64.decode(saltText, Base64.NO_WRAP)
        val expected = Base64.decode(verifierText, Base64.NO_WRAP)
        val actual = derive(pin, salt)
        return java.security.MessageDigest.isEqual(expected, actual)
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 310_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
