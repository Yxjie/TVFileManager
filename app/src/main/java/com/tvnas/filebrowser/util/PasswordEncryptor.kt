package com.tvnas.filebrowser.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密码加密工具
 * 使用 Android Keystore 的 AES/GCM 加密，安全存储 SMB 密码
 */
object PasswordEncryptor {

    private const val KEY_ALIAS = "tv_file_manager_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val SEPARATOR = ":"

    /**
     * 加密密码，返回 Base64 编码的 "IV:ciphertext" 字符串
     * 如果加密失败则返回 null
     */
    fun encrypt(plainText: String): String? {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
            val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val dataBase64 = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            "$ivBase64$SEPARATOR$dataBase64"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解密之前加密的密码字符串
     * 如果解密失败（包括密钥丢失、数据损坏）则返回 null
     */
    fun decrypt(encryptedText: String?): String? {
        if (encryptedText == null || !encryptedText.contains(SEPARATOR)) return null
        return try {
            val parts = encryptedText.split(SEPARATOR, limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }
}
