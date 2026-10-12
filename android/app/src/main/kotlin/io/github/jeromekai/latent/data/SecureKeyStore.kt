package io.github.jeromekai.latent.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The API key, encrypted with a key that never leaves the phone's Keystore.
 *
 * On the camera the key sat in a plain .env file, and the audit found a route
 * that served it to anyone on the LAN. Here it is never written in the clear:
 * SharedPreferences holds only AES-GCM ciphertext, and the decryption key is
 * hardware-backed where the phone supports it.
 */
class SecureKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("latent_secure", Context.MODE_PRIVATE)
    private val _hasKey = MutableStateFlow(prefs.contains(PREF_KEY))
    val hasKey: StateFlow<Boolean> = _hasKey.asStateFlow()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "latent_api_key"
        const val PREF_KEY = "api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    @Synchronized
    fun set(apiKey: String) {
        val trimmed = apiKey.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        val plain = trimmed.toByteArray(Charsets.UTF_8)
        val payload = try {
            encrypt(plain)
        } catch (_: Exception) {
            // A Keystore entry that cannot encrypt (corrupted, or broken by a
            // system update) would fail every save from now on and leave the
            // app with no way to take a key. Whatever it once sealed is
            // replaced by this save anyway, so drop it and start a fresh one.
            runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
            encrypt(plain)
        }
        prefs.edit().putString(PREF_KEY, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
        _hasKey.value = true
    }

    /** The key, or null when none is stored or it can no longer be decrypted. */
    @Synchronized
    fun get(): String? {
        val stored = prefs.getString(PREF_KEY, null) ?: return null
        return try {
            val payload = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES),
            )
            String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            // A restored backup or a reset Keystore leaves ciphertext nothing
            // can open. Drop it so the app asks for the key again.
            clear()
            null
        }
    }

    /** "…abcd": enough to recognise a key without showing it. */
    fun hint(): String? = get()?.takeLast(4)?.let { "…$it" }

    @Synchronized
    fun clear() {
        prefs.edit().remove(PREF_KEY).apply()
        _hasKey.value = false
    }
}
