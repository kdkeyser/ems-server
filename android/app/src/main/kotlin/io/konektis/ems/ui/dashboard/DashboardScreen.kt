package io.konektis.ems.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.konektis.ems.data.ConnectionState
import io.konektis.ems.data.ControlState
import io.konektis.ems.data.model.ManagerMode
import io.konektis.ems.ui.charger.ChargerScreen
import io.konektis.ems.ui.components.EmsIcons
import io.konektis.ems.ui.heatpump.HeatPumpScreen
import io.konektis.ems.ui.overview.OverviewScreen
import io.konektis.ems.ui.theme.LocalEmsColors
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import io.konektis.ems.R

private data class TabItem(@StringRes val labelRes: Int, val icon: ImageVector)

private val tabs = listOf(
    TabItem(R.string.tab_overview, EmsIcons.House),
    TabItem(R.string.tab_charger, EmsIcons.Charger),
    TabItem(R.string.tab_heatpump, EmsIcons.HeatPump),
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
    val mode by vm.mode.collectAsState()
    val chargerControl by vm.chargerControl.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    val statusBanner: Pair<Color, String>? = when (connectionState) {
        is ConnectionState.Connected -> null
        is ConnectionState.Connecting -> Color(0xFFFBBF24) to stringResource(R.string.conn_connecting)
        is ConnectionState.Disconnected ->
            Color(0xFFF87171) to (stringResource(R.string.conn_disconnected) +
                (connectionState.error?.let { " — $it" } ?: ""))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EMS") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    val label = stringResource(tab.labelRes)
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = label) },
                        label = { Text(label) },
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
            ModeCard(
                controlState = controlState,
                mode = mode,
                onSetMode = vm::setMode,
            )
            when (selectedTab) {
                0 -> OverviewScreen(state = status)
                1 -> ChargerScreen(
                    statusState = status,
                    controlState = controlState,
                    chargerControl = chargerControl,
                    mode = mode,
                    onSetCharging = vm::setCharging
                )
                2 -> HeatPumpScreen(state = status)
            }
        }
    }
}

/**
 * AUTO/MANUAL master switch. The switch is ON for AUTO (the EMS optimises power) and OFF for
 * MANUAL (the EMS hands control back and stops steering). Switching to MANUAL asks for
 * confirmation because it changes how every device behaves.
 */
@Composable
private fun ModeCard(
    controlState: ControlState,
    mode: ManagerMode?,
    onSetMode: (ManagerMode) -> Unit,
) {
    val ems = LocalEmsColors.current
    val authenticated = controlState is ControlState.Authenticated
    val controllable = authenticated && mode != null
    var confirmManual by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ems_mode_label), style = MaterialTheme.typography.labelSmall, color = ems.idle)
                val subtitle = when {
                    !authenticated -> stringResource(R.string.ems_mode_auth_hint)
                    mode == null -> stringResource(R.string.ems_mode_waiting)
                    mode == ManagerMode.AUTO -> stringResource(R.string.ems_mode_auto_subtitle)
                    else -> stringResource(R.string.ems_mode_manual_subtitle)
                }
                Text(
                    when (mode) {
                        ManagerMode.AUTO -> stringResource(R.string.ems_mode_auto)
                        ManagerMode.MANUAL -> stringResource(R.string.ems_mode_manual)
                        null -> "—"
                    },
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(subtitle, fontSize = 12.sp, color = ems.idle, modifier = Modifier.padding(top = 2.dp))
            }
            Switch(
                checked = mode == ManagerMode.AUTO,
                enabled = controllable,
                onCheckedChange = { toAuto ->
                    if (toAuto) onSetMode(ManagerMode.AUTO) else confirmManual = true
                },
            )
        }
    }

    if (confirmManual) {
        AlertDialog(
            onDismissRequest = { confirmManual = false },
            title = { Text(stringResource(R.string.manual_dialog_title)) },
            text = {
                Text(stringResource(R.string.manual_dialog_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmManual = false
                    onSetMode(ManagerMode.MANUAL)
                }) { Text(stringResource(R.string.manual_dialog_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmManual = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
