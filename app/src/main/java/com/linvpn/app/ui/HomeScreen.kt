package com.linvpn.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linvpn.app.core.NetInfo
import com.linvpn.app.core.Speed
import com.linvpn.app.core.Status
import com.linvpn.app.core.VpnState
import com.linvpn.app.data.Profile
import com.linvpn.app.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onGoProfiles: () -> Unit
) {
    val status by VpnState.status.collectAsState()
    val profiles by Store.profiles.collectAsState()
    val settings by Store.settings.collectAsState()
    val selected = profiles.firstOrNull { it.id == settings.lastProfileId } ?: profiles.firstOrNull()
    val busy = status != Status.IDLE

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize().verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
            if (wide) {
                Row(
                    Modifier.widthIn(max = 1000.dp).fillMaxWidth().padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Header()
                        ConnectArea(status, selected, onConnect, onDisconnect, onGoProfiles)
                        ProfilePicker(profiles, selected, busy, onGoProfiles)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SpeedCard(status)
                        ActionRow(status, onReconnect)
                        IpCard(status)
                        NoteCard(selected)
                        BannerCard()
                    }
                }
            } else {
                Column(
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Header()
                    ConnectArea(status, selected, onConnect, onDisconnect, onGoProfiles)
                    ProfilePicker(profiles, selected, busy, onGoProfiles)
                    SpeedCard(status)
                    ActionRow(status, onReconnect)
                    IpCard(status)
                    NoteCard(selected)
                    BannerCard()
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Text("Lin Vpn", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

private fun statusLabel(s: Status) = when (s) {
    Status.IDLE -> "Terputus"
    Status.CONNECTING -> "Menghubungkan…"
    Status.CONNECTED -> "Terhubung"
    Status.RECONNECTING -> "Menghubungkan ulang…"
    Status.DISCONNECTING -> "Memutuskan…"
}

@Composable
private fun ConnectArea(
    status: Status,
    selected: Profile?,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit,
    onGoProfiles: () -> Unit
) {
    val connectedAt by VpnState.connectedAt.collectAsState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status, connectedAt) {
        while (status == Status.CONNECTED) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PowerButton(status) {
            when {
                status != Status.IDLE -> onDisconnect()
                selected != null -> onConnect(selected.id)
                else -> onGoProfiles()
            }
        }
        Text(statusLabel(status), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (status == Status.CONNECTED && connectedAt > 0) {
            Text(
                fmtDuration(now - connectedAt),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (status == Status.IDLE && selected == null) {
            Text("Buat profil dulu di tab Profil", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PowerButton(status: Status, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val connected = status == Status.CONNECTED
    val working = status == Status.CONNECTING || status == Status.RECONNECTING || status == Status.DISCONNECTING
    val bg = if (connected) cs.primary else cs.surfaceContainerHigh
    val fg = if (connected) cs.onPrimary else cs.primary
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )
    val pulse by spin.animateFloat(
        initialValue = 0.15f, targetValue = 0.45f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        if (connected) {
            Box(Modifier.size(200.dp).alpha(pulse).background(cs.primary, CircleShape))
        }
        if (working) {
            Canvas(Modifier.size(196.dp).rotate(angle)) {
                drawArc(
                    color = cs.primary, startAngle = 0f, sweepAngle = 100f, useCenter = false,
                    style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }
        Box(
            Modifier.size(164.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.size(64.dp)) {
                val sw = 7.dp.toPx()
                val pad = sw / 2
                drawArc(
                    color = fg, startAngle = -55f, sweepAngle = 290f, useCenter = false,
                    topLeft = Offset(pad, pad + 4.dp.toPx()), size = Size(size.width - sw, size.height - sw - 4.dp.toPx()),
                    style = Stroke(width = sw, cap = StrokeCap.Round)
                )
                drawLine(fg, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height * 0.46f), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun ProfilePicker(profiles: List<Profile>, selected: Profile?, busy: Boolean, onGoProfiles: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Profil aktif", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (selected != null) {
                    Text(selected.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${selected.type.label} · ${selected.summary()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Belum ada profil", style = MaterialTheme.typography.titleMedium)
                }
            }
            Box {
                TextButton(enabled = !busy, onClick = { if (profiles.isEmpty()) onGoProfiles() else open = true }) {
                    Text(if (profiles.isEmpty()) "Buat" else "Ganti")
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    profiles.forEach { p ->
                        DropdownMenuItem(
                            text = { Text(p.name) },
                            onClick = {
                                open = false
                                Store.updateSettings { it.copy(lastProfileId = p.id) }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedCard(status: Status) {
    val sp by VpnState.speed.collectAsState()
    SectionCard("Kecepatan sinyal") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SpeedValue("↓ Unduh", if (status == Status.CONNECTED) fmtSpeed(sp.down) else "–", MaterialTheme.colorScheme.primary)
            SpeedValue("↑ Unggah", if (status == Status.CONNECTED) fmtSpeed(sp.up) else "–", MaterialTheme.colorScheme.secondary, end = true)
        }
        Spark(sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Total ↓ ${fmtBytes(sp.totalDown)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Total ↑ ${fmtBytes(sp.totalUp)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SpeedValue(label: String, value: String, color: Color, end: Boolean = false) {
    Column(horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color, textAlign = if (end) TextAlign.End else TextAlign.Start)
    }
}

@Composable
private fun Spark(sp: Speed) {
    val c1 = MaterialTheme.colorScheme.primary
    val c2 = MaterialTheme.colorScheme.secondary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(64.dp)) {
        val max = maxOf(1f, (sp.downHist + sp.upHist).maxOrNull() ?: 1f)
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
        fun line(h: List<Float>, color: Color) {
            if (h.size < 2) return
            val path = Path()
            h.forEachIndexed { i, v ->
                val x = size.width * i / 39f
                val y = size.height - (v / max) * (size.height - 4.dp.toPx()) - 2.dp.toPx()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        line(sp.downHist, c1)
        line(sp.upHist, c2)
    }
}

@Composable
private fun ActionRow(status: Status, onReconnect: () -> Unit) {
    if (status == Status.IDLE) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = onReconnect, enabled = status != Status.DISCONNECTING) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Hubungkan ulang")
        }
    }
}

@Composable
private fun IpCard(status: Status) {
    var local by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var pub by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val port by VpnState.socksPort.collectAsState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(status) {
        delay(if (status == Status.CONNECTED) 500 else 0)
        local = withContext(Dispatchers.IO) { NetInfo.localIps() }
        if (status != Status.CONNECTED) pub = null
    }
    SectionCard("IP") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("IP lokal", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { scope.launch { local = withContext(Dispatchers.IO) { NetInfo.localIps() } } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Segarkan")
            }
        }
        if (local.isEmpty()) {
            Text("Tidak ada jaringan aktif", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        local.forEach { (n, ip) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(n, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(ip, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("IP publik (lewat tunnel)", style = MaterialTheme.typography.labelLarge)
                val pubNow = pub
                Text(
                    when {
                        loading -> "Memeriksa…"
                        pubNow != null -> pubNow
                        else -> "–"
                    },
                    style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace
                )
            }
            TextButton(
                enabled = status == Status.CONNECTED && !loading && port > 0,
                onClick = {
                    scope.launch {
                        loading = true
                        pub = withContext(Dispatchers.IO) { NetInfo.publicIp(port) } ?: "gagal"
                        loading = false
                    }
                }
            ) { Text("Cek") }
        }
    }
}

@Composable
private fun BannerCard() {
    val banner by VpnState.banner.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    if (banner.isBlank()) return
    SectionCard("Pesan dari server") {
        Text(
            banner,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = if (expanded) Int.MAX_VALUE else 6,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
        )
        Text(
            if (expanded) "Ketuk untuk menciutkan" else "Ketuk untuk membuka semua",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoteCard(p: Profile?) {
    if (p == null || (p.note.isBlank() && p.expiresAt <= 0)) return
    SectionCard("Catatan penyedia") {
        if (p.note.isNotBlank()) HtmlText(p.note, Modifier.fillMaxWidth())
        if (p.expiresAt > 0) {
            val d = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("id", "ID")).format(java.util.Date(p.expiresAt))
            Text(
                if (p.isExpired()) "Kedaluwarsa sejak $d" else "Berlaku sampai $d",
                style = MaterialTheme.typography.labelMedium,
                color = if (p.isExpired()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
