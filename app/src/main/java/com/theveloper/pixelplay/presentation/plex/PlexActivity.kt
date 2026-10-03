package com.theveloper.pixelplay.presentation.plex

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.ui.theme.PixelPlayTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class PlexActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PixelPlayTheme { PlexScreen(onClose = { finish() }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlexScreen(onClose: () -> Unit, viewModel: PlexViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var server by rememberSaveable { mutableStateOf("") }
    // Keep the token out of saved instance state and clear it once connected.
    var token by remember { mutableStateOf("") }
    LaunchedEffect(state.connected) { if (state.connected) token = "" }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Plex music") }, navigationIcon = {
            TextButton(onClick = onClose) { Text("Back") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Listen to your Plex music in PixelPlayer", style = MaterialTheme.typography.headlineSmall)
            if (state.connected) {
                Text(state.server, style = MaterialTheme.typography.bodyMedium)
                Text("Music libraries", style = MaterialTheme.typography.titleMedium)
                state.libraries.forEach { Text(it.title) }
                Button(onClick = viewModel::sync, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Import / refresh music")
                }
                Text("Imported tracks use this app's player and appear alongside your other music. " +
                    "The server must be reachable during playback.")
                OutlinedButton(onClick = viewModel::disconnect, enabled = !state.busy) { Text("Disconnect Plex") }
            } else {
                Text("Enter the address of your Plex Media Server and its X-Plex-Token. " +
                    "Use a reachable HTTPS address for remote listening.")
                OutlinedTextField(server, { server = it }, label = { Text("Server URL") },
                    placeholder = { Text("http://192.168.1.10:32400") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("X-Plex-Token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !state.busy, modifier = Modifier.fillMaxWidth())
                Text("The token is stored securely on this device. It is never added to imported song URLs.")
                Button(onClick = { viewModel.connect(server, token) },
                    enabled = !state.busy && server.isNotBlank() && token.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text("Connect Plex")
                }
            }
            if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
        }
    }
}
