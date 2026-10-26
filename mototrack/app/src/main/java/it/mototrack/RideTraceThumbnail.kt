package it.mototrack

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min

private data class TracePoint(val x: Double, val y: Double, val segment: Int)

/** A local projection preserves the route's proportions, with north at the top. */
@Composable
fun RideTraceThumbnail(points: List<Sample>, modifier: Modifier = Modifier) {
    val trace = remember(points) {
        val valid = points.filter { it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 }
        val origin = valid.firstOrNull()
        if (origin == null) emptyList() else {
            val longitudeScale = cos(Math.toRadians(valid.map { it.lat }.average())).coerceAtLeast(0.000001)
            var previousLongitude = origin.lon
            var longitudeOffset = 0.0
            valid.map { point ->
                // Unwrap longitude so a crossing of the date line stays a short route.
                longitudeOffset += ((point.lon - previousLongitude + 540.0) % 360.0) - 180.0
                previousLongitude = point.lon
                TracePoint(longitudeOffset * longitudeScale, origin.lat - point.lat, point.segment)
            }
        }
    }
    val color = colorResource(R.color.map_pin_green)
    // Decorative: the surrounding clickable card already announces the ride's data.
    Canvas(modifier) {
        if (trace.isEmpty()) return@Canvas
        val inset = 6.dp.toPx()
        val minX = trace.minOf { it.x }
        val maxX = trace.maxOf { it.x }
        val minY = trace.minOf { it.y }
        val maxY = trace.maxOf { it.y }
        val width = maxX - minX
        val height = maxY - minY
        val scale = min((size.width - inset * 2).coerceAtLeast(0f) / width.coerceAtLeast(1e-9),
            (size.height - inset * 2).coerceAtLeast(0f) / height.coerceAtLeast(1e-9))
        fun position(point: TracePoint) = Offset(
            (size.width / 2 + (point.x - (minX + maxX) / 2) * scale).toFloat(),
            (size.height / 2 + (point.y - (minY + maxY) / 2) * scale).toFloat())
        val path = Path()
        trace.forEachIndexed { index, point ->
            val offset = position(point)
            if (index == 0 || point.segment != trace[index - 1].segment) {
                path.moveTo(offset.x, offset.y)
                drawCircle(color, 1.5.dp.toPx(), offset)
            } else path.lineTo(offset.x, offset.y)
        }
        drawPath(path, color, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
