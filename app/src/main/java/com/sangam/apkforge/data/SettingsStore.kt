package com.sangam.apkforge.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A Claude model the user can pick. */
data class ModelOption(val id: String, val title: String, val note: String)

/**
 * App settings. The Claude API key is encrypted with a key that lives in Android's
 * hardware-backed Keystore, so it can't be read from a copy of the app's files.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = prefs.getString(KEY_API, null)?.let { decrypt(it) }
        set(value) {
            val v = value?.trim().orEmpty()
            prefs.edit().apply {
                if (v.isEmpty()) remove(KEY_API) else putString(KEY_API, encrypt(v))
            }.apply()
        }

    /** Cheap check (no decryption), safe to call while drawing the screen. */
    val hasApiKey: Boolean get() = prefs.contains(KEY_API)

    var workspaceId: String
        get() = prefs.getString(KEY_WORKSPACE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WORKSPACE, value.trim()).apply()

    var model: String
        get() = prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) = prefs.edit().putString(KEY_MODEL, value.trim().ifEmpty { DEFAULT_MODEL }).apply()

    companion object {
        private const val KEY_API = "claude_api_key"
        private const val KEY_WORKSPACE = "claude_workspace_id"
        private const val KEY_MODEL = "claude_model"
        private const val KEYSTORE_ALIAS = "apkforge_settings"

        const val DEFAULT_MODEL = "claude-sonnet-5-5"

        val MODELS = listOf(
            ModelOption("claude-sonnet-5-5", "Sonnet 5.5", "Recommended. Smart and costs less."),
            ModelOption("claude-opus-5-5", "Opus 5.5", "Smartest for hard changes. Costs more."),
            ModelOption("claude-haiku-5-5", "Haiku 5.5", "Fastest and cheapest, for simple changes."),
        )

        /** Shortens a key for display, e.g. "sk-ant-...x9Qa". */
        fun mask(key: String): String = if (key.length <= 12) "****" else key.take(7) + "..." + key.takeLast(4)
    }

    // ---- encryption ----

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    /** Returns null if the stored value can't be decrypted (for example after a phone reset). */
    private fun decrypt(stored: String): String? = try {
        val (iv, data) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
