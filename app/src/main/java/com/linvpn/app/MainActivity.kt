@file:OptIn(ExperimentalMaterial3Api::class)

package com.linvpn.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.linvpn.app.core.VpnState
import com.linvpn.app.data.Profile
import com.linvpn.app.data.Store
import com.linvpn.app.service.LinVpnService
import com.linvpn.app.ui.HomeScreen
import com.linvpn.app.ui.LinTheme
import com.linvpn.app.ui.LogScreen
import com.linvpn.app.ui.ProfileEditor
import com.linvpn.app.ui.ProfilesScreen
import com.linvpn.app.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private var pendingProfile = ""

    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            LinVpnService.connect(this, pendingProfile)
        } else {
            Toast.makeText(this, "Izin VPN ditolak", Toast.LENGTH_SHORT).show()
        }
    }

    private val notifLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
        prepareVpn()
    }

    private fun requestConnect(profileId: String) {
        pendingProfile = profileId
        Store.updateSettings { it.copy(lastProfileId = profileId) }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            prepareVpn()
        }
    }

    private fun prepareVpn() {
        val i = VpnService.prepare(this)
        if (i != null) vpnLauncher.launch(i) else LinVpnService.connect(this, pendingProfile)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val st by Store.settings.collectAsState()
            LinTheme(st) {
                AppRoot(
                    onConnect = { requestConnect(it) },
                    onDisconnect = { LinVpnService.disconnect(this) },
                    onReconnect = { LinVpnService.reconnect(this) }
                )
            }
        }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

@Composable
private fun AppRoot(onConnect: (String) -> Unit, onDisconnect: () -> Unit, onReconnect: () -> Unit) {
    val tabs = listOf(
        Tab("Beranda", Icons.Default.Home),
        Tab("Profil", Icons.Default.Person),
        Tab("Log", Icons.Default.List),
        Tab("Pengaturan", Icons.Default.Settings)
    )
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    val banner by VpnState.banner.collectAsState()
    val popup by VpnState.bannerPopup.collectAsState()

    BackHandler(enabled = tab != 0 && editing == null) { tab = 0 }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                NavigationRail {
                    tabs.forEachIndexed { i, t ->
                        NavigationRailItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) }
                        )
                    }
                }
            }
            Scaffold(
                modifier = Modifier.weight(1f),
                bottomBar = {
                    if (!wide) {
                        NavigationBar {
                            tabs.forEachIndexed { i, t ->
                                NavigationBarItem(
                                    selected = tab == i, onClick = { tab = i },
                                    icon = { Icon(t.icon, contentDescription = t.label) }, label = { Text(t.label) }
                                )
                            }
                        }
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (tab) {
                        0 -> HomeScreen(onConnect, onDisconnect, onReconnect, onGoProfiles = { tab = 1 })
                        1 -> ProfilesScreen(onEdit = { editing = it ?: Profile() })
                        2 -> LogScreen()
                        else -> SettingsScreen()
                    }
                }
            }
        }
        editing?.let { p -> ProfileEditor(p, onClose = { editing = null }) }
    }

    if (popup && banner.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { VpnState.bannerPopup.value = false },
            title = { Text("Pesan dari server") },
            text = { Text(banner) },
            confirmButton = { TextButton(onClick = { VpnState.bannerPopup.value = false }) { Text("OK") } }
        )
    }
}
