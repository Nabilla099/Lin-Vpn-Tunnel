package com.linvpn.app.core

import android.content.Context
import com.linvpn.app.data.DnsRecordType
import com.linvpn.app.data.DnsTransport
import com.linvpn.app.data.Profile
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.net.Inet4Address
import java.io.IOException

/**
 * Menjalankan klien DNS-tunnel lokal yang membuka 127.0.0.1:port dan meneruskan TCP
 * lewat kueri DNS ke server SlowDNS (NS + public key).
 *  - TXT  -> dnstt-client standar (libdnstt.so)
 *  - lain -> vaydns-client dengan -record-type (libvaydns.so)
 */
class SlowDnsClient(
    private val ctx: Context,
    private val p: Profile,
    private val cand: Candidate,
    val port: Int
) {
    enum class Bin(val lib: String) { DNSTT("libdnstt.so"), VAYDNS("libvaydns.so") }

    data class Candidate(val bin: Bin, val record: String?) {
        val label: String
            get() = if (bin == Bin.DNSTT) "TXT (dnstt)" else (record ?: "txt").uppercase() + " (VayDNS)"
    }

    private var proc: NativeProcess? = null

    fun start() {
        validate(p)
        val lib = File(ctx.applicationInfo.nativeLibraryDir, cand.bin.lib)
        if (!lib.exists()) throw IOException("${cand.bin.lib} tidak ada di APK (build native belum dijalankan?)")
        val key = p.pubKey.trim().lowercase()
        val ns = p.nsDomain.trim().trim('.')
        val (flag, target) = transport()

        val args = ArrayList<String>()
        args += lib.absolutePath
        args += flag
        args += target
        args += listOf("-pubkey", key)
        if (cand.bin == Bin.DNSTT) {
            // dnstt-client [flags] DOMAIN LOCALADDR
            args += ns
            args += "127.0.0.1:$port"
        } else {
            args += listOf("-domain", ns, "-listen", "127.0.0.1:$port")
            cand.record?.let { args += listOf("-record-type", it) }
        }
        LogBus.add("SlowDNS ${cand.label} · ${p.dnsTransport.name} → $target · NS $ns")

        val env = mapOf("GOMEMLIMIT" to "96MiB", "GOMAXPROCS" to "2")
        val np = NativeProcess("slowdns", args, env) { line ->
            // Sembunyikan log per-stream yang sangat ramai
            if (line.contains("forged DNS responses")) {
                LogBus.add("Peringatan: jawaban DNS dipalsukan jaringan. Kosongkan kolom Resolver DNS agar memakai DNS jaringan, atau coba resolver lain/DoH.")
            }
            !(line.contains("stream", true) && !line.contains("err", true) && !line.contains("fail", true))
        }
        proc = np
        np.start()
        if (!Net.waitPort(port, 15000) { np.isAlive() }) {
            stop()
            throw IOException("Klien SlowDNS gagal berjalan (cek NS, public key, atau resolver DNS)")
        }
    }

    fun isAlive(): Boolean = proc?.isAlive() == true

    fun stop() {
        proc?.stop()
        proc = null
    }

    private fun transport(): Pair<String, String> {
        var s = p.dnsServer.trim()
        val h = p.host.trim()
        if (h.isNotEmpty() && p.dnsTransport == DnsTransport.UDP) {
            // Host SlowDNS (mis. selowdns.example.com:53) = alamat server DNS yang dikueri langsung.
            s = if (h.contains(':')) h else "$h:53"
            LogBus.add("Host SlowDNS: $s")
        }
        if (s.isEmpty() && p.dnsTransport == DnsTransport.UDP) {
            val sys = systemDns(ctx)
            if (sys.isEmpty()) throw IOException("DNS jaringan tidak ditemukan; isi Resolver DNS secara manual")
            s = sys.first()
            LogBus.add("Resolver: DNS jaringan ($s)")
        }
        return when (p.dnsTransport) {
            DnsTransport.UDP -> "-udp" to (if (s.contains(':')) s else "$s:53")
            DnsTransport.DOT -> "-dot" to (if (s.contains(':')) s else "$s:853")
            DnsTransport.DOH -> "-doh" to (if (s.startsWith("http")) s else "https://$s/dns-query")
        }
    }

    companion object {
        /** Server DNS (IPv4) milik jaringan aktif non-VPN, mis. DNS operator seluler/Wi-Fi. */
        fun systemDns(ctx: Context): List<String> = try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val out = LinkedHashSet<String>()
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                cm.getLinkProperties(n)?.dnsServers?.forEach { a ->
                    if (a is Inet4Address && !a.isLoopbackAddress) a.hostAddress?.let { out += it }
                }
            }
            out.toList()
        } catch (_: Throwable) {
            emptyList()
        }

        fun validate(p: Profile) {
            require(p.nsDomain.isNotBlank()) { "Name Server (NS) belum diisi" }
            require(p.dnsTransport == DnsTransport.UDP || p.dnsServer.isNotBlank()) { "Resolver DNS belum diisi" }
            require(p.pubKey.trim().matches(Regex("[0-9a-fA-F]{64}"))) { "Public key harus 64 karakter hex" }
        }

        /** Urutan percobaan menurut tipe koneksi yang dipilih. */
        fun candidates(p: Profile): List<Candidate> = when (p.dnsRecord) {
            DnsRecordType.AUTO -> listOf(
                Candidate(Bin.DNSTT, null),
                Candidate(Bin.VAYDNS, "null"),
                Candidate(Bin.VAYDNS, "txt")
            )
            DnsRecordType.TXT -> listOf(Candidate(Bin.DNSTT, null))
            else -> listOf(Candidate(Bin.VAYDNS, p.dnsRecord.arg))
        }
    }
}
