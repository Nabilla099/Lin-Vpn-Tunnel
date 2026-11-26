@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.linvpn.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.linvpn.app.core.SlowDnsClient
import com.linvpn.app.core.Status
import com.linvpn.app.core.V2rayConfig
import com.linvpn.app.core.VpnState
import com.linvpn.app.data.DnsRecordType
import com.linvpn.app.data.DnsTransport
import com.linvpn.app.data.Profile
import com.linvpn.app.data.ProfileType
import com.linvpn.app.data.Store

@Composable
fun ProfilesScreen(onEdit: (Profile?) -> Unit) {
    val profiles by Store.profiles.collectAsState()
    val settings by Store.settings.collectAsState()
    val status by VpnState.status.collectAsState()
    var deleting by remember { mutableStateOf<Profile?>(null) }

    Box(Modifier.fillMaxSize()) {
        if (profiles.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("Belum ada profil", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tambahkan akun SSH atau konfigurasi V2Ray untuk mulai terhubung.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(profiles, key = { it.id }) { p ->
                    val selected = (settings.lastProfileId.ifBlank { profiles.first().id }) == p.id
                    ProfileCard(
                        p, selected, status != Status.IDLE,
                        onSelect = { Store.updateSettings { it.copy(lastProfileId = p.id) } },
                        onEdit = { onEdit(p) },
                        onDuplicate = { Store.duplicateProfile(p.id) },
                        onDelete = { deleting = p }
                    )
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onEdit(null) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("Tambah profil") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Hapus profil?") },
            text = { Text("\"${p.name}\" akan dihapus permanen.") },
            confirmButton = {
                TextButton(onClick = {
                    Store.deleteProfile(p.id)
                    deleting = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Batal") } }
        )
    }
}

@Composable
private fun ProfileCard(
    p: Profile, selected: Boolean, busy: Boolean,
    onSelect: () -> Unit, onEdit: () -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onSelect),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onSelect, enabled = !busy)
            Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(p.type.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(p.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Ubah") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Duplikat") }, onClick = { menu = false; onDuplicate() })
                    DropdownMenuItem(text = { Text("Hapus") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

// ---- Editor ------------------------------------------------------------------------------------

private fun validate(p: Profile): String? {
    if (p.name.isBlank()) return "Nama profil wajib diisi"
    if (p.type.isSsh) {
        if (p.username.isBlank()) return "Username SSH wajib diisi"
        if (p.privateKey.isBlank() && p.password.isEmpty()) return "Isi password atau private key"
    }
    if (p.type == ProfileType.SSH_PAYLOAD || p.type == ProfileType.SSH_TLS) {
        if (p.host.isBlank()) return "Host SSH wajib diisi"
        if (p.port !in 1..65535) return "Port SSH tidak valid"
        if ((p.proxyHost.isBlank()) != (p.proxyPort == 0)) return "Proxy: isi host dan port, atau kosongkan keduanya"
    }
    if (p.type.usesSlowDns) {
        try {
            SlowDnsClient.validate(p)
        } catch (e: IllegalArgumentException) {
            return e.message
        }
    }
    if (p.type.isV2ray) {
        if (p.v2rayConfig.isBlank()) return "Konfigurasi V2Ray kosong"
        try {
            V2rayConfig.prepare(p.v2rayConfig, 1080, null)
        } catch (e: Exception) {
            return "Konfigurasi V2Ray tidak valid: ${e.message}"
        }
    }
    return null
}

@Composable
fun ProfileEditor(initial: Profile, onClose: () -> Unit) {
    var p by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPass by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    BackHandler(onBack = onClose)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Default.ArrowBack, contentDescription = "Kembali") }
                Text(
                    if (Store.profiles.value.any { it.id == initial.id }) "Ubah profil" else "Profil baru",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f)
                )
                Button(onClick = {
                    val e = validate(p)
                    if (e != null) {
                        error = e
                    } else {
                        Store.saveProfile(p.copy(host = p.host.trim(), username = p.username.trim()))
                        onClose()
                    }
                }) { Text("Simpan") }
                Spacer(Modifier.padding(4.dp))
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                    SectionCard("Umum") {
                        OutlinedTextField(
                            value = p.name, onValueChange = { p = p.copy(name = it) },
                            label = { Text("Nama profil") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    SectionCard("Metode") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProfileType.values().forEach { t ->
                                FilterChip(
                                    selected = p.type == t,
                                    onClick = {
                                        var np = p.copy(type = t)
                                        if (t == ProfileType.SSH_TLS && p.port == 80) np = np.copy(port = 443)
                                        if (t == ProfileType.SSH_PAYLOAD && p.port == 443) np = np.copy(port = 80)
                                        p = np
                                    },
                                    label = { Text(t.label) }
                                )
                            }
                        }
                    }

                    if (p.type.isSsh) {
                        SectionCard("Akun SSH") {
                            if (p.type != ProfileType.SSH_SLOWDNS) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = p.host, onValueChange = { p = p.copy(host = it) },
                                        label = { Text("Host / IP server") }, singleLine = true, modifier = Modifier.weight(1f)
                                    )
                                    NumField("Port", p.port, { p = p.copy(port = it) }, Modifier.weight(0.45f))
                                }
                            }
                            OutlinedTextField(
                                value = p.username, onValueChange = { p = p.copy(username = it) },
                                label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = p.password, onValueChange = { p = p.copy(password = it) },
                                label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = { TextButton(onClick = { showPass = !showPass }) { Text(if (showPass) "Sembunyi" else "Lihat") } }
                            )
                            OutlinedTextField(
                                value = p.privateKey, onValueChange = { p = p.copy(privateKey = it) },
                                label = { Text("Private key (opsional)") }, minLines = 2, maxLines = 6,
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (p.type == ProfileType.SSH_PAYLOAD || p.type == ProfileType.SSH_TLS) {
                        SectionCard(if (p.type == ProfileType.SSH_TLS) "TLS & Payload" else "Payload") {
                            if (p.type == ProfileType.SSH_TLS) {
                                OutlinedTextField(
                                    value = p.sni, onValueChange = { p = p.copy(sni = it) },
                                    label = { Text("SNI (opsional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                                )
                            }
                            OutlinedTextField(
                                value = p.payload, onValueChange = { p = p.copy(payload = it) },
                                label = { Text(if (p.type == ProfileType.SSH_TLS) "Payload (opsional)" else "Payload (kosongkan untuk langsung)") },
                                placeholder = { Text("GET / HTTP/1.1[crlf]Host: [host][crlf]Upgrade: websocket[crlf][crlf]") },
                                minLines = 3, maxLines = 8,
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "Placeholder: [crlf] [host] [port] [host_port] [ua] [rotate=a;b] [split] [delay_split]",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = p.proxyHost, onValueChange = { p = p.copy(proxyHost = it) },
                                    label = { Text("Proxy HTTP (opsional)") }, singleLine = true, modifier = Modifier.weight(1f)
                                )
                                NumField("Port", p.proxyPort, { p = p.copy(proxyPort = it) }, Modifier.weight(0.45f))
                            }
                        }
                    }

                    if (p.type.usesSlowDns) {
                        SectionCard("SlowDNS") {
                            DropdownField(
                                label = "Tipe koneksi",
                                valueLabel = p.dnsRecord.label,
                                options = DnsRecordType.values().map { it.label to it },
                                onSelect = { p = p.copy(dnsRecord = it) }
                            )
                            Text(
                                when (p.dnsRecord) {
                                    DnsRecordType.AUTO -> "Mencoba TXT lalu NULL secara berurutan."
                                    DnsRecordType.TXT -> "Klien dnstt standar."
                                    else -> "Klien VayDNS: server harus memakai tipe ${p.dnsRecord.label} yang sama."
                                },
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            DropdownField(
                                label = "Transport DNS",
                                valueLabel = p.dnsTransport.label,
                                options = DnsTransport.values().map { it.label to it },
                                onSelect = { p = p.copy(dnsTransport = it) }
                            )
                            OutlinedTextField(
                                value = p.dnsServer, onValueChange = { p = p.copy(dnsServer = it) },
                                label = {
                                    Text(
                                        when (p.dnsTransport) {
                                            DnsTransport.UDP -> "Resolver DNS (mis. 8.8.8.8)"
                                            DnsTransport.DOT -> "Resolver DoT (mis. dns.google:853)"
                                            DnsTransport.DOH -> "URL DoH (mis. https://dns.google/dns-query)"
                                        }
                                    )
                                },
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = p.nsDomain, onValueChange = { p = p.copy(nsDomain = it) },
                                label = { Text("Name Server (NS)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = p.pubKey, onValueChange = { p = p.copy(pubKey = it.trim()) },
                                label = { Text("Public key NS (64 hex)") }, minLines = 2, maxLines = 3,
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    if (p.type.isV2ray) {
                        SectionCard("Konfigurasi V2Ray") {
                            OutlinedTextField(
                                value = p.v2rayConfig, onValueChange = { p = p.copy(v2rayConfig = it) },
                                label = { Text("JSON konfigurasi") }, minLines = 10, maxLines = 24,
                                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    val text = clip.getText()?.text ?: ""
                                    val r = V2rayConfig.importText(text)
                                    if (r == null) {
                                        Toast.makeText(ctx, "Clipboard bukan tautan vmess/vless/trojan/ss atau JSON valid", Toast.LENGTH_LONG).show()
                                    } else {
                                        p = p.copy(v2rayConfig = r.second, name = if (p.name == "Profil baru") r.first else p.name)
                                    }
                                }) { Text("Impor dari clipboard") }
                                OutlinedButton(onClick = { p = p.copy(v2rayConfig = V2rayConfig.template()) }) { Text("Contoh") }
                                OutlinedButton(onClick = {
                                    val r = V2rayConfig.pretty(p.v2rayConfig)
                                    if (r == null) Toast.makeText(ctx, "JSON tidak valid", Toast.LENGTH_SHORT).show() else p = p.copy(v2rayConfig = r)
                                }) { Text("Rapikan") }
                            }
                            Text(
                                "Inbound SOCKS diatur otomatis oleh aplikasi. Hindari aturan geoip/geosite karena berkas geo tidak disertakan.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp).navigationBarsPadding())
                }
            }
        }
    }
}
