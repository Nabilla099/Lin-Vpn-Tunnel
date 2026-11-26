package com.linvpn.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.linvpn.app.data.Store

/** Menyambung otomatis setelah perangkat menyala, bila diaktifkan di pengaturan dan izin VPN sudah ada. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val st = Store.settings.value
        if (!st.startOnBoot || st.lastProfileId.isBlank()) return
        if (VpnService.prepare(context) != null) return // izin VPN belum diberikan
        LinVpnService.connect(context, st.lastProfileId)
    }
}
