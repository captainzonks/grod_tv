package com.captainzonks.grodtv.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

/**
 * Draw a thick brand-purple ring while the modified composable is focused.
 * Used to make focus state legible on Buttons whose color tween is too
 * subtle to read from across the room.
 */
private fun Modifier.focusRing(focused: Boolean): Modifier =
    if (focused) {
        this.border(
            BorderStroke(3.dp, GrodColors.BrandPurpleFocused),
            shape = RoundedCornerShape(12.dp),
        )
    } else {
        this
    }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { context.appContainer }
    val settings by container.settings.collectAsState()
    val scope = rememberCoroutineScope()

    var qualityField by remember(settings.defaultQuality) { mutableStateOf(settings.defaultQuality) }
    var qualityExpanded by remember { mutableStateOf(false) }
    var qualityFocused by remember { mutableStateOf(false) }
    var testFocused by remember { mutableStateOf(false) }
    var backFocused by remember { mutableStateOf(false) }
    var urlRowFocused by remember { mutableStateOf(false) }
    var pinRowFocused by remember { mutableStateOf(false) }

    // Which field, if any, is being edited via the on-screen IME dialog.
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    var connState by remember { mutableStateOf<ConnState>(ConnState.Idle) }

    val firstRowFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(50L)
        runCatching { firstRowFocus.requestFocus() }
    }

    // Persist quality eagerly — it's a one-shot Quality enum pick, not a
    // free-text edit, so there's no rubber-banding to worry about.
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
                    if (editing != null) {
                        editing = null
                        true
                    } else {
                        onBack()
                        true
                    }
                } else {
                    false
                }
            },
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Settings", color = Color.White, fontSize = 32.sp)

        // Piped API URL — click to open IME dialog. No auto-IME on focus,
        // which is the core of the D-pad UX fix.
        EditableSettingRow(
            label = "Piped API URL",
            value = settings.pipedApiUrl,
            focused = urlRowFocused,
            modifier = Modifier
                .focusRequester(firstRowFocus)
                .onFocusChanged { urlRowFocused = it.isFocused },
            onClick = { editing = EditTarget.PipedUrl(settings.pipedApiUrl) },
        )

        // Quality — own Column so vertical focus traversal stops on the
        // Button itself, not on a Row that does not announce as focusable.
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
                modifier = Modifier
                    // Match the other settings rows so vertical D-pad
                    // traversal does not skip this Button because of a
                    // narrower hit region than its siblings.
                    .fillMaxWidth()
                    .onFocusChanged { qualityFocused = it.isFocused }
                    .focusRing(qualityFocused)
                    .focusable(),
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

        EditableSettingRow(
            label = "API PIN (blank = open access)",
            value = if (settings.apiPin.isEmpty()) "—" else "•".repeat(settings.apiPin.length),
            focused = pinRowFocused,
            modifier = Modifier.onFocusChanged { pinRowFocused = it.isFocused },
            onClick = { editing = EditTarget.ApiPin(settings.apiPin) },
        )

        Button(
            onClick = {
                connState = ConnState.Testing
                scope.launch {
                    val client = PipedClient(baseUrl = settings.pipedApiUrl, http = container.httpClient)
                    client.search("test")
                        .onSuccess { connState = ConnState.Ok(it.size) }
                        .onFailure { connState = ConnState.Fail(it.message ?: "unknown error") }
                }
            },
            colors = grodButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { testFocused = it.isFocused }
                .focusRing(testFocused)
                .focusable(),
        ) {
            Text("Test connection")
        }
        val (connLabel, connColor) = when (val s = connState) {
            ConnState.Idle -> "" to Color.White
            ConnState.Testing -> "Testing…" to Color.Yellow
            is ConnState.Ok -> "OK (${s.resultCount} results)" to Color.Green
            is ConnState.Fail -> "FAIL: ${s.msg}" to Color.Red
        }
        if (connLabel.isNotEmpty()) {
            Text(connLabel, color = connColor)
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onBack,
            colors = grodButtonColors(),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { backFocused = it.isFocused }
                .focusRing(backFocused)
                .focusable(),
        ) { Text("Back") }
    }

    // Render the IME dialog last so it overlays everything; the dialog
    // itself is the *only* place a soft keyboard appears, so D-pad
    // traversal across the settings list never accidentally pops an IME.
    editing?.let { target ->
        TextEntryDialog(
            title = when (target) {
                is EditTarget.PipedUrl -> "Edit Piped API URL"
                is EditTarget.ApiPin -> "Edit API PIN"
            },
            initial = target.initial,
            keyboardType = when (target) {
                is EditTarget.PipedUrl -> KeyboardType.Uri
                is EditTarget.ApiPin -> KeyboardType.NumberPassword
            },
            obscure = target is EditTarget.ApiPin,
            onAccept = { value ->
                scope.launch {
                    when (target) {
                        is EditTarget.PipedUrl ->
                            if (value.isNotBlank()) container.settingsStore.setPipedApiUrl(value)
                        is EditTarget.ApiPin ->
                            container.settingsStore.setApiPin(value)
                    }
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

private sealed class EditTarget(val initial: String) {
    class PipedUrl(initial: String) : EditTarget(initial)
    class ApiPin(initial: String) : EditTarget(initial)
}

/**
 * A read-only row that looks like a labelled field but is actually a
 * focusable / clickable surface. D-pad CENTER triggers `onClick`, which
 * is the only path that opens the soft keyboard via [TextEntryDialog].
 *
 * Why not a TextField with a focus listener that hides IME? The Compose
 * Material3 OutlinedTextField pops the IME on EditText.requestFocus and
 * there is no documented switch to opt out. Replacing the field with a
 * pure-Compose surface side-steps the EditText entirely.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EditableSettingRow(
    label: String,
    value: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            color = if (focused) GrodColors.SlateLight else GrodColors.Slate,
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onClick,
            colors = ButtonDefaults.colors(
                containerColor = Color.Black,
                contentColor = Color.White,
                focusedContainerColor = GrodColors.FieldFocusedBg,
                focusedContentColor = Color.White,
                pressedContainerColor = GrodColors.BrandPurplePressed,
                pressedContentColor = Color.White,
            ),
            modifier = modifier
                .fillMaxWidth()
                .border(
                    BorderStroke(
                        width = if (focused) 3.dp else 1.dp,
                        color = if (focused) GrodColors.BrandPurpleFocused else GrodColors.Slate,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
                .focusable(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    value.ifBlank { "(empty)" },
                    color = if (value.isBlank()) GrodColors.Slate else Color.White,
                    fontSize = 16.sp,
                )
            }
        }
    }
}

/**
 * Pop-up text-entry dialog. The TextField inside auto-claims focus, which
 * is the *one* place we explicitly want the IME to show on a TV remote.
 */
@Composable
private fun TextEntryDialog(
    title: String,
    initial: String,
    keyboardType: KeyboardType,
    obscure: Boolean,
    onAccept: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 50ms is the same yield-one-frame pattern HomeScreen uses; the
        // FocusRequester needs the field's LayoutNode attached before
        // requestFocus() can succeed.
        kotlinx.coroutines.delay(50L)
        runCatching { fieldFocus.requestFocus() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .padding(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(720.dp)
                    .background(Color.Black, shape = RoundedCornerShape(16.dp))
                    .border(
                        BorderStroke(2.dp, GrodColors.BrandPurpleFocused),
                        shape = RoundedCornerShape(16.dp),
                    )
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(title, color = Color.White, fontSize = 22.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = if (obscure) PasswordVisualTransformation()
                    else androidx.compose.ui.text.input.VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    colors = grodTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(fieldFocus),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    var saveFocused by remember { mutableStateOf(false) }
                    var cancelFocused by remember { mutableStateOf(false) }
                    @OptIn(ExperimentalTvMaterial3Api::class)
                    Button(
                        onClick = { onAccept(text) },
                        colors = grodButtonColors(),
                        modifier = Modifier
                            .onFocusChanged { saveFocused = it.isFocused }
                            .focusRing(saveFocused)
                            .focusable(),
                    ) { Text("Save") }
                    @OptIn(ExperimentalTvMaterial3Api::class)
                    Button(
                        onClick = onDismiss,
                        colors = grodButtonColors(),
                        modifier = Modifier
                            .onFocusChanged { cancelFocused = it.isFocused }
                            .focusRing(cancelFocused)
                            .focusable(),
                    ) { Text("Cancel") }
                }
            }
        }
    }
}
