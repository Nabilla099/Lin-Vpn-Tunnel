package com.linvpn.app.data

import android.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Opsi yang dipasang penyedia ke berkas .ln / tautan. */
data class ShareOptions(
    val password: String = "",
    val days: Int = 0,
    val note: String = "",
    val blockRoot: Boolean = false,
    val lock: Boolean = false
)

class NeedPasswordException : Exception("Berkas dilindungi password")
class WrongPasswordException : Exception("Password salah")

/**
 * Format berkas .ln dan tautan "lin-vpn:..." (v2) - tanpa tanda pengenal tetap (tidak ada "magic bytes"):
 *   salt(16) | iv(12) | AES-256-GCM_B( AES-256-GCM_A( gzip(data) + padding acak + panjang padding ) )
 * Kunci A dan B diturunkan terpisah (HMAC-SHA256) dari kunci akar + salt acak tiap berkas, sehingga dua
 * berkas dari isi yang sama tampak berbeda total. Bot/alat umum tidak mengenali formatnya dan tidak bisa
 * mendekripsinya. Dengan password, kunci akar ikut diturunkan dari password (PBKDF2).
 * Catatan: tanpa password, kunci akar tertanam di aplikasi, jadi hanya menyamarkan; yang kuat adalah password.
 */
object ProfileShare {
    const val PREFIX = "lin-vpn:"
    private const val LEGACY_PREFIX = "link-vpn:"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val rnd = SecureRandom()
    private const val BUILTIN = "LinVpn/profile-share/v2"

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    private fun root(password: String, salt: ByteArray): ByteArray {
        val base = sha256(BUILTIN.toByteArray())
        if (password.isEmpty()) return base
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
        val pk = f.generateSecret(PBEKeySpec(password.toCharArray(), salt, 60000, 256)).encoded
        return sha256(base + pk)
    }

    private fun derive(root: ByteArray, label: String, salt: ByteArray): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(root, "HmacSHA256"))
        m.update(label.toByteArray()); m.update(salt)
        return m.doFinal()
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(data)
    }

    /** Profil terkunci tidak pernah diekspor ulang. */
    fun encode(profiles: List<Profile>, o: ShareOptions): ByteArray {
        val exp = if (o.days > 0) System.currentTimeMillis() + o.days * 86_400_000L else 0L
        val list = profiles.filter { !it.locked }.map {
            it.copy(
                note = o.note.ifBlank { it.note },
                expiresAt = if (exp > 0) exp else it.expiresAt,
                blockRoot = o.blockRoot || it.blockRoot,
                locked = o.lock
            )
        }
        require(list.isNotEmpty()) { "Tidak ada profil yang boleh diekspor (profil terkunci tidak bisa diekspor)" }
        val gz = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(json.encodeToString(list).toByteArray()) } }.toByteArray()
        val pad = ByteArray(rnd.nextInt(256)).also(rnd::nextBytes)
        val plain = gz + pad + byteArrayOf((pad.size shr 8).toByte(), (pad.size and 255).toByte())
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val r = root(o.password, salt)
        val inner = gcm(Cipher.ENCRYPT_MODE, derive(r, "A", salt), derive(r, "IV", salt).copyOf(12), plain)
        val outer = gcm(Cipher.ENCRYPT_MODE, derive(r, "B", salt), iv, inner)
        return salt + iv + outer
    }

    private fun tryDecode(data: ByteArray, password: String): List<Profile>? {
        if (data.size < 16 + 12 + 16 + 16) return null
        return try {
            val salt = data.copyOfRange(0, 16)
            val iv = data.copyOfRange(16, 28)
            val r = root(password, salt)
            val inner = gcm(Cipher.DECRYPT_MODE, derive(r, "B", salt), iv, data.copyOfRange(28, data.size))
            val plain = gcm(Cipher.DECRYPT_MODE, derive(r, "A", salt), derive(r, "IV", salt).copyOf(12), inner)
            val padLen = ((plain[plain.size - 2].toInt() and 255) shl 8) or (plain[plain.size - 1].toInt() and 255)
            val gz = plain.copyOfRange(0, plain.size - 2 - padLen)
            val text = GZIPInputStream(ByteArrayInputStream(gz)).use { String(it.readBytes()) }
            json.decodeFromString<List<Profile>>(text)
        } catch (e: Exception) {
            null
        }
    }

    fun decode(data: ByteArray, password: String): List<Profile> {
        if (password.isEmpty()) {
            return tryDecode(data, "") ?: throw NeedPasswordException()
        }
        return tryDecode(data, password) ?: throw WrongPasswordException()
    }

    /** Tautan yang berisi seluruh data (tanpa server): lin-vpn:<base64url>. */
    fun toLink(data: ByteArray): String =
        PREFIX + Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    /**
     * Teks "lin-vpn:..." bisa berisi: URL http(s) tempat berkas .ln disimpan (diunduh),
     * atau data base64url langsung. Hasilnya berupa byte berkas .ln.
     */
    fun resolve(text: String): ByteArray {
        var t = text.trim()
        if (t.startsWith(PREFIX, ignoreCase = true)) t = t.substring(PREFIX.length).trim()
        else if (t.startsWith(LEGACY_PREFIX, ignoreCase = true)) t = t.substring(LEGACY_PREFIX.length).trim()
        require(t.isNotEmpty()) { "Tautan kosong" }
        if (t.startsWith("http://", true) || t.startsWith("https://", true)) return fetch(t)
        return Base64.decode(t, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun fetch(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "LinVpn")
        try {
            require(c.responseCode == 200) { "Server membalas ${c.responseCode}" }
            val raw = c.inputStream.use { it.readBytes() }
            require(raw.size <= 1_000_000) { "Berkas terlalu besar" }
            // Server boleh menyimpan berkas apa adanya (biner) atau sebagai teks base64url
            val txt = String(raw, Charsets.ISO_8859_1).trim().removePrefix(PREFIX).removePrefix(LEGACY_PREFIX)
            return if (txt.length > 40 && txt.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' })
                Base64.decode(txt, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            else raw
        } finally {
            c.disconnect()
        }
    }
}
