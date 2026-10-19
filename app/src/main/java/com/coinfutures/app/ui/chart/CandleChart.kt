package com.coinfutures.app.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coinfutures.app.data.settings.TimeDisplay
import com.coinfutures.app.ui.format.Fmt
import com.coinfutures.app.ui.theme.AppTheme
import com.coinfutures.engine.Candle
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class LevelKind { SUPPORT, RESISTANCE, INTERACTION, ENTRY, STOP, TARGET }

/** A horizontal level or band drawn over the candles. [label] is always drawn (not color only). */
data class ChartLevel(val kind: LevelKind, val label: String, val lower: BigDecimal, val upper: BigDecimal? = null)

/**
 * Custom Canvas candlestick chart (no third-party chart licence needed). Pinch to zoom, drag to
 * pan, tap a candle for its exact OHLC values. Up candles are hollow and down candles filled so
 * direction is readable without color.
 */
@Composable
fun CandleChart(
    candles: List<Candle>,
    levels: List<ChartLevel>,
    priceScale: Int,
    lastPrice: BigDecimal?,
    timeDisplay: TimeDisplay,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 320.dp,
) {
    val colors = AppTheme.trade
    val measurer = rememberTextMeasurer()
    val labelColor = colors.chartText
    val onSurface = MaterialTheme.colorScheme.onSurface
    val tooltipBg = MaterialTheme.colorScheme.surfaceContainerHighest
    var visible by remember(candles.size) { mutableFloatStateOf(min(72f, candles.size.toFloat().coerceAtLeast(1f))) }
    var offset by remember(candles.size) { mutableFloatStateOf(0f) }
    var selected by remember(candles) { mutableStateOf<Int?>(null) }
    val axisWidth = 64.dp
    val timeHeight = 18.dp

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(candles.size) {
                // Pinch = zoom; horizontal drag = pan. Vertical drags are left for the page scroll.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val plotW = size.width - axisWidth.toPx()
                        val maxVisible = min(300f, candles.size.toFloat())
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            visible = (visible / zoom).coerceIn(min(16f, maxVisible), maxVisible)
                            offset = offset.coerceIn(0f, max(0f, candles.size - visible))
                            event.changes.forEach { it.consume() }
                        } else {
                            val ch = event.changes.firstOrNull() ?: break
                            val d = ch.positionChange()
                            if (abs(d.x) > abs(d.y) && d.x != 0f) {
                                val cw = plotW / visible
                                offset = (offset + d.x / cw).coerceIn(0f, max(0f, candles.size - visible))
                                ch.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(candles.size, visible, offset) {
                detectTapGestures { pos ->
                    val plotW = size.width - axisWidth.toPx()
                    if (pos.x > plotW) { selected = null; return@detectTapGestures }
                    val cw = plotW / visible
                    val end = candles.size - offset.roundToInt()
                    val start = (end - visible.roundToInt()).coerceAtLeast(0)
                    val idx = start + (pos.x / cw).toInt()
                    selected = if (selected == idx || idx !in candles.indices) null else idx
                }
            }
    ) {
        if (candles.isEmpty()) return@Canvas
        val plotW = size.width - axisWidth.toPx()
        val plotH = size.height - timeHeight.toPx()
        val end = (candles.size - offset.roundToInt()).coerceIn(1, candles.size)
        val start = (end - visible.roundToInt()).coerceAtLeast(0)
        val view = candles.subList(start, end)
        val cw = plotW / visible

        var lo = view.minOf { it.low.toDouble() }
        var hi = view.maxOf { it.high.toDouble() }
        val span0 = (hi - lo).coerceAtLeast(1e-9)
        // Include plan levels and nearby zones so they stay visible.
        for (l in levels) {
            val a = l.lower.toDouble()
            val b = (l.upper ?: l.lower).toDouble()
            val near = a > lo - span0 * 0.6 && b < hi + span0 * 0.6
            if (l.kind == LevelKind.ENTRY || l.kind == LevelKind.STOP || l.kind == LevelKind.TARGET || near) {
                lo = min(lo, a); hi = max(hi, b)
            }
        }
        val pad = (hi - lo) * 0.06
        lo -= pad; hi += pad
        fun y(p: Double) = ((hi - p) / (hi - lo) * plotH).toFloat()

        val small = TextStyle(fontSize = 10.sp, color = labelColor, fontFamily = FontFamily.Monospace)

        // Grid and price axis
        for (i in 0..4) {
            val p = lo + (hi - lo) * i / 4.0
            val yy = y(p)
            drawLine(colors.grid, Offset(0f, yy), Offset(plotW, yy), 1f)
            drawText(measurer, fmt(p, priceScale), Offset(plotW + 6f, (yy - 7.sp.toPx()).coerceIn(0f, plotH - 14.sp.toPx())), small)
        }

        // Zones and plan levels
        levels.forEach { l ->
            val c = when (l.kind) {
                LevelKind.SUPPORT -> colors.support
                LevelKind.RESISTANCE -> colors.resistance
                LevelKind.INTERACTION -> colors.wait
                LevelKind.ENTRY -> colors.entry
                LevelKind.STOP -> colors.stop
                LevelKind.TARGET -> colors.target
            }
            val y1 = y((l.upper ?: l.lower).toDouble())
            val y2 = y(l.lower.toDouble())
            if (l.upper != null) {
                drawRect(c.copy(alpha = if (l.kind == LevelKind.ENTRY) 0.24f else 0.14f), Offset(0f, y1), Size(plotW, max(2f, y2 - y1)))
                drawLine(c.copy(alpha = 0.7f), Offset(0f, y1), Offset(plotW, y1), 1.5f)
                drawLine(c.copy(alpha = 0.7f), Offset(0f, y2), Offset(plotW, y2), 1.5f)
            } else {
                drawLine(c, Offset(0f, y1), Offset(plotW, y1), 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f)))
            }
            val tag = TextStyle(fontSize = 10.sp, color = c, fontWeight = FontWeight.Bold)
            drawText(measurer, l.label, Offset(6f, (y1 - 13.sp.toPx()).coerceIn(0f, plotH - 12.sp.toPx())), tag)
        }

        // Candles
        val bodyW = (cw * 0.62f).coerceAtLeast(1.5f)
        view.forEachIndexed { i, k ->
            val cx = i * cw + cw / 2
            val up = k.close >= k.open
            val col = if (up) colors.candleUp else colors.candleDown
            drawLine(col, Offset(cx, y(k.high.toDouble())), Offset(cx, y(k.low.toDouble())), max(1f, cw * 0.08f))
            val top = y(max(k.open.toDouble(), k.close.toDouble()))
            val bottom = y(min(k.open.toDouble(), k.close.toDouble()))
            val h = max(1.5f, bottom - top)
            if (up) {
                drawRect(col, Offset(cx - bodyW / 2, top), Size(bodyW, h), style = Stroke(width = max(1f, cw * 0.1f)))
            } else {
                drawRect(col, Offset(cx - bodyW / 2, top), Size(bodyW, h))
            }
        }

        // Last price
        if (lastPrice != null) {
            val yy = y(lastPrice.toDouble())
            if (yy in 0f..plotH) {
                drawLine(onSurface.copy(alpha = 0.6f), Offset(0f, yy), Offset(plotW, yy), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
                val txt = measurer.measure(fmt(lastPrice.toDouble(), priceScale), small.copy(color = Color.Black, fontWeight = FontWeight.Bold))
                drawRoundRect(onSurface, Offset(plotW + 2f, yy - txt.size.height / 2f - 2f), Size(axisWidth.toPx() - 4f, txt.size.height + 4f), CornerRadius(6f))
                drawText(txt, topLeft = Offset(plotW + 6f, yy - txt.size.height / 2f), color = MaterialTheme_onInverse(onSurface))
            }
        }

        // Time axis
        val tf = if (view.size > 1) view[1].openTime - view[0].openTime else 0L
        val fmtTime = DateTimeFormatter.ofPattern(if (tf >= 4 * 3_600_000L) "dd MMM" else "HH:mm", Locale.US)
        val zone: ZoneId = if (timeDisplay == TimeDisplay.UTC_ONLY) ZoneOffset.UTC else ZoneId.systemDefault()
        for (j in 0..3) {
            val i = ((view.size - 1) * j / 3.0).toInt()
            val label = fmtTime.format(Instant.ofEpochMilli(view[i].openTime).atZone(zone))
            val m = measurer.measure(label, small)
            val x = (i * cw + cw / 2 - m.size.width / 2).coerceIn(0f, plotW - m.size.width)
            drawText(m, topLeft = Offset(x, plotH + 3f))
        }

        // Selection crosshair + exact values
        selected?.let { idx ->
            if (idx < start || idx >= end) return@let
            val k = candles[idx]
            val cx = (idx - start) * cw + cw / 2
            drawLine(onSurface.copy(alpha = 0.5f), Offset(cx, 0f), Offset(cx, plotH), 1f)
            val lines = listOf(
                Fmt.shortTime(k.openTime, timeDisplay),
                "O ${fmt(k.open.toDouble(), priceScale)}  H ${fmt(k.high.toDouble(), priceScale)}",
                "L ${fmt(k.low.toDouble(), priceScale)}  C ${fmt(k.close.toDouble(), priceScale)}",
                "Vol ${Fmt.compact(k.volume)}",
            )
            tooltip(measurer, lines, tooltipBg, onSurface, cx, plotW)
        }
    }
}

private fun DrawScope.tooltip(measurer: TextMeasurer, lines: List<String>, bg: Color, fg: Color, cx: Float, plotW: Float) {
    val style = TextStyle(fontSize = 11.sp, color = fg, fontFamily = FontFamily.Monospace)
    val measured = lines.map { measurer.measure(it, style) }
    val w = measured.maxOf { it.size.width } + 20f
    val h = measured.sumOf { it.size.height } + 16f
    val x = if (cx + 12f + w < plotW) cx + 12f else (cx - 12f - w).coerceAtLeast(0f)
    drawRoundRect(bg, Offset(x, 8f), Size(w, h.toFloat()), CornerRadius(14f))
    var yy = 16f
    for (m in measured) {
        drawText(m, topLeft = Offset(x + 10f, yy))
        yy += m.size.height
    }
}

private fun MaterialTheme_onInverse(onSurface: Color): Color =
    if (onSurface.red + onSurface.green + onSurface.blue > 1.5f) Color(0xFF0A1318) else Color.White

private fun fmt(v: Double, scale: Int): String = BigDecimal(v).setScale(scale, RoundingMode.HALF_EVEN).toPlainString()
