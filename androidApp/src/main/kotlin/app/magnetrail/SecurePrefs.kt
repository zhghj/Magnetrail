package app.magnetrail

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 本地敏感数据的静态加密（Android Keystore + AES/GCM）。
 *
 * 用来保护浏览历史、收藏、磁力任务这类"被拿到 prefs 文件就等于泄露"的数据：
 * 密钥在 Keystore 里（不可导出），文件被拷走也解不开。
 *
 * 兼容旧数据：解密时如果不是 `enc1:` 前缀，原样返回；下次写入自动变成密文。
 */
object SecurePrefs {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "magnetrail.prefs.v1"
    private const val PREFIX = "enc1:"
    private const val GCM_TAG_BITS = 128

    fun encrypt(plain: String): String {
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    fun decrypt(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        return runCatching {
            val body = value.removePrefix(PREFIX)
            val iv = Base64.decode(body.substringBefore(':'), Base64.NO_WRAP)
            val ct = Base64.decode(body.substringAfter(':'), Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
