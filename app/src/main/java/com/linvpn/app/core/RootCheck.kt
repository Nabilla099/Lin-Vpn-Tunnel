package com.linvpn.app.core

import android.os.Build
import java.io.File

/** Deteksi sederhana perangkat root (bukan jaminan mutlak). */
object RootCheck {
    private val paths = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/su/bin/su",
        "/data/local/xbin/su", "/data/local/bin/su", "/system/sd/xbin/su",
        "/system/app/Superuser.apk", "/system/app/SuperSU.apk", "/data/adb/magisk", "/sbin/.magisk"
    )

    fun isRooted(): Boolean {
        if (Build.TAGS?.contains("test-keys") == true) return true
        if (paths.any { File(it).exists() }) return true
        return try {
            System.getenv("PATH").orEmpty().split(':').any { it.isNotEmpty() && File(it, "su").exists() }
        } catch (_: Throwable) {
            false
        }
    }
}
