package com.linvpn.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.linvpn.app.data.Store

class LinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_VPN, "Status VPN", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.description = "Status koneksi dan kecepatan"
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    companion object {
        const val CHANNEL_VPN = "vpn_status"
    }
}
