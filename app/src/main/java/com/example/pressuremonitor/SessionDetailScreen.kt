package com.example.pressuremonitor

/*
  Shows one saved session's full recorded graph, with pinch-to-zoom on the
  time axis (standard two-finger zoom) and drag-to-pan once zoomed in.

  Zoom level controls how wide a time window (in seconds) is visible;
  panning moves that window's center left/right. The Y-axis keeps
  auto-scaling to whatever's currently visible (handled inside
  PressureChart), so zooming in also "zooms" the pressure axis to the
  detail of that time slice.
*/

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MIN_ZOOM = 1f   // 1x = fully zoomed out, shows the whole session
private const val MAX_ZOOM = 20f  // 20x = narrowest zoomed-in window

@Composable
fun SessionDetailScreen(
    sessionId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var session by remember { mutableStateOf<PressureSession?>(null) }

    LaunchedEffect(sessionId) {
        session = SessionStorage.loadSession(context, sessionId)
    }

    val formatter = remember { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()) }
    val points = session?.points ?: emptyList()
    val fullDuration = points.lastOrNull()?.elapsedSeconds?.coerceAtLeast(1f) ?: 1f

    // Zoom/pan state - keyed on sessionId so opening a different session
    // starts fresh at "fully zoomed out" rather than keeping old zoom/pan.
    var zoom by remember(sessionId) { mutableStateOf(MIN_ZOOM) }
    var centerSeconds by remember(sessionId) { mutableStateOf(fullDuration / 2f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = formatter.format(Date(sessionId)),
            fontSize = 18.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        val halfSpan = (fullDuration / zoom) / 2f
        val xMin = (centerSeconds - halfSpan).coerceAtLeast(0f)
        val xMax = (centerSeconds + halfSpan).coerceAtMost(fullDuration)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .pointerInput(fullDuration) {
                    detectTransformGestures { _, pan, zoomChange, _ ->
                        val windowSpanBefore = fullDuration / zoom
                        val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        val windowSpanAfter = fullDuration / newZoom

                        // Convert the drag's pixel offset into seconds using
                        // the window span visible *before* this update, then
                        // shift the center (dragging right reveals earlier
                        // time, so subtract).
                        val secondsPerPixel = windowSpanBefore / size.width.toFloat()
                        val newCenter = centerSeconds - pan.x * secondsPerPixel

                        val newHalfSpan = windowSpanAfter / 2f
                        centerSeconds = if (windowSpanAfter >= fullDuration) {
                            fullDuration / 2f
                        } else {
                            newCenter.coerceIn(newHalfSpan, fullDuration - newHalfSpan)
                        }
                        zoom = newZoom
                    }
                }
        ) {
            PressureChart(
                points = points,
                xMin = xMin,
                xMax = xMax,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}