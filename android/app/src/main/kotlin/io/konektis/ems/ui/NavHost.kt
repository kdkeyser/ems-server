package io.konektis.ems.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.konektis.ems.EmsApplication
import io.konektis.ems.ui.dashboard.DashboardScreen
import io.konektis.ems.ui.dashboard.DashboardViewModel
import io.konektis.ems.ui.settings.SettingsScreen
import io.konektis.ems.ui.settings.SettingsViewModel

private inline fun <reified T : ViewModel> factory(crossinline create: () -> T) =
    object : ViewModelProvider.Factory {
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM {
            @Suppress("UNCHECKED_CAST")
            return create() as VM
        }
    }

@Composable
fun EmsNavHost(app: EmsApplication) {
    val navController = rememberNavController()

    val dashboardVm: DashboardViewModel = viewModel(factory = factory {
        DashboardViewModel(
            statusFlow    = app.component.statusWsClient.statusFlow,
            controlState  = app.component.controlWsClient.connectionState,
            mode          = app.component.controlWsClient.mode,
            chargerControl = app.component.controlWsClient.chargerControl,
            sendCommand   = { app.component.controlWsClient.send(it) }
        )
    })

    val settingsVm: SettingsViewModel = viewModel(factory = factory {
        SettingsViewModel(app.component.settingsRepository)
    })

    val connectionState by app.component.statusWsClient.connectionState.collectAsState()

    NavHost(navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                vm = dashboardVm,
                connectionState = connectionState,
                onSettingsClick = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(vm = settingsVm, onBack = { navController.popBackStack() })
        }
    }
}
