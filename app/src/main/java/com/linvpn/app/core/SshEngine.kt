package com.linvpn.app.core

import android.content.Context
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import com.linvpn.app.data.AppSettings
import com.linvpn.app.data.Profile
import com.linvpn.app.data.ProfileType
import java.io.IOException

/** SSH (core SSH-2): payload/WebSocket, TLS, SlowDNS. Menyediakan SOCKS5 lokal lewat direct-tcpip. */
class SshEngine(
    private val ctx: Context,
    private val profile: Profile,
    private val st: AppSettings,
    private val socksPort: Int
) : Engine {

    @Volatile
    private var session: Session? = null
    private var proxy: Proxy? = null
    private var socks: Socks5Server? = null
    private var dns: SlowDnsClient? = null

    @Volatile
    private var aborted = false

    override fun start() {
        val timeoutMs = st.connectTimeoutSec.coerceIn(5, 180) * 1000
        if (profile.type == ProfileType.SSH_SLOWDNS) {
            startViaSlowDns(timeoutMs)
        } else {
            val viaProxy = profile.proxyHost.isNotBlank() && profile.proxyPort > 0
            val cfg = TunnelProxy.Config(
                connectHost = if (viaProxy) profile.proxyHost.trim() else profile.host.trim(),
                connectPort = if (viaProxy) profile.proxyPort else profile.port,
                targetHost = profile.host.trim(),
                targetPort = profile.port,
                tls = profile.type == ProfileType.SSH_TLS,
                sni = profile.sni.trim(),
                payload = profile.payload
            )
            connectSsh(TunnelProxy(cfg), profile.host.trim(), profile.port, timeoutMs)
        }
        if (aborted) throw IOException("Dibatalkan")

        val gw = if (st.udpgw) st.udpgwPort else 0
        val server = Socks5Server(socksPort, { session }, gw, timeoutMs.coerceAtMost(20000))
        server.start()
        socks = server
        LogBus.add("Mesin SOCKS aktif: BadVPN" + if (gw > 0) " (UDPGW :$gw)" else " (UDP nonaktif)")
    }

    private fun startViaSlowDns(timeoutMs: Int) {
        val cands = SlowDnsClient.candidates(profile)
        var last: Throwable? = null
        for ((i, cand) in cands.withIndex()) {
            if (aborted) throw IOException("Dibatalkan")
            val port = Net.freePort()
            val c = SlowDnsClient(ctx, profile, cand, port)
            dns = c
            try {
                c.start()
                connectSsh(null, "127.0.0.1", port, if (cands.size > 1) 25000 else maxOf(timeoutMs, 45000))
                return
            } catch (e: Throwable) {
                c.stop()
                dns = null
                last = e
                if (aborted) throw IOException("Dibatalkan")
                if (i < cands.size - 1) LogBus.add("Tipe ${cand.label} gagal (${e.message}); mencoba tipe berikutnya")
            }
        }
        throw (last ?: IOException("SlowDNS gagal"))
    }

    private fun connectSsh(px: Proxy?, host: String, port: Int, timeoutMs: Int) {
        LogBus.add("Menginisialisasi SSH…")
        LogBus.add("Memulai core SSH-2")
        val jsch = JSch()
        val hasKey = profile.privateKey.isNotBlank()
        if (hasKey) {
            val pass = profile.password.takeIf { it.isNotEmpty() }?.toByteArray()
            jsch.addIdentity("lin-key", profile.privateKey.trim().toByteArray(), null, pass)
        }
        val s = jsch.getSession(profile.username.trim(), host, port)
        s.setPassword(profile.password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig(
            "PreferredAuthentications",
            if (hasKey) "publickey,password,keyboard-interactive" else "password,keyboard-interactive,publickey"
        )
        // Android (terutama versi lama) tidak punya EC/XDH di JCE -> prioritaskan algoritma DH + RSA + AES
        // yang pasti tersedia; algoritma bawaan library tetap ada di urutan belakang sebagai cadangan.
        androidSafe(s)
        // Algoritma lawas harus dinyalakan manual.
        if (st.legacySsh) enableLegacy(s)
        if (st.compression) {
            s.setConfig("compression.s2c", "zlib@openssh.com,zlib,none")
            s.setConfig("compression.c2s", "zlib@openssh.com,zlib,none")
        }
        s.serverAliveInterval = st.keepAliveSec.coerceIn(5, 300) * 1000
        s.serverAliveCountMax = 4
        s.setDaemonThread(true)
        if (px != null) {
            s.setProxy(px)
            proxy = px
        }
        s.userInfo = Ui(profile.password) { onBanner(it) }
        session = s
        try {
            s.connect(timeoutMs)
        } catch (e: JSchException) {
            session = null
            throw IOException(friendly(e), e)
        }
        if (aborted) {
            s.disconnect()
            throw IOException("Dibatalkan")
        }
        LogBus.add("SSH terhubung · ${s.serverVersion}")
    }

    private fun androidSafe(s: Session) {
        fun front(key: String, first: String) {
            val base = JSch.getConfig(key) ?: ""
            val head = first.split(',')
            val rest = base.split(',').filter { it.isNotBlank() && it !in head }
            s.setConfig(key, (head + rest).joinToString(","))
        }
        front("kex", "diffie-hellman-group-exchange-sha256,diffie-hellman-group16-sha512,diffie-hellman-group18-sha512,diffie-hellman-group14-sha256")
        front("server_host_key", "rsa-sha2-512,rsa-sha2-256,ssh-rsa")
        front("cipher.s2c", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-gcm@openssh.com,aes256-gcm@openssh.com")
        front("cipher.c2s", "aes128-ctr,aes192-ctr,aes256-ctr,aes128-gcm@openssh.com,aes256-gcm@openssh.com")
        front("mac.s2c", "hmac-sha2-256,hmac-sha2-512")
        front("mac.c2s", "hmac-sha2-256,hmac-sha2-512")
    }

    /** Opsional (Pengaturan > Algoritma SSH lawas) untuk server yang benar-benar tua. */
    private fun enableLegacy(s: Session) {
        fun add(key: String, extra: String) {
            val base = JSch.getConfig(key) ?: ""
            s.setConfig(key, if (base.isEmpty()) extra else "$base,$extra")
        }
        add("kex", "diffie-hellman-group14-sha1,diffie-hellman-group-exchange-sha1,diffie-hellman-group1-sha1")
        add("server_host_key", "ssh-rsa,ssh-dss")
        add("cipher.s2c", "aes128-cbc,aes192-cbc,aes256-cbc,3des-cbc")
        add("cipher.c2s", "aes128-cbc,aes192-cbc,aes256-cbc,3des-cbc")
        add("mac.s2c", "hmac-sha1,hmac-md5")
        add("mac.c2s", "hmac-sha1,hmac-md5")
    }

    private fun onBanner(text: String) {
        if (text.isBlank()) return
        LogBus.add(text.trim())
        VpnState.banner.value = text.trim()
        if (com.linvpn.app.data.Store.settings.value.showBanner) VpnState.bannerPopup.value = true
    }

    private fun friendly(e: JSchException): String {
        val m = e.message ?: e.javaClass.simpleName
        return when {
            m.contains("Auth fail", true) || m.contains("Auth cancel", true) ->
                "Login SSH gagal: username/password salah atau akun kedaluwarsa"
            m.contains("timeout", true) || m.contains("timed out", true) -> "Waktu koneksi habis ($m)"
            m.contains("Algorithm negotiation fail", true) ->
                "Server hanya menawarkan algoritma SSH lawas. Aktifkan \"Algoritma SSH lawas\" di Pengaturan bila server ini tepercaya"
            m.contains("Connection refused", true) -> "Koneksi ditolak server"
            m.contains("UnknownHost", true) -> "Host tidak ditemukan"
            else -> m
        }
    }

    override fun isAlive(): Boolean =
        session?.isConnected == true && socks?.running == true && (dns?.isAlive() ?: true)

    override fun stop() {
        aborted = true
        socks?.stop()
        socks = null
        try {
            proxy?.close()
        } catch (_: Exception) {
        }
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        session = null
        dns?.stop()
        dns = null
    }

    /** Menangkap banner (SSH_MSG_USERAUTH_BANNER) lewat showMessage dan menjawab login. */
    private class Ui(private val pass: String, private val onMsg: (String) -> Unit) : UserInfo, UIKeyboardInteractive {
        override fun getPassphrase(): String? = pass.ifEmpty { null }
        override fun getPassword(): String? = pass
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = true
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) {
            if (message != null) onMsg(message)
        }

        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?,
            prompt: Array<String>?, echo: BooleanArray?
        ): Array<String>? = prompt?.map { pass }?.toTypedArray()
    }
}
