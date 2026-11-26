package com.linvpn.app.core

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Server SOCKS5 lokal. CONNECT dibuka sebagai channel direct-tcpip di sesi SSH.
 * UDP ASSOCIATE diteruskan lewat BadVPN udpgw di sisi server (127.0.0.1:udpgwPort).
 */
class Socks5Server(
    private val port: Int,
    private val sessionProvider: () -> Session?,
    private val udpgwPort: Int,
    private val openTimeoutMs: Int
) {
    @Volatile
    var running = false
        private set

    private var server: ServerSocket? = null
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(null, r, "socks", 256 * 1024).apply { isDaemon = true }
    }
    private val gw: UdpGw? = if (udpgwPort > 0) UdpGw() else null

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(loopback, port), 512)
        server = ss
        running = true
        pool.execute {
            while (running) {
                try {
                    val c = ss.accept()
                    pool.execute { handle(c) }
                } catch (e: IOException) {
                    if (!running || ss.isClosed) break
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        gw?.shutdown()
        pool.shutdownNow()
    }

    // ---- Handshake -----------------------------------------------------------------------

    private fun handle(c: Socket) {
        try {
            c.tcpNoDelay = true
            c.soTimeout = 15000
            val inp = c.getInputStream()
            val out = c.getOutputStream()
            if (inp.read() != 5) {
                c.close()
                return
            }
            val nMethods = inp.read()
            if (nMethods < 0) {
                c.close()
                return
            }
            readFully(inp, ByteArray(nMethods))
            out.write(byteArrayOf(5, 0))
            out.flush()

            val h = ByteArray(4)
            readFully(inp, h)
            val cmd = h[1].toInt()
            val host: String = when (h[3].toInt()) {
                1 -> InetAddress.getByAddress(ByteArray(4).also { readFully(inp, it) }).hostAddress ?: ""
                3 -> {
                    val len = inp.read()
                    if (len <= 0) throw IOException("domain kosong")
                    String(ByteArray(len).also { readFully(inp, it) }, Charsets.ISO_8859_1)
                }
                4 -> InetAddress.getByAddress(ByteArray(16).also { readFully(inp, it) }).hostAddress ?: ""
                else -> {
                    reply(out, 8)
                    c.close()
                    return
                }
            }
            val pb = ByteArray(2)
            readFully(inp, pb)
            val dport = ((pb[0].toInt() and 0xff) shl 8) or (pb[1].toInt() and 0xff)

            when (cmd) {
                1 -> connectCmd(c, out, host, dport)
                3 -> udpAssociate(c, inp, out)
                else -> {
                    reply(out, 7)
                    c.close()
                }
            }
        } catch (e: Exception) {
            try {
                c.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun connectCmd(c: Socket, out: OutputStream, host: String, dport: Int) {
        val sess = sessionProvider()
        if (sess == null || !sess.isConnected) {
            reply(out, 1)
            c.close()
            return
        }
        val gate = GateOut(out)
        try {
            val ch = sess.openChannel("direct-tcpip") as ChannelDirectTCPIP
            ch.setHost(host)
            ch.setPort(dport)
            ch.setInputStream(c.getInputStream())
            ch.setOutputStream(gate)
            c.soTimeout = 0
            ch.connect(openTimeoutMs)
        } catch (e: Exception) {
            try {
                reply(out, 5)
            } catch (_: Exception) {
            }
            c.close()
            return
        }
        reply(out, 0)
        gate.open()
    }

    private fun reply(out: OutputStream, code: Int) {
        out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
        out.flush()
    }

    /** Menahan data dari server sampai balasan SOCKS terkirim agar urutannya benar. */
    private class GateOut(private val target: OutputStream) : OutputStream() {
        private var open = false
        private val buf = ByteArrayOutputStream()

        @Synchronized
        override fun write(b: Int) {
            if (open) target.write(b) else buf.write(b)
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (open) target.write(b, off, len) else buf.write(b, off, len)
        }

        @Synchronized
        override fun flush() {
            if (open) target.flush()
        }

        @Synchronized
        override fun close() {
            target.close()
        }

        @Synchronized
        fun open() {
            open = true
            if (buf.size() > 0) {
                target.write(buf.toByteArray())
                buf.reset()
            }
            target.flush()
        }
    }

    // ---- UDP via udpgw --------------------------------------------------------------------

    private fun udpAssociate(c: Socket, inp: InputStream, out: OutputStream) {
        if (gw == null) {
            reply(out, 7)
            c.close()
            return
        }
        val relay = DatagramSocket(0, loopback)
        val p = relay.localPort
        out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, (p shr 8).toByte(), (p and 0xff).toByte()))
        out.flush()
        c.soTimeout = 0
        val t = Thread({
            val buf = ByteArray(4096)
            val pkt = DatagramPacket(buf, buf.size)
            try {
                while (true) {
                    pkt.length = buf.size
                    relay.receive(pkt)
                    gw.forward(relay, pkt)
                }
            } catch (_: Exception) {
            }
        }, "udp-relay")
        t.isDaemon = true
        t.start()
        try {
            while (inp.read() >= 0) {
                // koneksi kontrol dibiarkan terbuka selama asosiasi aktif
            }
        } catch (_: Exception) {
        } finally {
            relay.close()
            gw.dropRelay(relay)
            try {
                c.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Klien BadVPN udpgw. Bingkai: [len:u16 LE][flags:u8][conid:u16 LE][ip:4|16 (urutan jaringan)][port:u16 BE][data].
     * flags: 1 keepalive, 2 rebind, 4 dns, 8 ipv6.
     * Catatan: urutan byte ini mengikuti implementasi referensi badvpn dan belum diuji terhadap server nyata.
     */
    private inner class UdpGw {
        private inner class Flow(
            val key: String, val relay: DatagramSocket,
            val clientAddr: InetAddress, val clientPort: Int,
            val ip: ByteArray, val port: Int, val v6: Boolean
        )

        private val lock = Any()
        private val flows = ConcurrentHashMap<Int, Flow>()
        private val keys = ConcurrentHashMap<String, Int>()
        private var nextId = 1
        private var channel: ChannelDirectTCPIP? = null
        private var chOut: OutputStream? = null
        private var disabledUntil = 0L

        fun forward(relay: DatagramSocket, pkt: DatagramPacket) {
            if (System.currentTimeMillis() < disabledUntil) return
            val d = pkt.data
            val off = pkt.offset
            val len = pkt.length
            if (len < 10 || d[off + 2].toInt() != 0) return // fragmentasi tidak didukung
            var p = off + 3
            val ip: ByteArray
            val v6: Boolean
            when (d[p++].toInt()) {
                1 -> {
                    ip = d.copyOfRange(p, p + 4); p += 4; v6 = false
                }
                4 -> {
                    ip = d.copyOfRange(p, p + 16); p += 16; v6 = true
                }
                else -> return // domain tidak didukung untuk UDP
            }
            val dport = ((d[p].toInt() and 0xff) shl 8) or (d[p + 1].toInt() and 0xff)
            p += 2
            val payload = d.copyOfRange(p, off + len)
            val key = "${System.identityHashCode(relay)}|${pkt.port}|${ip.joinToString(".") { (it.toInt() and 0xff).toString() }}|$dport"
            val id = synchronized(lock) {
                keys[key] ?: run {
                    val nid = nextId
                    nextId = if (nextId >= 65535) 1 else nextId + 1
                    flows.put(nid, Flow(key, relay, pkt.address, pkt.port, ip, dport, v6))?.let { keys.remove(it.key) }
                    keys[key] = nid
                    nid
                }
            }
            send(id, v6, ip, dport, payload)
        }

        private fun send(id: Int, v6: Boolean, ip: ByteArray, dport: Int, payload: ByteArray) {
            val body = 3 + ip.size + 2 + payload.size
            if (body > 65535) return
            val buf = ByteArray(2 + body)
            buf[0] = (body and 0xff).toByte()
            buf[1] = (body shr 8).toByte()
            buf[2] = if (v6) 8 else 0
            buf[3] = (id and 0xff).toByte()
            buf[4] = (id shr 8).toByte()
            System.arraycopy(ip, 0, buf, 5, ip.size)
            buf[5 + ip.size] = (dport shr 8).toByte()
            buf[6 + ip.size] = (dport and 0xff).toByte()
            System.arraycopy(payload, 0, buf, 7 + ip.size, payload.size)
            try {
                synchronized(lock) {
                    ensureOpen()
                    chOut!!.write(buf)
                    chOut!!.flush()
                }
            } catch (e: Exception) {
                synchronized(lock) { channel = null }
            }
        }

        private fun ensureOpen() {
            val cur = channel
            if (cur != null && cur.isConnected && !cur.isClosed) return
            val s = sessionProvider() ?: throw IOException("sesi SSH tidak ada")
            try {
                val ch = s.openChannel("direct-tcpip") as ChannelDirectTCPIP
                ch.setHost("127.0.0.1")
                ch.setPort(udpgwPort)
                val rin = ch.inputStream
                val rout = ch.outputStream
                ch.connect(openTimeoutMs)
                channel = ch
                chOut = rout
                val t = Thread({ readLoop(rin, ch) }, "udpgw")
                t.isDaemon = true
                t.start()
                LogBus.add("UDPGW aktif (127.0.0.1:$udpgwPort)")
            } catch (e: Exception) {
                disabledUntil = System.currentTimeMillis() + 60000
                LogBus.add("UDPGW tidak tersedia di server; UDP dinonaktifkan sementara")
                throw e
            }
        }

        private fun readLoop(inp: InputStream, ch: ChannelDirectTCPIP) {
            try {
                val hdr = ByteArray(2)
                while (true) {
                    readFully(inp, hdr)
                    val len = (hdr[0].toInt() and 0xff) or ((hdr[1].toInt() and 0xff) shl 8)
                    val body = ByteArray(len)
                    readFully(inp, body)
                    onReply(body)
                }
            } catch (_: Exception) {
            } finally {
                synchronized(lock) {
                    if (channel === ch) channel = null
                }
                try {
                    ch.disconnect()
                } catch (_: Exception) {
                }
            }
        }

        private fun onReply(b: ByteArray) {
            if (b.size < 3) return
            val flags = b[0].toInt() and 0xff
            if ((flags and 1) != 0) return
            val id = (b[1].toInt() and 0xff) or ((b[2].toInt() and 0xff) shl 8)
            val flow = flows[id] ?: return
            val v6 = (flags and 8) != 0
            val ipLen = if (v6) 16 else 4
            if (b.size < 3 + ipLen + 2) return
            val data = b.copyOfRange(3 + ipLen + 2, b.size)
            val ip = b.copyOfRange(3, 3 + ipLen)
            val out = ByteArray(4 + ipLen + 2 + data.size)
            out[3] = if (v6) 4 else 1
            System.arraycopy(ip, 0, out, 4, ipLen)
            out[4 + ipLen] = b[3 + ipLen]
            out[5 + ipLen] = b[4 + ipLen]
            System.arraycopy(data, 0, out, 6 + ipLen, data.size)
            try {
                flow.relay.send(DatagramPacket(out, out.size, flow.clientAddr, flow.clientPort))
            } catch (_: Exception) {
            }
        }

        fun dropRelay(relay: DatagramSocket) {
            synchronized(lock) {
                val it = flows.entries.iterator()
                while (it.hasNext()) {
                    val e = it.next()
                    if (e.value.relay === relay) {
                        keys.remove(e.value.key)
                        it.remove()
                    }
                }
            }
        }

        fun shutdown() {
            synchronized(lock) {
                try {
                    channel?.disconnect()
                } catch (_: Exception) {
                }
                channel = null
                flows.clear()
                keys.clear()
            }
        }
    }

    companion object {
        fun readFully(inp: InputStream, buf: ByteArray) {
            var n = 0
            while (n < buf.size) {
                val r = inp.read(buf, n, buf.size - n)
                if (r < 0) throw EOFException()
                n += r
            }
        }
    }
}

/** Alat bantu: IP lokal perangkat dan IP publik yang terlihat lewat tunnel. */
object NetInfo {
    fun localIps(): List<Pair<String, String>> {
        val res = ArrayList<Pair<String, String>>()
        try {
            val all = java.net.NetworkInterface.getNetworkInterfaces() ?: return res
            for (ni in java.util.Collections.list(all)) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in java.util.Collections.list(ni.inetAddresses)) {
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress) {
                        res.add(label(ni.name) to (a.hostAddress ?: ""))
                    }
                }
            }
        } catch (_: Exception) {
        }
        return res
    }

    private fun label(n: String): String = when {
        n.startsWith("wlan") -> "Wi-Fi"
        n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") -> "Seluler"
        n.startsWith("tun") -> "VPN"
        n.startsWith("ap") || n.startsWith("rndis") || n.startsWith("swlan") -> "Hotspot/USB"
        else -> n
    }

    /** Meminta IP publik lewat proxy SOCKS lokal (artinya IP keluar tunnel). */
    fun publicIp(socksPort: Int): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", socksPort), 3000)
            s.soTimeout = 15000
            val o = s.getOutputStream()
            val i = s.getInputStream()
            o.write(byteArrayOf(5, 1, 0)); o.flush()
            val r = ByteArray(2); Socks5Server.readFully(i, r)
            val host = "api.ipify.org".toByteArray()
            val req = ByteArrayOutputStream()
            req.write(byteArrayOf(5, 1, 0, 3, host.size.toByte())); req.write(host); req.write(byteArrayOf(0, 80))
            o.write(req.toByteArray()); o.flush()
            val h = ByteArray(10); Socks5Server.readFully(i, h)
            if (h[1].toInt() != 0) null else {
                o.write("GET / HTTP/1.1\r\nHost: api.ipify.org\r\nConnection: close\r\n\r\n".toByteArray()); o.flush()
                val text = String(i.readBytes(), Charsets.ISO_8859_1)
                text.substringAfter("\r\n\r\n", "").trim().lineSequence().lastOrNull()?.takeIf { it.length in 7..45 }
            }
        }
    } catch (e: Exception) {
        null
    }
}
