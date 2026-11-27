package com.linvpn.app.data

import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.util.UUID

enum class ProfileType(val label: String) {
    SSH_PAYLOAD("SSH + WebSocket / Payload"),
    SSH_TLS("SSH + TLS"),
    SSH_SLOWDNS("SSH + SlowDNS"),
    V2RAY("V2Ray"),
    V2RAY_SLOWDNS("V2Ray + SlowDNS");

    val isSsh get() = this == SSH_PAYLOAD || this == SSH_TLS || this == SSH_SLOWDNS
    val isV2ray get() = this == V2RAY || this == V2RAY_SLOWDNS
    val usesSlowDns get() = this == SSH_SLOWDNS || this == V2RAY_SLOWDNS
}

enum class DnsTransport(val label: String) {
    UDP("UDP (DNS biasa)"),
    DOH("DoH (HTTPS)"),
    DOT("DoT (TLS)")
}

/**
 * Tipe koneksi SlowDNS = jenis record DNS yang dipakai membawa data.
 * TXT memakai klien dnstt standar; tipe lain memakai klien VayDNS dan server
 * harus dikonfigurasi dengan tipe yang sama. "Otomatis" mencoba beberapa tipe berurutan.
 */
enum class DnsRecordType(val label: String, val arg: String?) {
    AUTO("Otomatis", null),
    TXT("TXT", "txt"),
    NULL("NULL", "null"),
    CNAME("CNAME", "cname"),
    A("A", "a"),
    AAAA("AAAA", "aaaa"),
    MX("MX", "mx"),
    NS("NS", "ns"),
    SRV("SRV", "srv"),
    CAA("CAA", "caa")
}

@Serializable
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Profil baru",
    val type: ProfileType = ProfileType.SSH_PAYLOAD,
    // Akun SSH
    val host: String = "",
    val port: Int = 80,
    val username: String = "",
    val password: String = "",
    val privateKey: String = "",
    // Payload / TLS
    val payload: String = "",
    val sni: String = "",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    // SlowDNS
    val dnsTransport: DnsTransport = DnsTransport.UDP,
    val dnsRecord: DnsRecordType = DnsRecordType.AUTO,
    val dnsServer: String = "", // kosong = pakai DNS perangkat/operator (disarankan)
    val nsDomain: String = "",
    val pubKey: String = "",
    // V2Ray
    val v2rayConfig: String = "",
    // Dari penyedia (berkas/tautan impor)
    val note: String = "",          // catatan; boleh memakai HTML/CSS sederhana untuk warna
    val expiresAt: Long = 0,        // epoch ms; 0 = tidak kedaluwarsa
    val blockRoot: Boolean = false, // tolak perangkat root
    val locked: Boolean = false     // kunci penyedia: detail disembunyikan, tidak bisa diubah/diekspor
) {
    fun isExpired(now: Long = System.currentTimeMillis()) = expiresAt > 0 && now > expiresAt

    fun summary(): String = if (locked) "Dikunci penyedia" else when (type) {
        ProfileType.SSH_PAYLOAD, ProfileType.SSH_TLS ->
            "${username.ifBlank { "?" }}@${host.ifBlank { "?" }}:$port"
        ProfileType.SSH_SLOWDNS -> "${username.ifBlank { "?" }} · NS ${nsDomain.ifBlank { "?" }}"
        ProfileType.V2RAY -> v2rayAddress() ?: "Konfigurasi V2Ray"
        ProfileType.V2RAY_SLOWDNS -> "NS ${nsDomain.ifBlank { "?" }}"
    }

    private fun v2rayAddress(): String? = try {
        val outs = JSONObject(v2rayConfig).getJSONArray("outbounds")
        var res: String? = null
        for (i in 0 until outs.length()) {
            val s = outs.getJSONObject(i).optJSONObject("settings") ?: continue
            val arr = s.optJSONArray("vnext") ?: s.optJSONArray("servers")
            val o = if (arr != null && arr.length() > 0) arr.getJSONObject(0) else s
            val a = o.optString("address")
            if (a.isNotBlank()) {
                res = "$a:${o.optString("port")}"
                break
            }
        }
        res
    } catch (e: Exception) {
        null
    }
}

@Serializable
data class AppSettings(
    // Tampilan
    val theme: Int = 0, // 0 sistem, 1 terang, 2 gelap
    val dynamicColor: Boolean = true,
    // Koneksi
    val autoReconnect: Boolean = true,
    val maxRetries: Int = 0, // 0 = tanpa batas
    val retryDelaySec: Int = 3,
    val reconnectOnNetwork: Boolean = true,
    val connectTimeoutSec: Int = 30,
    val keepAliveSec: Int = 15,
    val compression: Boolean = false,
    val legacySsh: Boolean = false, // izinkan algoritma SSH lawas (hanya untuk server tua)
    val wakeLock: Boolean = true,
    val startOnBoot: Boolean = false,
    // Jaringan
    val socksPort: Int = 10808,
    val mtu: Int = 1500,
    val dnsMode: Int = 0, // 0 = peta DNS lewat terowongan, 1 = DNS kustom
    val dns1: String = "8.8.8.8",
    val dns2: String = "1.1.1.1",
    val ipv6: Boolean = false,
    val bypassLan: Boolean = true,
    val udpgw: Boolean = true,
    val udpgwPort: Int = 7300,
    // Per aplikasi
    val appMode: Int = 0, // 0 semua, 1 hanya terpilih, 2 kecuali terpilih
    val apps: Set<String> = emptySet(),
    // Log
    val autoScroll: Boolean = true,
    val logMax: Int = 500,
    val clearLogOnConnect: Boolean = true,
    // Notifikasi
    val showSpeedNotif: Boolean = true,
    val showBanner: Boolean = true,
    // Lain-lain
    val lastProfileId: String = ""
)
