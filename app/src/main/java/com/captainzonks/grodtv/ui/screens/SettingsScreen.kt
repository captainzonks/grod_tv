package com.captainzonks.grodtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.captainzonks.grodtv.appContainer
import com.captainzonks.grodtv.piped.PipedClient
import com.captainzonks.grodtv.piped.Quality
import kotlinx.coroutines.launch

private sealed class ConnState {
    object Idle : ConnState()
    object Testing : ConnState()
    data class Ok(val resultCount: Int) : ConnState()
    data class Fail(val msg: String) : ConnState()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { context.appContainer }
    val settings by container.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var urlField by remember(settings.pipedApiUrl) { mutableStateOf(settings.pipedApiUrl) }
    var pinField by remember(settings.apiPin) { mutableStateOf(settings.apiPin) }
    var qualityField by remember(settings.defaultQuality) { mutableStateOf(settings.defaultQuality) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var connState by remember { mutableStateOf<ConnState>(ConnState.Idle) }

    // Persist edits as they happen; cheap on DataStore.
    LaunchedEffect(urlField) {
        if (urlField.isNotBlank() && urlField != settings.pipedApiUrl) {
            container.settingsStore.setPipedApiUrl(urlField)
        }
    }
    LaunchedEffect(pinField) {
        if (pinField != settings.apiPin) {
            container.settingsStore.setApiPin(pinField)
        }
    }
    LaunchedEffect(qualityField) {
        if (qualityField != settings.defaultQuality) {
            container.settingsStore.setDefaultQuality(qualityField)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", color = Color.White, fontSize = 32.sp)

        OutlinedTextField(
            value = urlField,
            onValueChange = { urlField = it },
            label = { androidx.compose.material3.Text("Piped API URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Default quality: ", color = Color.White)
            Button(onClick = { qualityExpanded = true }) {
                Text(qualityField.label)
            }
            DropdownMenu(
                expanded = qualityExpanded,
                onDismissRequest = { qualityExpanded = false },
            ) {
                for (q in Quality.entries) {
                    DropdownMenuItem(
                        text = { androidx.compose.material3.Text(q.label) },
                        onClick = {
                            qualityField = q
                            qualityExpanded = false
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = pinField,
            onValueChange = { pinField = it },
            label = { androidx.compose.material3.Text("API PIN (blank = open access)") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Button(
                onClick = {
                    connState = ConnState.Testing
                    scope.launch {
                        val client = PipedClient(baseUrl = urlField, http = container.httpClient)
                        client.search("test")
                            .onSuccess { connState = ConnState.Ok(it.size) }
                            .onFailure { connState = ConnState.Fail(it.message ?: "unknown error") }
                    }
                },
            ) {
                Text("Test connection")
            }
            Spacer(Modifier.width(16.dp))
            val (label, color) = when (val s = connState) {
                ConnState.Idle -> "" to Color.White
                ConnState.Testing -> "Testing…" to Color.Yellow
                is ConnState.Ok -> "OK (${s.resultCount} results)" to Color.Green
                is ConnState.Fail -> "FAIL: ${s.msg}" to Color.Red
            }
            Text(label, color = color)
        }

        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) { Text("Back") }
    }
}
