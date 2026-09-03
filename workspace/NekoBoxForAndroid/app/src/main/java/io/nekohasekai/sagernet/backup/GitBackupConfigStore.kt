package io.nekohasekai.sagernet.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class GitBackupConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences("git_backup_secure", Context.MODE_PRIVATE)

    fun load(): GitBackupConfig? {
        val encoded = preferences.getString(valueKey, null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > nonceSize)
            val cipher = Cipher.getInstance(transformation)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, bytes.copyOfRange(0, nonceSize)),
            )
            val json = JSONObject(
                cipher.doFinal(bytes, nonceSize, bytes.size - nonceSize).toString(Charsets.UTF_8),
            )
            GitBackupConfig(
                json.getString("repositoryUrl"),
                json.optString("username"),
                json.getString("branch"),
                json.optString("credential"),
                json.getString("encryptionPassword"),
            )
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun save(config: GitBackupConfig) {
        val json = JSONObject().apply {
            put("repositoryUrl", config.repositoryUrl)
            put("username", config.username)
            put("branch", config.branch)
            put("credential", config.credential)
            put("encryptionPassword", config.encryptionPassword)
        }.toString().toByteArray()
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.iv + cipher.doFinal(json)
        preferences.edit {
            putString(valueKey, Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }
    }

    fun clear() {
        preferences.edit { clear() }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val alias = "nekobox_plus_git_backup"
        const val valueKey = "configuration"
        const val transformation = "AES/GCM/NoPadding"
        const val nonceSize = 12
    }
}
