package com.linvpn.app.core

/** Menghitung rute IPv4 "semua kecuali jaringan lokal" (VpnService.Builder < Android 13 tidak punya excludeRoute). */
object Routes {
    private class Cidr(val ip: Long, val len: Int) {
        val last: Long get() = ip + (1L shl (32 - len)) - 1
    }

    private fun parse(s: String): Cidr {
        val (a, l) = s.split('/')
        val o = a.split('.').map { it.toLong() }
        return Cidr((o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8) or o[3], l.toInt())
    }

    private fun fmt(ip: Long): String =
        "${(ip shr 24) and 255}.${(ip shr 16) and 255}.${(ip shr 8) and 255}.${ip and 255}"

    private val lan = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4"
    )

    fun allExceptLan(): List<Pair<String, Int>> {
        val ex = lan.map { parse(it) }
        val out = ArrayList<Cidr>()
        split(0L, 0, ex, out)
        return out.map { fmt(it.ip) to it.len }
    }

    private fun split(ip: Long, len: Int, ex: List<Cidr>, out: MutableList<Cidr>) {
        val block = Cidr(ip, len)
        for (e in ex) if (block.ip >= e.ip && block.last <= e.last) return // seluruhnya dikecualikan
        if (ex.none { block.ip <= it.last && it.ip <= block.last }) { // tidak beririsan
            out.add(block)
            return
        }
        val half = 1L shl (32 - len - 1)
        split(ip, len + 1, ex, out)
        split(ip + half, len + 1, ex, out)
    }
}
