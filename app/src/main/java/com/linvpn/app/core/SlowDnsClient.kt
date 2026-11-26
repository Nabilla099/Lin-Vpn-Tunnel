package com.linvpn.app.core

import android.content.Context
import com.linvpn.app.data.DnsRecordType
import com.linvpn.app.data.DnsTransport
import com.linvpn.app.data.Profile
import java.io.File
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
        val s = p.dnsServer.trim()
        return when (p.dnsTransport) {
            DnsTransport.UDP -> "-udp" to (if (s.contains(':')) s else "$s:53")
            DnsTransport.DOT -> "-dot" to (if (s.contains(':')) s else "$s:853")
            DnsTransport.DOH -> "-doh" to (if (s.startsWith("http")) s else "https://$s/dns-query")
        }
    }

    companion object {
        fun validate(p: Profile) {
            require(p.nsDomain.isNotBlank()) { "Name Server (NS) belum diisi" }
            require(p.dnsServer.isNotBlank()) { "Resolver DNS belum diisi" }
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
