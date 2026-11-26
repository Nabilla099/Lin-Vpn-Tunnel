package com.linvpn.app.core

import android.content.Context
import com.linvpn.app.data.DnsRecordType
import com.linvpn.app.data.DnsTransport
import com.linvpn.app.data.Profile
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.io.ByteArrayOutputStream
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

    data class Candidate(val bin: Bin, val record: String?, val compat: Boolean = false) {
        val label: String
            get() = when {
                bin == Bin.DNSTT -> "TXT (dnstt)"
                compat -> "TXT (VayDNS kompatibel dnstt)"
                else -> (record ?: "txt").uppercase() + " (VayDNS)"
            }
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
            if (cand.compat) args += "-dnstt-compat" else cand.record?.let { args += listOf("-record-type", it) }
        }
        if (p.dnsTransport == DnsTransport.UDP) probe(target, ns)
        LogBus.add("SlowDNS ${cand.label} · ${p.dnsTransport.name} → $target · NS $ns")

        val env = mapOf("GOMEMLIMIT" to "96MiB", "GOMAXPROCS" to "2")
        val np = NativeProcess("slowdns", args, env) { line ->
            if (line.contains("forged DNS responses")) {
                LogBus.add("Peringatan: resolver membalas dengan jawaban palsu untuk tipe ini. Coba tipe TXT atau resolver/DoH lain.")
            }
            true
        }
        proc = np
        np.start()
        if (!Net.waitPort(port, 15000) { np.isAlive() }) {
            stop()
            throw IOException("Klien SlowDNS gagal berjalan (cek NS, public key, atau resolver DNS)")
        }
    }

    /** Uji cepat: kirim satu kueri TXT ke server DNS lalu catat balasannya (membantu diagnosa jaringan). */
    private fun probe(target: String, ns: String) {
        try {
            val host = target.substringBeforeLast(':')
            val port = target.substringAfterLast(':').toIntOrNull() ?: 53
            val addr = try {
                InetAddress.getByName(host)
            } catch (e: Exception) {
                LogBus.add("Uji DNS: nama $host tidak bisa di-resolve"); return
            }
            val id = (Math.random() * 65535).toInt()
            val q = ByteArrayOutputStream()
            q.write(id shr 8); q.write(id and 255)
            q.write(byteArrayOf(1, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            for (label in ("t" + (Math.random() * 99999).toInt() + "." + ns).split('.')) {
                val b = label.toByteArray(); q.write(b.size); q.write(b)
            }
            q.write(0); q.write(byteArrayOf(0, 16, 0, 1))
            val data = q.toByteArray()
            DatagramSocket().use { sock ->
                sock.soTimeout = 3000
                val t0 = System.currentTimeMillis()
                sock.send(DatagramPacket(data, data.size, addr, port))
                val buf = ByteArray(2048)
                val pk = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pk)
                    val rcode = buf[3].toInt() and 15
                    val an = ((buf[6].toInt() and 255) shl 8) or (buf[7].toInt() and 255)
                    LogBus.add("Uji DNS ${addr.hostAddress}:$port: balasan ${pk.length} byte, RCODE=$rcode, jawaban=$an, ${System.currentTimeMillis() - t0} ms")
                } catch (e: java.net.SocketTimeoutException) {
                    LogBus.add("Uji DNS ${addr.hostAddress}:$port: TIDAK ADA balasan (UDP 53 ke server ini diblokir atau alamat salah)")
                }
            }
        } catch (e: Throwable) {
            LogBus.add("Uji DNS gagal: ${e.message}")
        }
    }

    fun isAlive(): Boolean = proc?.isAlive() == true

    fun stop() {
        proc?.stop()
        proc = null
    }

    private fun transport(): Pair<String, String> {
        var s = p.dnsServer.trim()
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
                Candidate(Bin.VAYDNS, "txt", compat = true),
                Candidate(Bin.VAYDNS, "null"),
                Candidate(Bin.VAYDNS, "txt")
            )
            DnsRecordType.TXT -> listOf(Candidate(Bin.DNSTT, null))
            else -> listOf(Candidate(Bin.VAYDNS, p.dnsRecord.arg))
        }
    }
}
