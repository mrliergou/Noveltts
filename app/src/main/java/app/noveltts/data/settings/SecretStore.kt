package app.noveltts.data.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.noveltts.engine.DiagnosticsLog
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 Android Keystore 保存少量敏感配置。密文格式为 iv + ciphertext，
 * 只以 Base64 写入 SharedPreferences，不把许可证暴露为普通偏好。
 */
class SecretStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun encryptedValue(key: String): String? = preferences.getString(key, null)

    fun put(key: String, plainText: String?) {
        DiagnosticsLog.registerSensitiveValue(plainText)
        if (plainText.isNullOrBlank()) {
            check(preferences.edit().remove(key).commit()) { "凭据写入失败" }
            return
        }
        run {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val packed = cipher.iv + encrypted
            preferences.edit()
                .putString(key, Base64.encodeToString(packed, Base64.NO_WRAP))
                .commit().also { check(it) { "凭据写入失败" } }
        }
    }

    fun encryptedSnapshot(): Map<String, String> = preferences.all.mapNotNull { (key, value) ->
        (value as? String)?.let { key to it } }.toMap()
    fun restoreEncryptedSnapshot(snapshot: Map<String, String>) {
        val editor = preferences.edit().clear()
        snapshot.forEach { (key, value) -> editor.putString(key, value) }
        check(editor.commit()) { "凭据回滚失败" }
    }
    fun flush() { check(preferences.edit().commit()) { "凭据同步失败" } }

    fun get(key: String): String? {
        val packed = preferences.getString(key, null) ?: return null
        synchronized(decrypted) { decrypted[packed]?.let { return it } }
        return runCatching {
            val bytes = Base64.decode(packed, Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return@runCatching null
            val iv = bytes.copyOfRange(0, IV_BYTES)
            val cipherText = bytes.copyOfRange(IV_BYTES, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
                }.onFailure { DiagnosticsLog.warn(app.noveltts.engine.Category.STORAGE, "凭据解密失败，请重新配置") }
            .getOrNull()?.takeIf { it.isNotBlank() }?.also { value ->
                DiagnosticsLog.registerSensitiveValue(value)
                synchronized(decrypted) {
                    if (decrypted.size >= 4) decrypted.clear()
                    decrypted[packed] = value
                }
            }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        private val decrypted = HashMap<String, String>()
        const val PREFS = "multitts.secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "multitts.license"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
