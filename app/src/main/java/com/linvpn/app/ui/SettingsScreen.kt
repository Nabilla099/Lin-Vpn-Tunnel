@file:OptIn(ExperimentalMaterial3Api::class)

package com.linvpn.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.linvpn.app.data.AppSettings
import com.linvpn.app.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface Dlg {
    class Num(val title: String, val hint: String, val value: Int, val min: Int, val max: Int, val apply: (AppSettings, Int) -> AppSettings) : Dlg
    class Txt(val title: String, val value: String, val apply: (AppSettings, String) -> AppSettings) : Dlg
    class Choice(val title: String, val options: List<String>, val selected: Int, val apply: (AppSettings, Int) -> AppSettings) : Dlg
}

@Composable
fun SettingsScreen() {
    val s by Store.settings.collectAsState()
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    var picker by remember { mutableStateOf(false) }
    var shareExport by remember { mutableStateOf(false) }
    var shareImport by remember { mutableStateOf(false) }
    if (shareExport) ExportDialog(Store.profiles.value) { shareExport = false }
    if (shareImport) ImportDialog { shareImport = false }

    fun upd(b: (AppSettings) -> AppSettings) = Store.updateSettings(b)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(vertical = 8.dp)) {
            Text("Pengaturan", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Text(
                "Perubahan diterapkan pada koneksi berikutnya.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            SettingGroup("Tampilan") {
                SettingItem("Tema", listOf("Ikuti sistem", "Terang", "Gelap")[s.theme.coerceIn(0, 2)]) {
                    dlg = Dlg.Choice("Tema", listOf("Ikuti sistem", "Terang", "Gelap"), s.theme) { a, v -> a.copy(theme = v) }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    SettingDivider()
                    SettingSwitch("Warna dinamis", "Ambil warna dari wallpaper", s.dynamicColor) { v -> upd { it.copy(dynamicColor = v) } }
                }
            }

            SettingGroup("Koneksi") {
                SettingSwitch("Hubungkan ulang otomatis", "Sambung lagi bila koneksi terputus", s.autoReconnect) { v -> upd { it.copy(autoReconnect = v) } }
                SettingDivider()
                SettingItem("Batas percobaan", if (s.maxRetries == 0) "Tanpa batas" else "${s.maxRetries}×", "0 = terus mencoba") {
                    dlg = Dlg.Num("Batas percobaan", "0 = tanpa batas", s.maxRetries, 0, 100) { a, v -> a.copy(maxRetries = v) }
                }
                SettingDivider()
                SettingItem("Jeda percobaan", "${s.retryDelaySec} dtk", "Bertambah bertahap, maksimal 30 dtk") {
                    dlg = Dlg.Num("Jeda percobaan (detik)", "", s.retryDelaySec, 1, 30) { a, v -> a.copy(retryDelaySec = v) }
                }
                SettingDivider()
                SettingSwitch("Sambung ulang saat jaringan berganti", "Wi-Fi ↔ seluler", s.reconnectOnNetwork) { v -> upd { it.copy(reconnectOnNetwork = v) } }
                SettingDivider()
                SettingItem("Timeout koneksi", "${s.connectTimeoutSec} dtk") {
                    dlg = Dlg.Num("Timeout koneksi (detik)", "SlowDNS minimal 45 dtk otomatis", s.connectTimeoutSec, 5, 180) { a, v -> a.copy(connectTimeoutSec = v) }
                }
                SettingDivider()
                SettingItem("Keep-alive SSH", "${s.keepAliveSec} dtk", "Interval ping agar sesi tidak diputus") {
                    dlg = Dlg.Num("Keep-alive SSH (detik)", "", s.keepAliveSec, 5, 300) { a, v -> a.copy(keepAliveSec = v) }
                }
                SettingDivider()
                SettingSwitch("Kompresi SSH", "Hemat kuota, sedikit menambah beban CPU", s.compression) { v -> upd { it.copy(compression = v) } }
                SettingDivider()
                SettingSwitch(
                    "Algoritma SSH lawas",
                    "Hanya untuk server tua yang gagal negosiasi. Kurang aman, nyalakan bila perlu.",
                    s.legacySsh
                ) { v -> upd { it.copy(legacySsh = v) } }
                SettingDivider()
                SettingSwitch("Wake lock", "Jaga CPU tetap aktif saat terhubung", s.wakeLock) { v -> upd { it.copy(wakeLock = v) } }
                SettingDivider()
                SettingSwitch("Mulai saat perangkat menyala", "Perlu izin VPN yang sudah diberikan", s.startOnBoot) { v -> upd { it.copy(startOnBoot = v) } }
                SettingDivider()
                SettingItem("Abaikan optimasi baterai", "", "Agar koneksi tidak dimatikan sistem di latar belakang") {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        Toast.makeText(ctx, "Tidak bisa membuka pengaturan baterai", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            SettingGroup("Jaringan") {
                SettingItem("Port SOCKS lokal", s.socksPort.toString()) {
                    dlg = Dlg.Num("Port SOCKS lokal", "", s.socksPort, 1024, 65535) { a, v -> a.copy(socksPort = v) }
                }
                SettingDivider()
                SettingItem("MTU", s.mtu.toString()) {
                    dlg = Dlg.Num("MTU", "Turunkan bila ada situs yang macet", s.mtu, 576, 9000) { a, v -> a.copy(mtu = v) }
                }
                SettingDivider()
                SettingItem(
                    "Mode DNS", if (s.dnsMode == 0) "Lewat terowongan" else "Kustom",
                    "Lewat terowongan: DNS di-resolve oleh server, tanpa bocor"
                ) {
                    dlg = Dlg.Choice("Mode DNS", listOf("Lewat terowongan (disarankan)", "DNS kustom"), s.dnsMode) { a, v -> a.copy(dnsMode = v) }
                }
                if (s.dnsMode == 1) {
                    SettingDivider()
                    SettingItem("DNS utama", s.dns1) { dlg = Dlg.Txt("DNS utama", s.dns1) { a, v -> a.copy(dns1 = v) } }
                    SettingDivider()
                    SettingItem("DNS cadangan", s.dns2) { dlg = Dlg.Txt("DNS cadangan", s.dns2) { a, v -> a.copy(dns2 = v) } }
                }
                SettingDivider()
                SettingSwitch("IPv6", "Arahkan lalu lintas IPv6 lewat tunnel", s.ipv6) { v -> upd { it.copy(ipv6 = v) } }
                SettingDivider()
                SettingSwitch("Lewati jaringan lokal", "Perangkat di Wi-Fi/hotspot tetap bisa diakses", s.bypassLan) { v -> upd { it.copy(bypassLan = v) } }
                SettingDivider()
                SettingSwitch("UDP (UDPGW)", "Untuk game, panggilan, dan DNS kustom; server perlu UDPGW", s.udpgw) { v -> upd { it.copy(udpgw = v) } }
                SettingDivider()
                SettingItem("Port UDPGW", s.udpgwPort.toString()) {
                    dlg = Dlg.Num("Port UDPGW di server", "", s.udpgwPort, 1, 65535) { a, v -> a.copy(udpgwPort = v) }
                }
            }

            SettingGroup("Aplikasi") {
                SettingItem(
                    "Mode", listOf("Semua aplikasi", "Hanya terpilih", "Kecuali terpilih")[s.appMode.coerceIn(0, 2)]
                ) {
                    dlg = Dlg.Choice("Aplikasi yang memakai VPN", listOf("Semua aplikasi", "Hanya aplikasi terpilih", "Semua kecuali terpilih"), s.appMode) { a, v -> a.copy(appMode = v) }
                }
                SettingDivider()
                SettingItem("Pilih aplikasi", "${s.apps.size} dipilih") { picker = true }
            }

            SettingGroup("Log") {
                SettingSwitch("Auto scroll", "Otomatis ke baris terbaru", s.autoScroll) { v -> upd { it.copy(autoScroll = v) } }
                SettingDivider()
                SettingItem("Batas baris", "${s.logMax}") {
                    dlg = Dlg.Num("Batas baris log", "Lebih kecil = lebih ringan", s.logMax, 100, 5000) { a, v -> a.copy(logMax = v) }
                }
                SettingDivider()
                SettingSwitch("Bersihkan log saat menyambung", null, s.clearLogOnConnect) { v -> upd { it.copy(clearLogOnConnect = v) } }
            }

            SettingGroup("Notifikasi & banner") {
                SettingSwitch("Kecepatan di notifikasi", "Unduh/unggah diperbarui tiap detik", s.showSpeedNotif) { v -> upd { it.copy(showSpeedNotif = v) } }
                SettingDivider()
                SettingSwitch("Tampilkan pesan server", "Munculkan banner dari akun SSH saat terhubung", s.showBanner) { v -> upd { it.copy(showBanner = v) } }
            }

            SettingGroup("Data") {
                SettingItem("Ekspor profil", "", "Simpan sebagai berkas .ln atau tautan Link-Vpn") { shareExport = true }
                SettingDivider()
                SettingItem("Impor profil", "", "Dari berkas .ln atau tautan Link-Vpn") { shareImport = true }
                SettingDivider()
                SettingItem("Setel ulang pengaturan", "", "Profil tidak dihapus") {
                    upd { AppSettings(lastProfileId = it.lastProfileId) }
                }
            }

            SettingGroup("Tentang") {
                val ver = try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
                } catch (_: Exception) {
                    ""
                }
                SettingItem("Lin Vpn", "v$ver", "Gunakan dengan bijak dan patuhi hukum yang berlaku") {}
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (val d = dlg) {
        is Dlg.Num -> NumberDialog(d.title, d.hint, d.value, d.min, d.max, { dlg = null }) { v ->
            upd { d.apply(it, v) }
            dlg = null
        }
        is Dlg.Txt -> TextDialog(d.title, d.value, { dlg = null }) { v ->
            upd { d.apply(it, v) }
            dlg = null
        }
        is Dlg.Choice -> ChoiceDialog(d.title, d.options, d.selected, { dlg = null }) { v ->
            upd { d.apply(it, v) }
            dlg = null
        }
        null -> {}
    }
    if (picker) AppPicker(s.apps, onClose = { picker = false }) { sel -> upd { it.copy(apps = sel) } }
}

private data class AppInfo(val label: String, val pkg: String)

@Composable
private fun AppPicker(initial: Set<String>, onClose: () -> Unit, onChange: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf(initial) }
    BackHandler(onBack = onClose)
    val apps by produceState(initialValue = emptyList<AppInfo>()) {
        value = withContext(Dispatchers.IO) { loadApps(ctx) }
    }
    val shown = apps.filter { query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "Kembali") }
                Text("Pilih aplikasi", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { sel = emptySet(); onChange(sel) }) { Text("Kosongkan") }
            }
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                label = { Text("Cari aplikasi") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
            if (apps.isEmpty()) {
                Text("Memuat…", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(shown, key = { it.pkg }) { a ->
                    val on = a.pkg in sel
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            sel = if (on) sel - a.pkg else sel + a.pkg
                            onChange(sel)
                        }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(a.label, style = MaterialTheme.typography.bodyLarge)
                            Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun loadApps(ctx: Context): List<AppInfo> {
    val pm = ctx.packageManager
    return try {
        pm.getInstalledApplications(0)
            .filter { it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
            .map { AppInfo(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.label.lowercase() }
    } catch (_: Exception) {
        emptyList()
    }
}
