package com.myid.wallet.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts each file independently using AES-256-GCM and a fresh random IV.
 * The AES key is non-exportable and protected by Android Keystore.
 */
class EncryptionManager {
    private val alias = "myid_wallet_file_key"

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain)
        val output = ByteArrayOutputStream()
        output.write(cipher.iv.size)
        output.write(cipher.iv)
        output.write(encrypted)
        return output.toByteArray()
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.isNotEmpty()) { "Encrypted file is empty." }
        val ivLength = blob[0].toInt() and 0xFF
        require(ivLength in 12..16 && blob.size > 1 + ivLength) {
            "Encrypted file format is invalid."
        }
        val iv = blob.copyOfRange(1, 1 + ivLength)
        val ciphertext = blob.copyOfRange(1 + ivLength, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, iv)
        )
        return cipher.doFinal(ciphertext)
    }

    fun deleteKey() {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
    }
}
