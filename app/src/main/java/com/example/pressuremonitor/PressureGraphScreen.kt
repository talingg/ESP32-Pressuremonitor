package com.example.pressuremonitor

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.max

private const val LIVE_WINDOW_SECONDS = 30f

@Composable
fun PressureGraphScreen(
    viewModel: BleViewModel,
    onBack: () -> Unit
) {
    val isRecording = viewModel.isRecording
    val points = viewModel.recordedPoints

    val latestElapsed = points.lastOrNull()?.elapsedSeconds ?: 0f

    // While recording: always show the most recent 30s window (or 0..30
    // while less than 30s of data exists yet).
    // After stopping: show the full fixed range that was recorded.
    val xMin: Float
    val xMax: Float
    if (isRecording) {
        xMin = max(0f, latestElapsed - LIVE_WINDOW_SECONDS)
        xMax = max(LIVE_WINDOW_SECONDS, latestElapsed)
    } else {
        xMin = 0f
        xMax = max(1f, latestElapsed)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { viewModel.startRecording() }, enabled = !isRecording) {
                Text("Start")
            }
            Button(onClick = { viewModel.stopRecording() }, enabled = isRecording) {
                Text("Stop")
            }
            Button(onClick = { viewModel.clearRecording() }) {
                Text("Clear")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        PressureChart(
            points = points,
            xMin = xMin,
            xMax = xMax,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                // Stop an in-progress recording (and save it) before leaving,
                // rather than leaving it orphaned mid-recording.
                if (isRecording) viewModel.stopRecording()
                onBack()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }
}