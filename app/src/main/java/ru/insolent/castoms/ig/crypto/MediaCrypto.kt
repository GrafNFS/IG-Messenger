package ru.insolent.castoms.ig.crypto

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object MediaCrypto {
    private const val TAG = "MediaCrypto"
    private const val AES_KEY_SIZE = 256
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    fun generateAESKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance("AES")
        keyGenerator.init(AES_KEY_SIZE, SecureRandom())
        return keyGenerator.generateKey()
    }

    fun encryptFile(inputFile: File, outputFile: File, key: SecretKey) {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        FileInputStream(inputFile).use { input ->
            FileOutputStream(outputFile).use { output ->
                output.write(iv) // Сохраняем IV в начало файла

                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val encrypted = cipher.update(buffer, 0, bytesRead)
                    if (encrypted != null) output.write(encrypted)
                }
                val finalBlock = cipher.doFinal()
                if (finalBlock != null) output.write(finalBlock)
            }
        }
        Log.d(TAG, "Encrypted: ${inputFile.length()} → ${outputFile.length()} bytes")
    }

    /**
     * Расшифровывает файл AES-GCM
     */
    fun decryptFile(inputFile: File, outputFile: File, key: SecretKey) {
        FileInputStream(inputFile).use { input ->
            // Читаем IV из начала файла
            val iv = ByteArray(GCM_IV_LENGTH)
            if (input.read(iv) != GCM_IV_LENGTH) {
                throw Exception("Invalid encrypted file: cannot read IV")
            }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val decrypted = cipher.update(buffer, 0, bytesRead)
                    if (decrypted != null) {
                        output.write(decrypted)
                    }
                }

                // Final block
                val finalBlock = cipher.doFinal()
                if (finalBlock != null) {
                    output.write(finalBlock)
                }
            }
        }

        Log.d(TAG, "Decrypted: ${inputFile.length()} → ${outputFile.length()} bytes")
    }

    fun keyToBytes(key: SecretKey): ByteArray = key.encoded
    fun bytesToKey(keyBytes: ByteArray): SecretKey = javax.crypto.spec.SecretKeySpec(keyBytes, "AES")
}