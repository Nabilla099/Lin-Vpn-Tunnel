package com.linvpn.app.core

import android.annotation.SuppressLint
import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Transport untuk JSch: TCP langsung / lewat proxy HTTP, opsional TLS (dengan SNI),
 * opsional payload (WebSocket upgrade, CONNECT, dsb) lalu SSH berjalan di atas soket itu.
 */
class TunnelProxy(private val c: Config) : Proxy {

    data class Config(
        val connectHost: String,
        val connectPort: Int,
        val targetHost: String,
        val targetPort: Int,
        val tls: Boolean,
        val sni: String,
        val payload: String
    )

    @Volatile
    private var raw: Socket? = null
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun connect(socketFactory: SocketFactory?, host: String?, port: Int, timeout: Int) {
        val t = if (timeout > 0) timeout else 30000
        val r = Socket()
        raw = r
        try {
            r.tcpNoDelay = true
            r.keepAlive = true
            LogBus.add("Menghubungkan ke ${c.connectHost}:${c.connectPort}")
            r.connect(InetSocketAddress(c.connectHost, c.connectPort), t)
            r.soTimeout = t

            val s: Socket = if (c.tls) wrapTls(r) else r
            val out = s.getOutputStream()
            val pin = PushbackInputStream(s.getInputStream(), 16)

            if (c.payload.isNotBlank()) {
                sendPayload(out)
                readResponses(pin)
            }
            socket = s
            input = pin
            output = out
        } catch (e: Throwable) {
            try {
                r.close()
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun wrapTls(r: Socket): Socket {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(TrustAll), SecureRandom())
        val sniHost = c.sni.ifBlank { c.targetHost }
        val ssl = ctx.socketFactory.createSocket(r, sniHost, c.connectPort, true) as SSLSocket
        val protos = ssl.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
        if (protos.isNotEmpty()) ssl.enabledProtocols = protos.toTypedArray()
        ssl.startHandshake()
        LogBus.add("TLS ${ssl.session.protocol} · SNI $sniHost")
        return ssl
    }

    private fun sendPayload(out: OutputStream) {
        val parts = Payload.build(c.payload, c.targetHost, c.targetPort)
        for ((text, delay) in parts) {
            if (delay > 0) Thread.sleep(delay)
            out.write(text.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        val firstLine = parts.firstOrNull()?.first?.lineSequence()?.firstOrNull()?.take(80) ?: ""
        LogBus.add("Payload terkirim: $firstLine")
    }

    /** Membaca 0..n respons HTTP (101/200 dsb) yang mendahului banner SSH. */
    private fun readResponses(pin: PushbackInputStream) {
        for (round in 0 until 4) {
            val peek = ByteArray(5)
            var n = 0
            while (n < 5) {
                val r = pin.read(peek, n, 5 - n)
                if (r < 0) {
                    if (n == 0) throw IOException("Server menutup koneksi setelah payload dikirim")
                    break
                }
                n += r
            }
            if (n == 5 && String(peek, Charsets.ISO_8859_1) == "HTTP/") {
                val head = readHead(pin)
                val first = head.lineSequence().first().trim()
                LogBus.add("Respons: $first")
                val code = first.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                if (code !in 100..299) throw IOException("Server menolak payload: $first")
            } else {
                pin.unread(peek, 0, n)
                return
            }
        }
    }

    private fun readHead(pin: InputStream): String {
        val sb = StringBuilder("HTTP/")
        while (sb.length < 16384) {
            val b = pin.read()
            if (b < 0) break
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n") || sb.endsWith("\n\n")) break
        }
        return sb.toString()
    }

    override fun getInputStream(): InputStream = input!!

    override fun getOutputStream(): OutputStream = output!!

    override fun getSocket(): Socket = socket!!

    override fun close() {
        try {
            input?.close()
        } catch (_: Exception) {
        }
        try {
            output?.close()
        } catch (_: Exception) {
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        try {
            raw?.close()
        } catch (_: Exception) {
        }
    }

    /** Server tunnel (stunnel/CDN) umumnya bersertifikat sendiri; klien sejenis tidak memverifikasi. */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
