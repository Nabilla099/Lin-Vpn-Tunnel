package com.linvpn.app.core

import kotlinx.coroutines.flow.MutableStateFlow

enum class Status { IDLE, CONNECTING, CONNECTED, RECONNECTING, DISCONNECTING }

data class Speed(
    val down: Long = 0,
    val up: Long = 0,
    val totalDown: Long = 0,
    val totalUp: Long = 0,
    val downHist: List<Float> = emptyList(),
    val upHist: List<Float> = emptyList()
)

/** Status bersama antara service dan UI (satu proses). */
object VpnState {
    /** true saat aplikasi tampil di layar; bila false, riwayat grafik tidak dihitung (hemat CPU/RAM). */
    @Volatile var uiVisible = false

    val status = MutableStateFlow(Status.IDLE)
    val speed = MutableStateFlow(Speed())
    val connectedAt = MutableStateFlow(0L)
    val banner = MutableStateFlow("")
    val bannerPopup = MutableStateFlow(false)
    val profileName = MutableStateFlow("")
    val socksPort = MutableStateFlow(0)

    fun reset() {
        status.value = Status.IDLE
        speed.value = Speed()
        connectedAt.value = 0L
        socksPort.value = 0
    }
}
