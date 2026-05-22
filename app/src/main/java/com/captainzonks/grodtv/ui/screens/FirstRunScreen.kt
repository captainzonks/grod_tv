package com.captainzonks.grodtv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.captainzonks.grodtv.appContainer
import com.captainzonks.grodtv.net.getLanAddresses

private const val ApiPort = 7878

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun FirstRunScreen(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = remember { context.appContainer }
    val settings by container.settings.collectAsState()
    val addresses = remember { getLanAddresses() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("grod_tv", color = Color.White, fontSize = 48.sp)
        Spacer(Modifier.height(16.dp))
        Text("Point grod_remote at this device", color = Color.White, fontSize = 22.sp)
        Spacer(Modifier.height(32.dp))

        if (addresses.isEmpty()) {
            Text("No LAN address detected (Wi-Fi off?)", color = Color.Red, fontSize = 22.sp)
        } else {
            for (ip in addresses) {
                Text("$ip:$ApiPort", color = Color.Cyan, fontSize = 32.sp)
            }
        }

        Spacer(Modifier.height(24.dp))
        val pinLine = if (settings.apiPin.isEmpty()) "PIN: none (open access)"
        else "PIN: ${settings.apiPin}"
        Text(pinLine, color = Color.White, fontSize = 20.sp)

        Spacer(Modifier.height(48.dp))
        Button(onClick = onDismiss) {
            Text("OK")
        }
    }
}
