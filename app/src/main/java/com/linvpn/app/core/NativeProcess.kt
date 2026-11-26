package com.linvpn.app.core

import android.os.Build
import java.io.File
import java.io.FileInputStream

/** Menjalankan binary native (dnstt, xray, ...) sebagai proses anak dan meneruskan lognya. */
class NativeProcess(
    private val tag: String,
    private val cmd: List<String>,
    private val env: Map<String, String> = emptyMap(),
    private val keep: (String) -> Boolean = { true }
) {
    private var proc: Process? = null

    fun start() {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = pb.start()
        proc = p
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { if (keep(it)) LogBus.add("[$tag] $it") }
            } catch (_: Exception) {
            }
        }, "$tag-log").apply { isDaemon = true }.start()
    }

    fun isAlive(): Boolean {
        val p = proc ?: return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    }

    fun stop() {
        val p = proc ?: return
        try {
            p.destroy()
            if (Build.VERSION.SDK_INT >= 26) {
                if (!p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            }
        } catch (_: Exception) {
        }
        proc = null
    }

    companion object {
        private val names = listOf("libdnstt.so", "libvaydns.so", "libxray.so")

        /** Membunuh proses sisa (mis. setelah aplikasi di-kill) yang masih memegang port. */
        fun killStale() {
            val me = android.os.Process.myPid()
            File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                if (pid == me) return@forEach
                try {
                    val cmdline = FileInputStream(File(d, "cmdline")).use { it.readBytes() }.toString(Charsets.ISO_8859_1)
                    if (names.any { cmdline.contains(it) }) android.os.Process.killProcess(pid)
                } catch (_: Exception) {
                }
            }
        }
    }
}
