package com.linvpn.app.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** Satu "mesin" tunnel yang menyediakan proxy SOCKS5 di 127.0.0.1:port. */
interface Engine {
    /** Blocking sampai siap atau melempar exception. */
    fun start()

    fun isAlive(): Boolean

    /** Aman dipanggil dari thread mana pun, berkali-kali. */
    fun stop()
}

object Net {
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    fun freePort(): Int = ServerSocket(0, 1, loopback).use { it.localPort }

    fun isPortFree(port: Int): Boolean = try {
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(InetSocketAddress(loopback, port))
        }
        true
    } catch (e: IOException) {
        false
    }

    /** Menunggu sampai port lokal menerima koneksi. false bila timeout atau proses mati. */
    fun waitPort(port: Int, timeoutMs: Int, alive: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (!alive()) return false
            try {
                Socket().use { it.connect(InetSocketAddress(loopback, port), 300) }
                return true
            } catch (e: IOException) {
                // belum siap
            }
            try {
                Thread.sleep(250)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return false
    }
}
