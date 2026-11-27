package com.linvpn.app.core

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Impor tautan (vmess/vless/trojan/ss) dan penyiapan konfigurasi Xray untuk dijalankan. */
object V2rayConfig {

    private val proxyProtocols = setOf("vmess", "vless", "trojan", "shadowsocks", "socks", "http")

    fun template(): String = importLink(
        "vless://11111111-2222-3333-4444-555555555555@example.com:443" +
            "?encryption=none&security=tls&sni=example.com&type=ws&host=example.com&path=%2F#contoh"
    )!!.second

    /** Memformat ulang JSON; null bila bukan JSON valid. */
    fun pretty(json: String): String? = try {
        JSONObject(json).toString(2)
    } catch (e: Exception) {
        null
    }

    /** Teks bisa berupa tautan atau JSON lengkap. Hasil: (nama, konfigurasi JSON). */
    fun importText(text: String): Pair<String, String>? {
        val t = text.trim()
        if (t.startsWith("{")) {
            return try {
                JSONObject(t)
                "V2Ray" to JSONObject(t).toString(2)
            } catch (e: Exception) {
                null
            }
        }
        return importLink(t.lineSequence().first().trim())
    }

    fun importLink(link: String): Pair<String, String>? = try {
        when {
            link.startsWith("vmess://", true) -> vmess(link)
            link.startsWith("vless://", true) -> vless(link)
            link.startsWith("trojan://", true) -> trojan(link)
            link.startsWith("ss://", true) -> shadowsocks(link)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /** Menyiapkan konfigurasi akhir: inbound SOCKS lokal, dan opsional alihkan server ke SlowDNS lokal. */
    fun prepare(json: String, socksPort: Int, override: Pair<String, Int>?): String {
        val root = JSONObject(json)
        val inbound = JSONObject()
            .put("tag", "socks-in")
            .put("listen", "127.0.0.1")
            .put("port", socksPort)
            .put("protocol", "socks")
            .put("settings", JSONObject().put("auth", "noauth").put("udp", true).put("ip", "127.0.0.1"))
        root.put("inbounds", JSONArray().put(inbound))
        if (!root.has("log")) root.put("log", JSONObject().put("loglevel", "warning"))
        val outs = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("Konfigurasi tidak memiliki 'outbounds'")
        if (override != null) patchServer(outs, override)
        return root.toString()
    }

    private fun patchServer(outs: JSONArray, ov: Pair<String, Int>) {
        for (i in 0 until outs.length()) {
            val ob = outs.getJSONObject(i)
            if (ob.optString("protocol") !in proxyProtocols) continue
            val settings = ob.optJSONObject("settings") ?: continue
            val arr = settings.optJSONArray("vnext") ?: settings.optJSONArray("servers")
            val orig: String
            if (arr != null && arr.length() > 0) {
                val srv = arr.getJSONObject(0)
                orig = srv.optString("address")
                srv.put("address", ov.first)
                srv.put("port", ov.second)
            } else if (settings.has("address")) {
                orig = settings.optString("address")
                settings.put("address", ov.first)
                settings.put("port", ov.second)
            } else {
                continue
            }
            fillServerName(ob, orig)
            return
        }
        throw IllegalArgumentException("Tidak ada outbound proxy yang bisa dialihkan ke SlowDNS")
    }

    /** Alamat asli dipertahankan sebagai SNI bila belum diisi, supaya TLS tetap benar. */
    private fun fillServerName(ob: JSONObject, orig: String) {
        if (orig.isBlank() || orig == "127.0.0.1") return
        val ss = ob.optJSONObject("streamSettings") ?: return
        val key = when (ss.optString("security")) {
            "tls" -> "tlsSettings"
            "reality" -> "realitySettings"
            else -> return
        }
        val t = ss.optJSONObject(key) ?: JSONObject().also { ss.put(key, it) }
        if (t.optString("serverName").isBlank()) t.put("serverName", orig)
    }

    // ---- Editor formulir ---------------------------------------------------------------------

    data class Form(
        val proto: String = "vless", val address: String = "", val port: Int = 443, val id: String = "",
        val net: String = "ws", val sec: String = "tls", val sni: String = "", val path: String = "/",
        val host: String = "", val insecure: Boolean = false, val alpn: String = "", val fp: String = "",
        val alterId: Int = 0, val cipher: String = "auto"
    )

    private val formProtocols = setOf("vmess", "vless", "trojan")

    private fun firstProxyIndex(outs: JSONArray?): Int {
        if (outs == null) return -1
        for (i in 0 until outs.length()) if (outs.getJSONObject(i).optString("protocol") in formProtocols) return i
        return -1
    }

    /** Membaca outbound proxy pertama (vmess/vless/trojan) menjadi isian formulir; null bila tidak ada/tidak didukung. */
    fun toForm(json: String): Form? = try {
        val outs = JSONObject(json).optJSONArray("outbounds")
        val ob = outs!!.getJSONObject(firstProxyIndex(outs).also { if (it < 0) throw IllegalStateException() })
        val proto = ob.getString("protocol")
        val settings = ob.getJSONObject("settings")
        var f = Form(proto = proto)
        if (proto == "trojan") {
            val sv = settings.getJSONArray("servers").getJSONObject(0)
            f = f.copy(address = sv.optString("address"), port = sv.optInt("port", 443), id = sv.optString("password"))
        } else {
            val sv = settings.getJSONArray("vnext").getJSONObject(0)
            val u = sv.getJSONArray("users").getJSONObject(0)
            f = f.copy(
                address = sv.optString("address"), port = sv.optInt("port", 443), id = u.optString("id"),
                alterId = u.optInt("alterId", 0), cipher = u.optString("security", "auto").ifEmpty { "auto" }
            )
        }
        val ss = ob.optJSONObject("streamSettings") ?: JSONObject()
        val net = ss.optString("network", "tcp").ifEmpty { "tcp" }
        var path = ""
        var host = ""
        when (net) {
            "ws" -> ss.optJSONObject("wsSettings")?.let { path = it.optString("path"); host = it.optJSONObject("headers")?.optString("Host").orEmpty() }
            "grpc" -> path = ss.optJSONObject("grpcSettings")?.optString("serviceName").orEmpty()
            "httpupgrade" -> ss.optJSONObject("httpupgradeSettings")?.let { path = it.optString("path"); host = it.optString("host") }
            "xhttp" -> ss.optJSONObject("xhttpSettings")?.let { path = it.optString("path"); host = it.optString("host") }
            "http" -> ss.optJSONObject("httpSettings")?.let { path = it.optString("path"); host = it.optJSONArray("host")?.optString(0).orEmpty() }
        }
        val sec = ss.optString("security", "none").ifEmpty { "none" }
        val tls = ss.optJSONObject("tlsSettings")
        f.copy(
            net = net, path = path, host = host, sec = sec,
            sni = tls?.optString("serverName").orEmpty(),
            insecure = tls?.optBoolean("allowInsecure", false) ?: false,
            alpn = tls?.optJSONArray("alpn")?.let { a -> (0 until a.length()).joinToString(",") { a.getString(it) } }.orEmpty(),
            fp = tls?.optString("fingerprint").orEmpty()
        )
    } catch (e: Exception) {
        null
    }

    /** Menulis isian formulir ke outbound proxy pertama; bagian lain JSON (routing, dns, dll) dipertahankan. */
    fun applyForm(json: String, f: Form): String {
        val root = try { JSONObject(json) } catch (e: Exception) { JSONObject(wrap(JSONObject().put("protocol", "freedom"))) }
        val outs = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        val idx = firstProxyIndex(outs)
        val old = if (idx >= 0) outs.getJSONObject(idx) else null
        val oldUser = old?.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0)?.optJSONArray("users")?.optJSONObject(0)
        val ob = JSONObject().put("tag", old?.optString("tag")?.ifEmpty { "proxy" } ?: "proxy").put("protocol", f.proto)
        if (f.proto == "trojan") {
            val server = JSONObject().put("address", f.address).put("port", f.port).put("password", f.id)
            ob.put("settings", JSONObject().put("servers", JSONArray().put(server)))
        } else {
            val user = JSONObject().put("id", f.id)
            if (f.proto == "vmess") {
                user.put("alterId", f.alterId).put("security", f.cipher.ifEmpty { "auto" })
            } else {
                user.put("encryption", "none")
                oldUser?.optString("flow")?.takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }
            }
            ob.put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(JSONObject().put("address", f.address).put("port", f.port).put("users", JSONArray().put(user)))
                )
            )
        }
        if (f.sec == "reality" && old?.optJSONObject("streamSettings") != null) {
            // Reality tidak bisa diedit lewat formulir; pertahankan pengaturan aslinya
            ob.put("streamSettings", old.getJSONObject("streamSettings"))
        } else {
            val ss = stream(f.net, f.sec, f.host, f.path, f.sni, f.alpn, f.fp, "", emptyMap(), f.address)
            ss.optJSONObject("tlsSettings")?.put("allowInsecure", f.insecure)
            ob.put("streamSettings", ss)
        }
        if (idx >= 0) outs.put(idx, ob) else {
            val rebuilt = JSONArray().put(ob)
            for (i in 0 until outs.length()) rebuilt.put(outs.get(i))
            root.put("outbounds", rebuilt)
        }
        return root.toString(2)
    }

    // ---- Importer -------------------------------------------------------------------------

    private fun b64(s: String): String {
        val t = s.trim().replace('-', '+').replace('_', '/')
        return String(Base64.decode(t, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun wrap(proxy: JSONObject): String = JSONObject()
        .put("log", JSONObject().put("loglevel", "warning"))
        .put("inbounds", JSONArray())
        .put(
            "outbounds",
            JSONArray()
                .put(proxy)
                .put(JSONObject().put("protocol", "freedom").put("tag", "direct"))
                .put(JSONObject().put("protocol", "blackhole").put("tag", "block"))
        )
        .toString(2)

    private fun stream(
        net: String, sec: String, host: String, path: String, sni: String,
        alpn: String, fp: String, headerType: String, extra: Map<String, String>, address: String
    ): JSONObject {
        val n = when (net) {
            "", "tcp" -> "tcp"
            "h2", "http" -> "http"
            "splithttp", "xhttp" -> "xhttp"
            else -> net
        }
        val ss = JSONObject().put("network", n)
        val p = path.ifEmpty { "/" }
        when (n) {
            "ws" -> {
                val ws = JSONObject().put("path", p)
                if (host.isNotEmpty()) ws.put("headers", JSONObject().put("Host", host))
                ss.put("wsSettings", ws)
            }
            "grpc" -> ss.put("grpcSettings", JSONObject().put("serviceName", path))
            "httpupgrade" -> ss.put("httpupgradeSettings", JSONObject().put("path", p).put("host", host))
            "xhttp" -> ss.put("xhttpSettings", JSONObject().put("path", p).put("host", host))
            "http" -> ss.put(
                "httpSettings",
                JSONObject().put("path", p).put("host", JSONArray().also { if (host.isNotEmpty()) it.put(host) })
            )
            "tcp" -> if (headerType == "http") {
                val req = JSONObject()
                    .put("path", JSONArray().put(p))
                    .put("headers", JSONObject().put("Host", JSONArray().put(host)))
                ss.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", req)))
            }
        }
        when (sec) {
            "tls" -> {
                val tls = JSONObject()
                    .put("serverName", sni.ifEmpty { host.ifEmpty { address } })
                    .put("allowInsecure", false)
                if (alpn.isNotEmpty()) tls.put("alpn", JSONArray(alpn.split(',')))
                if (fp.isNotEmpty()) tls.put("fingerprint", fp)
                ss.put("security", "tls").put("tlsSettings", tls)
            }
            "reality" -> {
                val r = JSONObject()
                    .put("serverName", sni)
                    .put("fingerprint", fp.ifEmpty { "chrome" })
                    .put("publicKey", extra["pbk"] ?: "")
                    .put("shortId", extra["sid"] ?: "")
                    .put("spiderX", extra["spx"] ?: "")
                ss.put("security", "reality").put("realitySettings", r)
            }
        }
        return ss
    }

    private fun vmess(link: String): Pair<String, String> {
        val j = JSONObject(b64(link.substring(8)))
        val add = j.optString("add")
        val port = j.optString("port").toInt()
        val user = JSONObject()
            .put("id", j.optString("id"))
            .put("alterId", j.optString("aid", "0").toIntOrNull() ?: 0)
            .put("security", j.optString("scy").ifEmpty { "auto" })
        val ob = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vmess")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(JSONObject().put("address", add).put("port", port).put("users", JSONArray().put(user)))
                )
            )
            .put(
                "streamSettings",
                stream(
                    j.optString("net"), j.optString("tls"), j.optString("host"), j.optString("path"),
                    j.optString("sni"), j.optString("alpn"), j.optString("fp"), j.optString("type"),
                    emptyMap(), add
                )
            )
        return j.optString("ps", "vmess").ifEmpty { "vmess" } to wrap(ob)
    }

    private fun queryMap(u: Uri): Map<String, String> =
        u.queryParameterNames.associateWith { u.getQueryParameter(it) ?: "" }

    private fun vless(link: String): Pair<String, String> {
        val u = Uri.parse(link)
        val q = queryMap(u)
        val host = u.host ?: throw IllegalArgumentException("host kosong")
        val port = if (u.port > 0) u.port else 443
        val user = JSONObject()
            .put("id", u.userInfo ?: "")
            .put("encryption", q["encryption"] ?: "none")
        q["flow"]?.takeIf { it.isNotEmpty() }?.let { user.put("flow", it) }
        val ob = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "vless")
            .put(
                "settings",
                JSONObject().put(
                    "vnext",
                    JSONArray().put(JSONObject().put("address", host).put("port", port).put("users", JSONArray().put(user)))
                )
            )
            .put(
                "streamSettings",
                stream(
                    q["type"] ?: "tcp", q["security"] ?: "none", q["host"] ?: "", q["path"] ?: q["serviceName"] ?: "",
                    q["sni"] ?: "", q["alpn"] ?: "", q["fp"] ?: "", q["headerType"] ?: "", q, host
                )
            )
        return (u.fragment ?: "vless") to wrap(ob)
    }

    private fun trojan(link: String): Pair<String, String> {
        val u = Uri.parse(link)
        val q = queryMap(u)
        val host = u.host ?: throw IllegalArgumentException("host kosong")
        val port = if (u.port > 0) u.port else 443
        val server = JSONObject().put("address", host).put("port", port).put("password", u.userInfo ?: "")
        val ob = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "trojan")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
            .put(
                "streamSettings",
                stream(
                    q["type"] ?: "tcp", q["security"] ?: "tls", q["host"] ?: "", q["path"] ?: q["serviceName"] ?: "",
                    q["sni"] ?: "", q["alpn"] ?: "", q["fp"] ?: "", q["headerType"] ?: "", q, host
                )
            )
        return (u.fragment ?: "trojan") to wrap(ob)
    }

    private fun shadowsocks(link: String): Pair<String, String> {
        val name = link.substringAfter('#', "ss")
        val body = link.substring(5).substringBefore('#')
        val (userInfo, hostPort) = if (body.contains('@')) {
            body.substringBeforeLast('@') to body.substringAfterLast('@')
        } else {
            val d = b64(body)
            d.substringBeforeLast('@') to d.substringAfterLast('@')
        }
        val creds = if (userInfo.contains(':')) userInfo else b64(userInfo)
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').substringBefore('/').substringBefore('?').toInt()
        val server = JSONObject()
            .put("address", host).put("port", port)
            .put("method", creds.substringBefore(':')).put("password", creds.substringAfter(':'))
        val ob = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "shadowsocks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        return name to wrap(ob)
    }
}
