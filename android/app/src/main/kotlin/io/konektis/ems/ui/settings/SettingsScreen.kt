package io.konektis.ems.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.konektis.ems.data.settings.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val saved by vm.settingsFlow.collectAsState(initial = Settings())
    var serverUrl by rememberSaveable(saved.serverUrl) { mutableStateOf(saved.serverUrl) }
    var username  by rememberSaveable(saved.username)  { mutableStateOf(saved.username) }
    var password  by rememberSaveable(saved.password)  { mutableStateOf(saved.password) }
    var useTls         by rememberSaveable(saved.useTls)              { mutableStateOf(saved.useTls) }
    var cfId           by rememberSaveable(saved.cfAccessClientId)    { mutableStateOf(saved.cfAccessClientId) }
    var cfSecret       by rememberSaveable(saved.cfAccessClientSecret){ mutableStateOf(saved.cfAccessClientSecret) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
        Column(
            Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = { serverUrl = it },
                label = { Text("Server address") },
                placeholder = { Text("ems.kenas.be or 10.0.2.2:8080") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = cfId,
                onValueChange = { cfId = it },
                label = { Text("CF Access Client ID") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = cfSecret,
                onValueChange = { cfSecret = it },
                label = { Text("CF Access Client Secret") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Use TLS (wss)")
                Spacer(Modifier.weight(1f))
                Switch(checked = useTls, onCheckedChange = { useTls = it })
            }
            Button(
                onClick = {
                    vm.save(
                        Settings(
                            serverUrl = serverUrl.trim(),
                            username = username.trim(),
                            password = password,
                            useTls = useTls,
                            cfAccessClientId = cfId.trim(),
                            cfAccessClientSecret = cfSecret.trim()
                        )
                    )
                    onBack()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save") }
        }
    }
}
