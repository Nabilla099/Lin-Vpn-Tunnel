package com.linvpn.app.core

import android.content.Context
import com.linvpn.app.data.DnsRecordType
import com.linvpn.app.data.Profile
import com.linvpn.app.data.ProfileType
import java.io.File
import java.io.IOException

/** V2Ray / V2Ray + SlowDNS: menjalankan Xray sebagai proses dengan inbound SOCKS lokal. */
class V2rayEngine(
    private val ctx: Context,
    private val profile: Profile,
    private val socksPort: Int
) : Engine {
    private var proc: NativeProcess? = null
    private var dns: SlowDnsClient? = null
    private var cfgFile: File? = null

    @Volatile
    private var stopped = false

    override fun start() {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libxray.so")
        if (!bin.exists()) throw IOException("libxray.so tidak ada di APK (build native belum dijalankan?)")
        if (profile.v2rayConfig.isBlank()) throw IOException("Konfigurasi V2Ray kosong")

        if (profile.type != ProfileType.V2RAY_SLOWDNS) {
            launchXray(bin, null)
            return
        }
        // V2Ray + SlowDNS: coba tiap tipe DNS sampai terowongan benar-benar membawa lalu lintas.
        val cands = SlowDnsClient.candidates(profile)
        var last: Exception? = null
        for ((i, cand) in cands.withIndex()) {
            if (stopped) throw IOException("Dibatalkan")
            try {
                attempt(bin, cand, if (cands.size > 1) 20_000 else 40_000)
                return
            } catch (e: Exception) {
                last = e
                teardownAttempt()
                if (stopped) throw IOException("Dibatalkan")
                if (i < cands.size - 1) LogBus.add("Tipe ${cand.label} belum berhasil; mencoba tipe berikutnya")
            }
        }
        throw IOException(last?.message ?: "Terowongan SlowDNS gagal")
    }

    private fun attempt(bin: File, cand: SlowDnsClient.Candidate, probeMs: Int) {
        val port = Net.freePort()
        val c = SlowDnsClient(ctx, profile, cand, port)
        dns = c
        c.start()
        if (stopped) throw IOException("Dibatalkan")
        launchXray(bin, "127.0.0.1" to port)
        LogBus.add("Menguji terowongan SlowDNS (${cand.label})…")
        var ok = false
        val deadline = System.currentTimeMillis() + probeMs
        while (!stopped && !ok && System.currentTimeMillis() < deadline && proc?.isAlive() == true) {
            ok = NetInfo.probeThroughSocks(socksPort, "connectivitycheck.gstatic.com", "/generate_204", 8_000)
            if (!ok) Thread.sleep(500)
        }
        if (!ok) throw IOException("Terowongan SlowDNS tidak merespons (jabat tangan belum selesai). Periksa NS, public key, dan tipe SlowDNS")
        LogBus.add("Terowongan SlowDNS aktif (${cand.label})")
    }

    private fun teardownAttempt() {
        proc?.stop(); proc = null
        dns?.stop(); dns = null
    }

    private fun launchXray(bin: File, override: Pair<String, Int>?) {
        val cfg = try {
            V2rayConfig.prepare(profile.v2rayConfig, socksPort, override)
        } catch (e: Exception) {
            throw IOException("Konfigurasi V2Ray tidak valid: ${e.message}")
        }
        val f = File(ctx.filesDir, "xray.json")
        f.writeText(cfg)
        cfgFile = f

        LogBus.add("Memulai V2Ray (Xray)")
        val env = mapOf(
            "XRAY_LOCATION_ASSET" to ctx.filesDir.absolutePath,
            "xray.location.asset" to ctx.filesDir.absolutePath,
            "GOMEMLIMIT" to "96MiB",
            "GOGC" to "50",
            "GOMAXPROCS" to "2",
            "GODEBUG" to "madvdontneed=1"
        )
        val np = NativeProcess("xray", listOf(bin.absolutePath, "run", "-c", f.absolutePath), env)
        proc = np
        np.start()
        if (!Net.waitPort(socksPort, 15000) { np.isAlive() }) {
            stop()
            throw IOException("Xray gagal berjalan, periksa konfigurasi (lihat log)")
        }
        LogBus.add("V2Ray aktif di 127.0.0.1:$socksPort")
    }

    override fun isAlive(): Boolean = proc?.isAlive() == true && (dns?.isAlive() ?: true)

    override fun stop() {
        stopped = true
        proc?.stop()
        proc = null
        dns?.stop()
        dns = null
        cfgFile?.delete()
        cfgFile = null
    }
}
