package com.linvpn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linvpn.app.core.V2rayConfig.Form

/** Editor V2Ray berbentuk formulir (protokol, server, transport, TLS). Perubahan langsung ditulis ke JSON. */
@Composable
fun V2rayForm(form: Form, onChange: (Form) -> Unit) {
    val netOptions = listOf(
        "TCP" to "tcp", "WS" to "ws", "gRPC" to "grpc", "HTTPUpgrade" to "httpupgrade", "XHTTP" to "xhttp", "HTTP/2" to "http"
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Protokol", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("VMess" to "vmess", "VLESS" to "vless", "Trojan" to "trojan").forEach { (label, v) ->
                FilterChip(selected = form.proto == v, onClick = { onChange(form.copy(proto = v)) }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = form.address, onValueChange = { onChange(form.copy(address = it.trim())) },
                label = { Text("Alamat server") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            NumField("Port", form.port, { onChange(form.copy(port = it)) }, Modifier.weight(0.45f))
        }
        OutlinedTextField(
            value = form.id, onValueChange = { onChange(form.copy(id = it.trim())) },
            label = { Text(if (form.proto == "trojan") "Password" else "UUID") }, singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (form.proto == "vmess") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumField("Alter ID", form.alterId, { onChange(form.copy(alterId = it)) }, Modifier.weight(0.5f))
                DropdownField(
                    "Enkripsi", form.cipher,
                    listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none").map { it to it },
                    { onChange(form.copy(cipher = it)) }, Modifier.weight(1f)
                )
            }
        }
        DropdownField(
            "Jaringan", netOptions.firstOrNull { it.second == form.net }?.first ?: form.net.uppercase(),
            netOptions, { onChange(form.copy(net = it)) }, Modifier.fillMaxWidth()
        )
        DropdownField(
            "Keamanan",
            when (form.sec) { "tls" -> "TLS"; "reality" -> "Reality (tidak bisa diedit di formulir)"; else -> "None" },
            listOf("None" to "none", "TLS" to "tls"),
            { onChange(form.copy(sec = it)) }, Modifier.fillMaxWidth()
        )
        if (form.net != "tcp") {
            OutlinedTextField(
                value = form.path, onValueChange = { onChange(form.copy(path = it)) },
                label = { Text(if (form.net == "grpc") "Service name" else "Path") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (form.net != "grpc") {
                OutlinedTextField(
                    value = form.host, onValueChange = { onChange(form.copy(host = it.trim())) },
                    label = { Text("Host header") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (form.sec == "tls") {
            OutlinedTextField(
                value = form.sni, onValueChange = { onChange(form.copy(sni = it.trim())) },
                label = { Text("SNI (kosong = host/alamat)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.alpn, onValueChange = { onChange(form.copy(alpn = it.trim())) },
                    label = { Text("ALPN") }, singleLine = true, modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = form.fp, onValueChange = { onChange(form.copy(fp = it.trim())) },
                    label = { Text("Fingerprint") }, singleLine = true, modifier = Modifier.weight(1f)
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Izinkan sertifikat tidak aman", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = form.insecure, onCheckedChange = { onChange(form.copy(insecure = it)) })
            }
        }
    }
}
