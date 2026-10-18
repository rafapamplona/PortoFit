package com.example.portofit.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** O que fica guardado no aparelho. O access token nunca é guardado (só existe em memória). */
@Serializable
data class StoredSession(
    val userId: String,
    val username: String,
    val refreshToken: String,
) {
    override fun toString() = "StoredSession(userId=$userId, username=$username)"
}

sealed interface StoredSessionResult {
    data object None : StoredSessionResult
    data class Found(val session: StoredSession) : StoredSessionResult

    /** Ficheiro ilegível (ex.: chave do Keystore perdida); já foi apagado. */
    data object Unreadable : StoredSessionResult
}

interface SessionStore {
    fun load(): StoredSessionResult
    fun save(session: StoredSession)
    fun clear()
}

/** Cifra simétrica autenticada. A implementação real usa uma chave do Android Keystore. */
interface SecretCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * Sessão num ficheiro privado da app, cifrado. A pasta (files/session/) está excluída das cópias de
 * segurança e da transferência entre aparelhos (res/xml/backup_rules.xml e data_extraction_rules.xml).
 * Escrita atómica: um ficheiro temporário é escrito e depois substitui o anterior.
 */
class FileSessionStore(
    private val file: File,
    private val cipher: SecretCipher,
    private val json: Json = Json,
) : SessionStore {

    @Synchronized
    override fun load(): StoredSessionResult {
        if (!file.exists()) return StoredSessionResult.None
        return try {
            val plaintext = cipher.decrypt(file.readBytes())
            StoredSessionResult.Found(json.decodeFromString(StoredSession.serializer(), plaintext.decodeToString()))
        } catch (e: Exception) {
            // Chave invalidada, ficheiro corrompido ou de outra versão: a sessão não é recuperável.
            file.delete()
            StoredSessionResult.Unreadable
        }
    }

    @Synchronized
    override fun save(session: StoredSession) {
        file.parentFile?.mkdirs()
        val ciphertext = cipher.encrypt(json.encodeToString(StoredSession.serializer(), session).encodeToByteArray())
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(ciphertext)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    @Synchronized
    override fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }
}

/**
 * AES-256-GCM com uma chave gerada e guardada no Android Keystore (não exportável).
 * Formato: [versão][tamanho do IV][IV][texto cifrado + etiqueta de autenticação].
 */
class KeystoreSecretCipher(private val alias: String = "portofit.session.v1") : SecretCipher {

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        return byteArrayOf(VERSION, iv.size.toByte()) + iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > 2 && ciphertext[0] == VERSION) { "Formato desconhecido" }
        val ivSize = ciphertext[1].toInt()
        val iv = ciphertext.copyOfRange(2, 2 + ivSize)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext, 2 + ivSize, ciphertext.size - 2 - ivSize)
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
    }
}
