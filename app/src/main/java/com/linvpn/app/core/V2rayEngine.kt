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

        var override: Pair<String, Int>? = null
        if (profile.type == ProfileType.V2RAY_SLOWDNS) {
            val cands = SlowDnsClient.candidates(profile)
            val cand = cands.first()
            if (profile.dnsRecord == DnsRecordType.AUTO) {
                LogBus.add("Tipe SlowDNS Otomatis untuk V2Ray memakai ${cand.label}; pilih tipe lain bila server berbeda")
            }
            val port = Net.freePort()
            val c = SlowDnsClient(ctx, profile, cand, port)
            dns = c
            c.start()
            if (stopped) throw IOException("Dibatalkan")
            override = "127.0.0.1" to port
        }

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
            "GOMEMLIMIT" to "192MiB"
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
