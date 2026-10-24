package com.example.pressuremonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    isConnected: Boolean,
    isConnecting: Boolean,
    onConnectClick: () -> Unit,
    onGraphClick: () -> Unit,
    onSessionsClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onConnectClick) {
                Text("Connect")
            }
            Spacer(modifier = Modifier.width(12.dp))

            // Spinner: visible only while a connection attempt is in
            // progress (from button press until isConnected becomes true).
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            // LED-style status dot: green = connected, red = not connected
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (isConnected) Color(0xFF00C853) else Color(0xFFD50000))
            )
        }

        Button(onClick = onGraphClick, modifier = Modifier.fillMaxWidth()) {
            Text("Pressure Graph")
        }

        Button(onClick = onSessionsClick, modifier = Modifier.fillMaxWidth()) {
            Text("Logged Sessions")
        }
    }
}