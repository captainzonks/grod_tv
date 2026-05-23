package com.captainzonks.grodtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.captainzonks.grodtv.appContainer
import com.captainzonks.grodtv.piped.PipedClient
import com.captainzonks.grodtv.piped.Quality
import com.captainzonks.grodtv.ui.GrodColors
import kotlinx.coroutines.launch

private sealed class ConnState {
    object Idle : ConnState()
    object Testing : ConnState()
    data class Ok(val resultCount: Int) : ConnState()
    data class Fail(val msg: String) : ConnState()
}

/**
 * Common OutlinedTextField colors override. Default Material3 light theme
 * renders dark text on light field background — invisible on our black
 * scaffold. Force white-on-dark + brand-purple focus state.
 */
@Composable
private fun grodTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = GrodColors.Slate,
    cursorColor = Color.White,
    focusedBorderColor = GrodColors.BrandPurpleFocused,
    unfocusedBorderColor = GrodColors.Slate,
    focusedLabelColor = GrodColors.SlateLight,
    unfocusedLabelColor = GrodColors.Slate,
    focusedContainerColor = GrodColors.FieldFocusedBg,
    unfocusedContainerColor = Color.Black,
)

/**
 * Common TV Button color override so focused / pressed states are visible
 * on the dark scaffold.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun grodButtonColors() = ButtonDefaults.colors(
    containerColor = GrodColors.Slate,
    contentColor = Color.White,
    focusedContainerColor = GrodColors.BrandPurpleFocused,
    focusedContentColor = Color.White,
    pressedContainerColor = GrodColors.BrandPurplePressed,
    pressedContentColor = Color.White,
)

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

    // Focus the URL field on entry so D-pad CENTER does not bleed to the
    // PlayerView still alive underneath the nav graph.
    val urlFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(50L)
        runCatching { urlFocus.requestFocus() }
    }

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
            .padding(32.dp)
            .focusGroup()
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.key == Key.Back || e.key == Key.Escape)
                ) {
                    onBack()
                    true
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", color = Color.White, fontSize = 32.sp)

        OutlinedTextField(
            value = urlField,
            onValueChange = { urlField = it },
            label = { androidx.compose.material3.Text("Piped API URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = grodTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(urlFocus),
        )

        // Quality lives in its own Column so vertical focus traversal stops on
        // the Button itself, not on a Row that does not announce as focusable.
        Column {
            Text(
                "Default quality",
                color = GrodColors.SlateLight,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { qualityExpanded = true },
                colors = grodButtonColors(),
                modifier = Modifier.focusable(),
            ) {
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
            colors = grodTextFieldColors(),
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
                colors = grodButtonColors(),
                modifier = Modifier.focusable(),
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
        Button(
            onClick = onBack,
            colors = grodButtonColors(),
            modifier = Modifier.focusable(),
        ) { Text("Back") }
    }
}
