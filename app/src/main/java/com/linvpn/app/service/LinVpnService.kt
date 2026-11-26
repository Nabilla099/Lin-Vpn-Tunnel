package com.linvpn.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.linvpn.app.LinApp
import com.linvpn.app.MainActivity
import com.linvpn.app.R
import com.linvpn.app.core.Engine
import com.linvpn.app.core.LogBus
import com.linvpn.app.core.NativeProcess
import com.linvpn.app.core.Net
import com.linvpn.app.core.Routes
import com.linvpn.app.core.Speed
import com.linvpn.app.core.SshEngine
import com.linvpn.app.core.Status
import com.linvpn.app.core.V2rayEngine
import com.linvpn.app.core.VpnState
import com.linvpn.app.data.AppSettings
import com.linvpn.app.data.Profile
import com.linvpn.app.data.ProfileType
import com.linvpn.app.data.Store
import hev.htproxy.TProxyService
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class LinVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.linvpn.app.CONNECT"
        const val ACTION_DISCONNECT = "com.linvpn.app.DISCONNECT"
        const val ACTION_RECONNECT = "com.linvpn.app.RECONNECT"
        const val EXTRA_PROFILE = "profile_id"
        private const val NOTIF_ID = 1
        private const val TUN_ADDR = "198.18.0.1"
        private const val DNS_ADDR = "198.18.0.2"

        fun connect(ctx: Context, profileId: String) {
            ContextCompat.startForegroundService(
                ctx,
                Intent(ctx, LinVpnService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_PROFILE, profileId)
            )
        }

        fun disconnect(ctx: Context) {
            ctx.startService(Intent(ctx, LinVpnService::class.java).setAction(ACTION_DISCONNECT))
        }

        fun reconnect(ctx: Context) {
            ctx.startService(Intent(ctx, LinVpnService::class.java).setAction(ACTION_RECONNECT))
        }
    }

    @Volatile private var stopRequested = false
    @Volatile private var reconnectRequested = false
    @Volatile private var engine: Engine? = null
    private var worker: Thread? = null
    private var tun: ParcelFileDescriptor? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val signal = LinkedBlockingQueue<Unit>()
    private var lastNotifText = ""

    // ---- Siklus hidup ----------------------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> stopVpn()
            ACTION_RECONNECT -> {
                if (worker?.isAlive == true) {
                    reconnectRequested = true
                    signal.offer(Unit)
                } else {
                    finishService()
                }
            }
            else -> {
                // ACTION_CONNECT, atau dimulai sistem (Always-on VPN) tanpa extra
                val id = intent?.getStringExtra(EXTRA_PROFILE)?.takeIf { it.isNotBlank() }
                    ?: Store.settings.value.lastProfileId
                startVpn(id)
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        LogBus.add("Izin VPN dicabut oleh sistem")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopRequested = true
        engine?.let { e -> Thread { e.stop() }.start() }
        worker?.interrupt()
        super.onDestroy()
    }

    private fun startVpn(profileId: String) {
        showForeground(buildNotification("Menghubungkan…", Status.CONNECTING))
        if (worker?.isAlive == true) return
        val profile = Store.profiles.value.firstOrNull { it.id == profileId }
        if (profile == null) {
            LogBus.add("Profil belum dipilih / tidak ditemukan")
            finishService()
            return
        }
        stopRequested = false
        reconnectRequested = false
        signal.clear()
        worker = Thread({ supervise(profile) }, "lin-supervisor").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun stopVpn() {
        if (worker?.isAlive != true) {
            VpnState.reset()
            finishService()
            return
        }
        VpnState.status.value = Status.DISCONNECTING
        stopRequested = true
        signal.offer(Unit)
        // Menutup engine membuka blokir connect yang sedang menunggu
        engine?.let { e -> Thread { e.stop() }.start() }
        worker?.interrupt()
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- Supervisor: koneksi, pantau, sambung ulang ---------------------------------------

    private fun supervise(profile: Profile) {
        val st = Store.settings.value
        VpnState.reset()
        VpnState.profileName.value = profile.name
        VpnState.banner.value = ""
        VpnState.bannerPopup.value = false
        if (st.clearLogOnConnect) LogBus.clear()
        logHeader()
        NativeProcess.killStale()
        acquireWakeLock(st)
        registerNetworkWatcher(st)

        val port = if (Net.isPortFree(st.socksPort)) st.socksPort else Net.freePort()
        VpnState.socksPort.value = port
        var tunStarted = false
        var failures = 0
        var prevTx = 0L
        var prevRx = 0L
        try {
            while (!stopRequested) {
                VpnState.status.value = if (tunStarted) Status.RECONNECTING else Status.CONNECTING
                VpnState.speed.value = VpnState.speed.value.copy(down = 0, up = 0)
                updateNotification()
                var e: Engine? = null
                try {
                    e = createEngine(profile, st, port)
                    engine = e
                    e.start()
                    if (stopRequested) break
                    if (!tunStarted) {
                        startTun(st, port)
                        tunStarted = true
                        VpnState.connectedAt.value = System.currentTimeMillis()
                        prevTx = 0L
                        prevRx = 0L
                    }
                    failures = 0
                    VpnState.status.value = Status.CONNECTED
                    LogBus.add("Terhubung")
                    updateNotification()

                    var lastTick = System.nanoTime()
                    while (!stopRequested && !reconnectRequested && e.isAlive()) {
                        signal.poll(1000, TimeUnit.MILLISECONDS)
                        val now = System.nanoTime()
                        val dt = ((now - lastTick) / 1_000_000L).coerceAtLeast(1)
                        lastTick = now
                        val s = try {
                            TProxyService.TProxyGetStats()
                        } catch (_: Throwable) {
                            null
                        }
                        if (s != null && s.size >= 4) {
                            val up = ((s[1] - prevTx).coerceAtLeast(0) * 1000) / dt
                            val down = ((s[3] - prevRx).coerceAtLeast(0) * 1000) / dt
                            prevTx = s[1]
                            prevRx = s[3]
                            publishSpeed(down, up, s[3], s[1])
                            updateNotification()
                        }
                    }
                    if (stopRequested) break
                    LogBus.add(if (reconnectRequested) "Menghubungkan ulang…" else "Koneksi terputus")
                } catch (ex: InterruptedException) {
                    break
                } catch (ex: UnsatisfiedLinkError) {
                    LogBus.add("Library native tidak ditemukan: ${ex.message}")
                    break
                } catch (ex: Throwable) {
                    if (stopRequested) break
                    LogBus.add("Gagal: ${ex.message ?: ex.javaClass.simpleName}")
                } finally {
                    try {
                        e?.stop()
                    } catch (_: Throwable) {
                    }
                    engine = null
                }
                if (stopRequested) break
                val forced = reconnectRequested
                reconnectRequested = false
                if (!forced) {
                    failures++
                    if (!st.autoReconnect) break
                    if (st.maxRetries > 0 && failures >= st.maxRetries) {
                        LogBus.add("Batas percobaan tercapai ($failures)")
                        break
                    }
                    // Jeda bertahap: delay, 2x, 3x ... maksimal 30 detik
                    val wait = (st.retryDelaySec.coerceAtLeast(1) * failures).coerceAtMost(30)
                    LogBus.add("Mencoba lagi dalam ${wait}s (percobaan $failures)")
                    try {
                        signal.poll(wait.toLong(), TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        } finally {
            teardown()
        }
    }

    private fun createEngine(profile: Profile, st: AppSettings, port: Int): Engine = when (profile.type) {
        ProfileType.SSH_PAYLOAD, ProfileType.SSH_TLS, ProfileType.SSH_SLOWDNS -> SshEngine(this, profile, st, port)
        ProfileType.V2RAY, ProfileType.V2RAY_SLOWDNS -> V2rayEngine(this, profile, port)
    }

    private fun teardown() {
        try {
            TProxyService.TProxyStopService()
        } catch (_: Throwable) {
        }
        try {
            tun?.close()
        } catch (_: Exception) {
        }
        tun = null
        unregisterNetworkWatcher()
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null
        LogBus.add("Terputus")
        VpnState.reset()
        finishService()
    }

    // ---- VPN interface + tun2socks ---------------------------------------------------------

    private fun startTun(st: AppSettings, socksPort: Int) {
        val mapDns = st.dnsMode == 0
        val b = Builder().setSession("Lin Vpn").setMtu(st.mtu.coerceIn(576, 9000))
        b.addAddress(TUN_ADDR, 32)
        if (st.bypassLan) {
            for ((ip, len) in Routes.allExceptLan()) b.addRoute(ip, len)
        } else {
            b.addRoute("0.0.0.0", 0)
        }
        if (st.ipv6) {
            b.addAddress("fc00::1", 128)
            b.addRoute("::", 0)
        }
        if (mapDns) {
            b.addDnsServer(DNS_ADDR)
        } else {
            for (d in listOf(st.dns1, st.dns2)) if (d.isNotBlank()) {
                try {
                    b.addDnsServer(d.trim())
                } catch (_: Exception) {
                }
            }
        }
        applyAppRules(b, st)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        b.setConfigureIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        val pfd = b.establish() ?: throw IOException("VPN tidak diizinkan oleh sistem")
        tun = pfd

        val yaml = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${st.mtu.coerceIn(576, 9000)}")
            appendLine("  ipv4: $TUN_ADDR")
            if (st.ipv6) appendLine("  ipv6: 'fc00::1'")
            appendLine("socks5:")
            appendLine("  port: $socksPort")
            appendLine("  address: 127.0.0.1")
            appendLine("  udp: 'udp'")
            if (mapDns) {
                appendLine("mapdns:")
                appendLine("  address: $DNS_ADDR")
                appendLine("  port: 53")
                appendLine("  network: 100.64.0.0")
                appendLine("  netmask: 255.192.0.0")
                appendLine("  cache-size: 10000")
            }
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("  connect-timeout: 30000")
            appendLine("  tcp-read-write-timeout: 300000")
            appendLine("  udp-read-write-timeout: 60000")
            appendLine("  log-level: warn")
        }
        val f = File(filesDir, "tproxy.yaml")
        f.writeText(yaml)
        if (!TProxyService.TProxyStartService(f.absolutePath, pfd.fd)) {
            throw IOException("tun2socks gagal dijalankan")
        }
        LogBus.add(if (mapDns) "DNS lewat terowongan aktif" else "DNS kustom: ${st.dns1}, ${st.dns2}")
    }

    private fun applyAppRules(b: Builder, st: AppSettings) {
        val own = packageName
        val picked = st.apps.filter { it != own }
        fun allow(p: String) = try {
            b.addAllowedApplication(p)
        } catch (_: PackageManager.NameNotFoundException) {
        }
        fun deny(p: String) = try {
            b.addDisallowedApplication(p)
        } catch (_: PackageManager.NameNotFoundException) {
        }
        if (st.appMode == 1 && picked.isNotEmpty()) {
            picked.forEach { allow(it) } // aplikasi ini otomatis di luar VPN
        } else {
            deny(own) // lalu lintas SSH/DNS-tunnel/Xray milik kita tidak boleh masuk VPN lagi
            if (st.appMode == 2) picked.forEach { deny(it) }
        }
    }

    // ---- Kecepatan, notifikasi -------------------------------------------------------------

    private fun publishSpeed(down: Long, up: Long, totalDown: Long, totalUp: Long) {
        val old = VpnState.speed.value
        fun push(l: List<Float>, v: Long): List<Float> {
            val n = ArrayList<Float>(l.size + 1)
            n.addAll(if (l.size >= 40) l.subList(l.size - 39, l.size) else l)
            n.add(v.toFloat())
            return n
        }
        VpnState.speed.value = Speed(down, up, totalDown, totalUp, push(old.downHist, down), push(old.upHist, up))
    }

    private fun fmt(bps: Long): String {
        val u = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var v = bps.toDouble()
        var i = 0
        while (v >= 1024 && i < u.size - 1) {
            v /= 1024
            i++
        }
        return if (i == 0) "${bps} B/s" else String.format(Locale.US, "%.1f %s", v, u[i])
    }

    private fun buildNotification(text: String, status: Status): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LinVpnService::class.java).setAction(ACTION_DISCONNECT), flags
        )
        val re = PendingIntent.getService(
            this, 2, Intent(this, LinVpnService::class.java).setAction(ACTION_RECONNECT), flags
        )
        val name = VpnState.profileName.value
        val b = NotificationCompat.Builder(this, LinApp.CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(if (name.isBlank()) "Lin Vpn" else "Lin Vpn · $name")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(R.drawable.ic_action_close, "Putuskan", stop)
            .addAction(R.drawable.ic_action_refresh, "Hubungkan ulang", re)
        if (status == Status.CONNECTED && VpnState.connectedAt.value > 0) {
            b.setShowWhen(true).setWhen(VpnState.connectedAt.value).setUsesChronometer(true)
        } else {
            b.setShowWhen(false)
        }
        return b.build()
    }

    private fun updateNotification() {
        val status = VpnState.status.value
        val text = when (status) {
            Status.CONNECTED -> {
                if (Store.settings.value.showSpeedNotif) {
                    val s = VpnState.speed.value
                    "↓ ${fmt(s.down)}   ↑ ${fmt(s.up)}"
                } else {
                    "Terhubung"
                }
            }
            Status.RECONNECTING -> "Menghubungkan ulang…"
            Status.DISCONNECTING -> "Memutuskan…"
            else -> "Menghubungkan…"
        }
        if (text == lastNotifText && status != Status.CONNECTED) return
        lastNotifText = text
        try {
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text, status))
        } catch (_: Exception) {
        }
    }

    private fun showForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---- Wake lock & pemantau jaringan ----------------------------------------------------

    private fun acquireWakeLock(st: AppSettings) {
        if (!st.wakeLock) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LinVpn::tunnel").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Bila jaringan yang dipakai hilang/berganti (Wi-Fi <-> seluler), langsung sambung ulang
     * tanpa menunggu keep-alive SSH habis.
     */
    private fun registerNetworkWatcher(st: AppSettings) {
        if (!st.reconnectOnNetwork) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var current: Network? = null

            override fun onAvailable(network: Network) {
                val old = current
                if (old == null) {
                    current = network
                    if (VpnState.status.value == Status.CONNECTED || VpnState.status.value == Status.RECONNECTING) {
                        LogBus.add("Jaringan tersedia, menghubungkan ulang")
                        trigger()
                    }
                }
            }

            override fun onLost(network: Network) {
                if (current != network) return
                current = null
                // Cari jaringan pengganti yang masih aktif
                val other = cm.allNetworks.firstOrNull { n ->
                    n != network && cm.getNetworkCapabilities(n)?.let {
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                            !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    } == true
                }
                if (other != null) {
                    current = other
                    LogBus.add("Jaringan berganti, menghubungkan ulang")
                    trigger()
                } else {
                    LogBus.add("Jaringan terputus, menunggu jaringan")
                }
            }

            private fun trigger() {
                reconnectRequested = true
                signal.offer(Unit)
                engine?.let { e -> Thread { e.stop() }.start() }
            }
        }
        netCallback = cb
        try {
            cm.registerNetworkCallback(req, cb)
        } catch (_: Exception) {
            netCallback = null
        }
    }

    private fun unregisterNetworkWatcher() {
        val cb = netCallback ?: return
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        netCallback = null
    }

    private fun logHeader() {
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        LogBus.add("Lin Vpn v$ver")
        LogBus.add("Berjalan di ${Build.MANUFACTURER} (${Build.DEVICE})")
        LogBus.add("Android ${Build.VERSION.RELEASE} API-${Build.VERSION.SDK_INT} (${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"})")
        LogBus.add("Memulai Profiles…")
    }
}
