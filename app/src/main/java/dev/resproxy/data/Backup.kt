package dev.resproxy.data

import kotlinx.serialization.Serializable
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Everything needed to recreate the setup on another device or Android profile. */
@Serializable
data class BackupPayload(
    val version: Int = 1,
    val createdAt: Long = 0,
    val settings: AppSettings = AppSettings(),
    val accounts: List<Account> = emptyList(),
    val chain: VpnChain? = null,
    val apiKeys: ApiKeys = ApiKeys(),
)

class WrongPassphraseException : IOException("Wrong passphrase or damaged backup")

/**
 * Passphrase-encrypted backup text: `resproxy-backup-v1:` + base64(JSON envelope). The payload is
 * AES-256-GCM with a PBKDF2-HMAC-SHA256 key, so the file/clipboard text is safe to move around.
 */
object BackupCrypto {
    const val PREFIX = "resproxy-backup-v1:"
    private const val ITERATIONS = 210_000

    @Serializable
    private data class Envelope(val kdf: String, val iter: Int, val salt: String, val iv: String, val data: String)

    fun seal(payload: BackupPayload, passphrase: CharArray): String {
        val rng = SecureRandom()
        val salt = ByteArray(16).also(rng::nextBytes)
        val iv = ByteArray(12).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, ITERATIONS), GCMParameterSpec(128, iv))
        cipher.updateAAD(PREFIX.toByteArray())
        val ct = cipher.doFinal(json.encodeToString(BackupPayload.serializer(), payload).toByteArray(Charsets.UTF_8))
        val env = Envelope("pbkdf2-sha256", ITERATIONS, b64(salt), b64(iv), b64(ct))
        return PREFIX + b64(json.encodeToString(Envelope.serializer(), env).toByteArray(Charsets.UTF_8))
    }

    fun looksLikeBackup(text: String) = text.trim().startsWith(PREFIX)

    fun open(text: String, passphrase: CharArray): BackupPayload {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) throw IOException("Not a ResProxy backup")
        val env = runCatching {
            json.decodeFromString(Envelope.serializer(), String(unb64(t.removePrefix(PREFIX).filterNot(Char::isWhitespace)), Charsets.UTF_8))
        }.getOrElse { throw IOException("Damaged backup") }
        if (env.kdf != "pbkdf2-sha256") throw IOException("Unsupported backup format")
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, unb64(env.salt), env.iter), GCMParameterSpec(128, unb64(env.iv)))
            cipher.updateAAD(PREFIX.toByteArray())
            cipher.doFinal(unb64(env.data))
        } catch (e: GeneralSecurityException) {
            throw WrongPassphraseException()
        }
        return json.decodeFromString(BackupPayload.serializer(), String(plain, Charsets.UTF_8))
    }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}
