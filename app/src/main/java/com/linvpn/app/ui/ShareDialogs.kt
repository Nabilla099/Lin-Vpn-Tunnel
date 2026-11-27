package com.linvpn.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.linvpn.app.data.NeedPasswordException
import com.linvpn.app.data.Profile
import com.linvpn.app.data.ProfileShare
import com.linvpn.app.data.ShareOptions
import com.linvpn.app.data.Store
import com.linvpn.app.data.WrongPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Ekspor ke berkas .ln atau tautan "lin-vpn:" lengkap dengan opsi penyedia. */
@Composable
fun ExportDialog(profiles: List<Profile>, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val exportable = profiles.filter { !it.locked }
    var password by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var blockRoot by remember { mutableStateOf(false) }
    var lock by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }

    fun build(): ByteArray? = try {
        ProfileShare.encode(
            exportable,
            ShareOptions(password, days.toIntOrNull()?.coerceIn(0, 3650) ?: 0, note, blockRoot, lock)
        )
    } catch (e: Exception) {
        Toast.makeText(ctx, e.message ?: "Gagal membuat berkas", Toast.LENGTH_LONG).show()
        null
    }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val data = pending
        if (uri != null && data != null) {
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                Toast.makeText(ctx, "Berkas disimpan", Toast.LENGTH_SHORT).show()
                onDismiss()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Gagal menyimpan: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        pending = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ekspor profil") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (exportable.isEmpty()) {
                    Text("Profil terkunci penyedia tidak bisa diekspor.", color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        "${exportable.size} profil. Isi berkas terenkripsi, tidak terbaca saat disadap.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password (opsional)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = days, onValueChange = { days = it.filter(Char::isDigit).take(4) },
                    label = { Text("Kedaluwarsa dalam (hari)") },
                    supportingText = { Text("Kosong atau 0 = tidak kedaluwarsa") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Catatan dukungan (HTML/CSS)") },
                    supportingText = { Text("Contoh: <b style=\"color:#e91e63\">Promo</b> <span style=\"color:#2e7d32\">aktif</span>") },
                    minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth()
                )
                if (note.isNotBlank()) {
                    Text("Pratinjau", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HtmlText(note, Modifier.fillMaxWidth())
                }
                SwitchRow("Blokir perangkat root", "Profil menolak tersambung di perangkat root", blockRoot) { blockRoot = it }
                SwitchRow("Kunci penyedia", "Detail akun disembunyikan; tidak bisa diubah, diduplikat, atau diekspor ulang", lock) { lock = it }
            }
        },
        confirmButton = {
            Row {
                TextButton(enabled = exportable.isNotEmpty(), onClick = {
                    build()?.let { clip.setText(AnnotatedString(ProfileShare.toLink(it))); Toast.makeText(ctx, "Tautan lin-vpn disalin", Toast.LENGTH_SHORT).show() }
                }) { Text("Salin tautan") }
                TextButton(enabled = exportable.isNotEmpty(), onClick = {
                    build()?.let { pending = it; saver.launch("profil.ln") }
                }) { Text("Simpan .ln") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

/** Impor dari berkas .ln atau tautan "lin-vpn:" (data langsung atau URL acak yang menyimpan berkas). */
@Composable
fun ImportDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var needPw by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<ByteArray?>(null) }

    fun process(data: ByteArray) {
        pending = data
        try {
            val list = ProfileShare.decode(data, password)
            val n = Store.importList(list)
            Toast.makeText(ctx, "$n profil diimpor", Toast.LENGTH_SHORT).show()
            onDismiss()
        } catch (e: NeedPasswordException) {
            needPw = true; error = "Berkas dilindungi password, atau formatnya tidak dikenali"
        } catch (e: WrongPasswordException) {
            needPw = true; error = "Password salah atau berkas rusak"
        } catch (e: Exception) {
            error = "Format tidak dikenali"
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true; error = null
                try {
                    val data = withContext(Dispatchers.IO) {
                        ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
                    }
                    process(data)
                } catch (e: Exception) {
                    error = "Gagal membaca berkas"
                } finally {
                    busy = false
                }
            }
        }
    }

    fun importLink() {
        scope.launch {
            busy = true; error = null
            try {
                val data = pending.takeIf { needPw } ?: withContext(Dispatchers.IO) { ProfileShare.resolve(text) }
                process(data)
            } catch (e: Exception) {
                error = e.message ?: "Tautan tidak valid"
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Impor profil") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it; needPw = false; error = null },
                    label = { Text("Tautan lin-vpn:…") }, minLines = 2, maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { text = clip.getText()?.text.orEmpty().trim(); needPw = false; error = null }) { Text("Tempel") }
                    TextButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) { Text("Pilih berkas .ln") }
                }
                if (needPw) {
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && (text.isNotBlank() || needPw), onClick = {
                importLink()
            }) { Text(if (busy) "Memproses…" else "Impor") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
