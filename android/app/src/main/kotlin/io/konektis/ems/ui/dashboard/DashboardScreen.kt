package io.konektis.ems.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.ConnectionState
import io.konektis.ems.ui.charger.ChargerScreen
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.heatpump.HeatPumpScreen
import io.konektis.ems.ui.overview.OverviewScreen

private data class TabItem(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("Overview", EmsIcons.House),
    TabItem("Charger", EmsIcons.Charger),
    TabItem("Heat Pump", EmsIcons.HeatPump),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    connectionState: ConnectionState,
    onSettingsClick: () -> Unit
) {
    val status by vm.statusState.collectAsState()
    val controlState by vm.controlState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val statusBanner: Pair<Color, String>? = when (connectionState) {
        is ConnectionState.Connected -> null
        is ConnectionState.Connecting -> Color(0xFFFBBF24) to "Connecting…"
        is ConnectionState.Disconnected ->
            Color(0xFFF87171) to "Disconnected${connectionState.error?.let { " — $it" } ?: ""}"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EMS") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { paddingValues ->
        Column(Modifier.padding(paddingValues)) {
            if (statusBanner != null) {
                val (bannerColor, bannerText) = statusBanner
                Surface(
                    color = bannerColor.copy(alpha = 0.2f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(bannerText, color = bannerColor, fontSize = 12.sp, modifier = Modifier.padding(8.dp))
                }
            }
            when (selectedTab) {
                0 -> OverviewScreen(state = status)
                1 -> ChargerScreen(
                    statusState = status,
                    controlState = controlState,
                    onSetCharging = vm::setCharging
                )
                2 -> HeatPumpScreen(state = status)
            }
        }
    }
}
