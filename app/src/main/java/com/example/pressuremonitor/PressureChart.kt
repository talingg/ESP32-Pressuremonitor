package com.example.pressuremonitor

/*
  Simple line chart drawn with Compose's Canvas - no external charting
  library dependency needed. Y-axis auto-scales to the data (with a bit
  of padding); X-axis range (xMin/xMax, in seconds) is controlled by the
  caller, so the same composable can show either a sliding 30s live
  window or a full fixed recorded range.
*/

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max

@Composable
fun PressureChart(
    points: List<PressurePoint>,
    xMin: Float,
    xMax: Float,
    modifier: Modifier = Modifier
) {
    val visiblePoints = points.filter { it.elapsedSeconds in xMin..xMax }

    val yMin: Float
    val yMax: Float
    if (visiblePoints.isEmpty()) {
        yMin = -1f
        yMax = 1f
    } else {
        val dataMin = visiblePoints.minOf { it.pressure }
        val dataMax = visiblePoints.maxOf { it.pressure }
        val padding = max(0.5f, (dataMax - dataMin) * 0.15f)
        yMin = dataMin - padding
        yMax = dataMax + padding
    }

    // Reuse the app's own theme colors so the chart matches the rest of
    // the UI: same background as the app, line color same as buttons.
    val backgroundColor = MaterialTheme.colorScheme.background
    val lineColor = MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f)
    val labelColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)

    Box(
        modifier = modifier.background(backgroundColor)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 44.dp, bottom = 20.dp, end = 8.dp, top = 8.dp)
        ) {
            // Axes
            drawLine(
                color = axisColor,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 2f
            )
            drawLine(
                color = axisColor,
                start = Offset(0f, 0f),
                end = Offset(0f, size.height),
                strokeWidth = 2f
            )

            if (visiblePoints.size >= 2) {
                val xSpan = (xMax - xMin).takeIf { it > 0f } ?: 1f
                val ySpan = (yMax - yMin).takeIf { it > 0f } ?: 1f

                val path = Path()
                visiblePoints.forEachIndexed { index, point ->
                    val xFrac = ((point.elapsedSeconds - xMin) / xSpan).coerceIn(0f, 1f)
                    val yFrac = ((point.pressure - yMin) / ySpan).coerceIn(0f, 1f)
                    val x = xFrac * size.width
                    val y = size.height - (yFrac * size.height)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path = path, color = lineColor, style = Stroke(width = 4f))
            }
        }

        // Axis labels - kept simple (just the range endpoints) rather than
        // a full tick/gridline system, which would add a lot more code
        // for limited extra clarity at this stage.
        Text(
            text = "%.1f hPa".format(yMax),
            color = labelColor,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.TopStart)
        )
        Text(
            text = "%.1f hPa".format(yMin),
            color = labelColor,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart)
        )
        Text(
            text = "%.0fs".format(xMin),
            color = labelColor,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 44.dp)
        )
        Text(
            text = "%.0fs".format(xMax),
            color = labelColor,
            fontSize = 10.sp,
            modifier = Modifier.align(Alignment.BottomEnd)
        )
    }
}