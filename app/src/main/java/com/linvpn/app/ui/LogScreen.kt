package com.linvpn.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linvpn.app.core.LogBus
import com.linvpn.app.data.Store

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen() {
    val lines by LogBus.lines.collectAsState()
    val st by Store.settings.collectAsState()
    val state = rememberLazyListState()
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current

    // Auto scroll: ikuti baris terbaru selama diaktifkan
    LaunchedEffect(lines.size, st.autoScroll) {
        if (st.autoScroll && lines.isNotEmpty()) state.scrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text("Log", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
            FilterChip(
                selected = st.autoScroll,
                onClick = { Store.updateSettings { it.copy(autoScroll = !it.autoScroll) } },
                label = { Text("Auto scroll") }
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            TextButton(onClick = {
                clip.setText(AnnotatedString(LogBus.asText()))
                Toast.makeText(ctx, "Log disalin", Toast.LENGTH_SHORT).show()
            }) { Text("Salin") }
            TextButton(onClick = {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, LogBus.asText())
                ctx.startActivity(Intent.createChooser(i, "Bagikan log"))
            }) { Text("Bagikan") }
            TextButton(onClick = { LogBus.clear() }) { Text("Bersihkan") }
        }
        if (lines.isEmpty()) {
            Text(
                "Belum ada log. Tekan tombol hubungkan di Beranda.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        LazyColumn(
            state = state,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
        ) {
            itemsIndexed(lines) { _, l ->
                val bad = l.text.contains("Gagal", true) || l.text.contains("error", true) || l.text.contains("ditolak", true) ||
                    l.text.contains("terputus", true) || l.text.contains("tidak valid", true)
                val good = l.text.startsWith("Terhubung") || l.text.contains("berhasil", true) || l.text.contains("aktif", true)
                val color = when {
                    bad -> MaterialTheme.colorScheme.error
                    good -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                val dim = MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = dim)) { append("[${l.time}] ") }
                        withStyle(SpanStyle(color = color)) { append(l.text) }
                    },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}
