package com.linvpn.app.core

/**
 * Pengolah payload. Placeholder yang didukung:
 * [crlf] [cr] [lf] [host] [port] [host_port] [ssh] [ip] [protocol] [ua]
 * [rotate=a;b;c]  -> pilih acak
 * [split] [delay_split] [instant_split] -> kirim bagian berikutnya sebagai paket terpisah
 * Baris baru yang diketik langsung otomatis dianggap [crlf].
 */
object Payload {
    private const val UA = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
    private val splitRe = Regex("\\[(split|delay_split|instant_split)]", RegexOption.IGNORE_CASE)
    private val rotateRe = Regex("\\[rotate=([^\\]]*)]", RegexOption.IGNORE_CASE)

    /** Hasil: daftar (isi, jeda sebelum kirim dalam ms). */
    fun build(raw: String, host: String, port: Int): List<Pair<String, Long>> {
        var s = raw.replace("\r\n", "\n").replace("\n", "[crlf]")
        s = rotateRe.replace(s) { m ->
            val opts = m.groupValues[1].split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }
            if (opts.isEmpty()) "" else opts.random()
        }
        // Urutan penting: token yang lebih panjang lebih dulu.
        val repl = linkedMapOf(
            "[crlf]" to "\r\n",
            "[cr]" to "\r",
            "[lf]" to "\n",
            "[host_port]" to "$host:$port",
            "[ssh]" to "$host:$port",
            "[host]" to host,
            "[ip]" to host,
            "[port]" to port.toString(),
            "[protocol]" to "HTTP/1.1",
            "[ua]" to UA
        )
        for ((k, v) in repl) s = s.replace(k, v, ignoreCase = true)

        val out = ArrayList<Pair<String, Long>>()
        var last = 0
        var delay = 0L
        for (m in splitRe.findAll(s)) {
            out.add(s.substring(last, m.range.first) to delay)
            last = m.range.last + 1
            delay = when (m.groupValues[1].lowercase()) {
                "delay_split" -> 1000L
                "split" -> 150L
                else -> 0L
            }
        }
        out.add(s.substring(last) to delay)
        return out.filter { it.first.isNotEmpty() }
    }
}
