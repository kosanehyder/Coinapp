package com.coinfutures.app.ui.analysis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.coinfutures.app.data.repo.AnalysisBundle
import com.coinfutures.app.data.settings.TimeDisplay
import com.coinfutures.app.ui.chart.CandleChart
import com.coinfutures.app.ui.chart.ChartLevel
import com.coinfutures.app.ui.chart.LevelKind
import com.coinfutures.app.ui.components.Bullet
import com.coinfutures.app.ui.components.LabeledValue
import com.coinfutures.app.ui.components.Pill
import com.coinfutures.app.ui.components.SectionCard
import com.coinfutures.app.ui.components.StatusBadge
import com.coinfutures.app.ui.components.statusVisual
import com.coinfutures.app.ui.format.Fmt
import com.coinfutures.app.ui.l10n.LocalStrings
import com.coinfutures.app.ui.search.contractLabel
import com.coinfutures.app.ui.theme.AppTheme
import com.coinfutures.app.ui.theme.Numeric
import com.coinfutures.engine.AnalysisResult
import com.coinfutures.engine.Bias
import com.coinfutures.engine.BiasResult
import com.coinfutures.engine.BreakStatus
import com.coinfutures.engine.Direction
import com.coinfutures.engine.OverallStatus
import com.coinfutures.engine.PlanState
import com.coinfutures.engine.ReasonCode
import com.coinfutures.engine.Timeframe
import com.coinfutures.engine.TradePlan
import com.coinfutures.engine.Zone
import java.math.BigDecimal

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MarketHeader(r: AnalysisResult, now: Long, mode: TimeDisplay) {
    val s = LocalStrings.current
    val t = AppTheme.trade
    val i = r.instrument
    val q = r.quote
    val ageMs = q?.let { (r.quoteAgeMs ?: 0) + (if (i.simulated) 0 else (now - r.snapshotTime).coerceAtLeast(0)) }
    val fresh = r.qualityOk && (ageMs ?: Long.MAX_VALUE) <= 10_000
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(contractLabel(s, i) + " · " + s.linearUsdt)
                Pill(s.timeframeProfile)
                if (i.simulated) Pill(s.simulatedVenue, color = t.unavailable, container = t.unavailableContainer)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(s.lastTrade, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Fmt.price(r.currentPrice, i), style = MaterialTheme.typography.displaySmall.merge(Numeric))
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("${s.markPrice} ${Fmt.price(q?.mark, i)}", style = MaterialTheme.typography.bodyMedium.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${s.bid} ${Fmt.price(q?.bid, i)} · ${s.ask} ${Fmt.price(q?.ask, i)}", style = MaterialTheme.typography.bodySmall.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Schedule, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(s.dataTime, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Pill(
                    (if (fresh) s.fresh else s.stale) + " · " + s.quoteAge + " " + (ageMs?.let { "${it / 1000}s" } ?: "—"),
                    color = if (fresh) t.long else t.unavailable,
                    container = if (fresh) t.longContainer else t.unavailableContainer,
                )
            }
            Spacer(Modifier.height(6.dp))
            Meta(s.snapshot, Fmt.time(r.snapshotTime, mode, withSeconds = true))
            Meta("${s.lastClosed} 15M", Fmt.time(r.lastClosedCandleTimes[Timeframe.M15], mode))
            Meta("${s.lastClosed} 1H / 4H", Fmt.shortTime(r.lastClosedCandleTimes[Timeframe.H1], mode) + " / " + Fmt.shortTime(r.lastClosedCandleTimes[Timeframe.H4], mode))
            Meta(s.source, "${r.source} · ${i.symbol}")
        }
    }
}

@Composable
private fun Meta(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall.merge(Numeric))
    }
}

/** Primary state with plain-language reason, supporting reasons and the next required condition. */
@Composable
internal fun StatusHero(r: AnalysisResult) {
    val s = LocalStrings.current
    val v = statusVisual(r.status)
    val bg by animateColorAsState(v.container, label = "statusBg")
    val i = r.instrument
    Card(colors = CardDefaults.cardColors(containerColor = bg), shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(r.status, large = true)
                Spacer(Modifier.width(8.dp))
                r.plan?.let { p ->
                    if (r.status != OverallStatus.DATA_UNAVAILABLE) {
                        Text(
                            s.direction(p.direction == Direction.LONG) + " · " + s.planState(p.state.name),
                            style = MaterialTheme.typography.labelLarge, color = v.color,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(s.reason(r.primaryReason), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            val p = r.plan
            if (r.status == OverallStatus.LONG_SETUP || r.status == OverallStatus.SHORT_SETUP) {
                if (p?.entryBand != null && p.stop != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.planSummary(
                            p.direction == Direction.LONG, Fmt.price(p.entryBand!!.lower, i), Fmt.price(p.entryBand!!.upper, i),
                            Fmt.price(p.stop, i), p.targets.getOrNull(0)?.let { Fmt.price(it.price, i) }, p.targets.getOrNull(1)?.let { Fmt.price(it.price, i) },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            val supporting = r.supportingReasons.filter { it !in setOf(ReasonCode.NEWS_NOT_CHECKED) }
            if (supporting.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(s.whyThisState, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                supporting.take(6).forEach { Bullet(s.reason(it), marker = v.color) }
            }
            r.nextCondition?.let { n ->
                val long = n.direction != Direction.SHORT
                val text = when (n.code) {
                    ReasonCode.AWAITING_BREAKOUT -> n.price?.let { s.nextBreakout(long, Fmt.price(it, i), n.minVolume?.let { v2 -> Fmt.compact(v2) + " " + i.volumeUnit }) }
                    ReasonCode.WAITING_RETEST -> n.price?.let { s.nextRetest(long, Fmt.price(it, i), n.candlesRemaining) }
                    ReasonCode.WAITING_CONFIRMATION -> n.price?.let { s.nextConfirm(long, Fmt.price(it, i), n.candlesRemaining) }
                    else -> null
                }
                if (text != null) {
                    Spacer(Modifier.height(12.dp))
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)).padding(12.dp)
                    ) {
                        Column {
                            Text(s.nextCondition, style = MaterialTheme.typography.labelLarge, color = v.color)
                            Spacer(Modifier.height(4.dp))
                            Text(text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(s.notAnOrder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PlanCard(r: AnalysisResult, p: TradePlan, revision: Int?, mode: TimeDisplay, now: Long) {
    val s = LocalStrings.current
    val t = AppTheme.trade
    val i = r.instrument
    val band = p.entryBand!!
    SectionCard(title = s.tradePlan, icon = Icons.Filled.Route, trailing = {
        Pill("${s.planRevision} ${revision ?: "—"}")
    }) {
        if (p.state == PlanState.WAITING_CONFIRMATION) {
            Pill(s.provisional, color = t.wait, container = t.waitContainer, icon = Icons.Filled.WarningAmber)
            Spacer(Modifier.height(8.dp))
        }
        if (p.state.isTerminal) {
            Pill(s.planState(p.state.name) + (p.terminalReason?.let { " · " + s.reason(it) } ?: ""), color = t.noTrade, container = t.noTradeContainer)
            Spacer(Modifier.height(8.dp))
        }
        PlanLevel(s.entryBand, "${Fmt.price(band.lower, i)} – ${Fmt.price(band.upper, i)}", t.entry, "${s.direction(p.direction == Direction.LONG)} · ${i.quote}")
        PlanLevel(s.stopLoss, Fmt.price(p.stop, i), t.stop, null)
        p.targets.forEachIndexed { idx, tg ->
            PlanLevel(
                if (idx == 0) s.target1 else s.target2, Fmt.price(tg.price, i), t.target,
                "${tg.allocation.multiply(BigDecimal(100)).stripTrailingZeros().toPlainString()}% ${s.ofPosition}",
            )
        }
        if (p.targets.size < 2) Text(s.reason(ReasonCode.MISSING_TARGETS), color = t.noTrade, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        p.metrics?.let { m ->
            LabeledValue(s.grossRr, Fmt.ratio(m.grossRrTp2))
            LabeledValue(
                s.netRr, Fmt.ratio(m.netRrTp2),
                valueColor = if (ReasonCode.RR_TOO_LOW in p.gateFailures) t.short else MaterialTheme.colorScheme.onSurface,
                caption = "${Fmt.price(m.worstEntry, i)} · fee 0.05% · slip 0.02%",
            )
            LabeledValue(s.blendedRr, Fmt.ratio(m.blendedNetRr))
        }
        LabeledValue(s.spread, Fmt.percent(p.spreadPercent), valueColor = if (ReasonCode.SPREAD_TOO_WIDE in p.gateFailures) t.short else MaterialTheme.colorScheme.onSurface)
        p.validUntil?.let { LabeledValue(s.validUntil, Fmt.time(it, mode)) }
        if (p.state == PlanState.ENTRY_READY) p.candlesRemaining?.let { LabeledValue(s.candlesLeft, it.toString()) }
        when (p.liveRelation) {
            ReasonCode.PRICE_ABOVE_BAND, ReasonCode.PRICE_BELOW_BAND -> Bullet(s.dontChase, marker = t.short)
            ReasonCode.PRICE_IN_BAND -> Bullet(s.inBandNow, marker = t.long)
            else -> Unit
        }
        Spacer(Modifier.height(8.dp))
        Text(s.cancelsIf, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val far = if (p.direction == Direction.LONG) p.zone.lower else p.zone.upper
        Bullet("${s.cancelFarClose} (${Fmt.price(far, i)})", marker = t.stop)
        Bullet("${s.cancelStop} (${Fmt.price(p.stop, i)})", marker = t.stop)
        Bullet(s.cancelStale, marker = t.stop)
        Bullet(s.cancelExpiry + (p.validUntil?.let { " (${Fmt.shortTime(it, mode)})" } ?: ""), marker = t.stop)
    }
}

@Composable
private fun PlanLevel(label: String, value: String, color: Color, caption: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 36.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Text(value, style = MaterialTheme.typography.titleMedium.merge(Numeric), fontWeight = FontWeight.SemiBold)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ChartCard(b: AnalysisBundle, tf: Timeframe, mode: TimeDisplay, onTf: (Timeframe) -> Unit) {
    val s = LocalStrings.current
    val t = AppTheme.trade
    val r = b.result
    val i = r.instrument
    val candles = b.snapshot.candles[tf].orEmpty().takeLast(300)
    val levels = buildList {
        r.resistances.forEachIndexed { k, z -> add(ChartLevel(LevelKind.RESISTANCE, "R${k + 1}", z.lower, z.upper)) }
        r.supports.forEachIndexed { k, z -> add(ChartLevel(LevelKind.SUPPORT, "S${k + 1}", z.lower, z.upper)) }
        r.zones?.interaction?.let { add(ChartLevel(LevelKind.INTERACTION, "Z", it.lower, it.upper)) }
        r.plan?.takeIf { !it.state.isTerminal }?.let { p ->
            p.entryBand?.let { add(ChartLevel(LevelKind.ENTRY, "ENTRY", it.lower, it.upper)) }
            p.stop?.let { add(ChartLevel(LevelKind.STOP, "SL ${Fmt.price(it, i)}", it)) }
            p.targets.forEachIndexed { k, tg -> add(ChartLevel(LevelKind.TARGET, "TP${k + 1} ${Fmt.price(tg.price, i)}", tg.price)) }
        }
    }
    val description = "${i.symbol} ${tf.code} chart, ${candles.size} candles, ${r.supports.size} support and ${r.resistances.size} resistance zones"
    SectionCard(title = s.chart, icon = Icons.AutoMirrored.Filled.ShowChart) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            val tfs = listOf(Timeframe.H4, Timeframe.H1, Timeframe.M15)
            tfs.forEachIndexed { idx, x ->
                SegmentedButton(
                    selected = x == tf,
                    onClick = { onTf(x) },
                    shape = SegmentedButtonDefaults.itemShape(idx, tfs.size),
                ) { Text(x.code.uppercase()) }
            }
        }
        Spacer(Modifier.height(12.dp))
        CandleChart(candles, levels, Fmt.priceScale(i), r.currentPrice, mode, description)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LegendItem("S", s.supportZones, t.support)
            LegendItem("R", s.resistanceZones, t.resistance)
            LegendItem("ENTRY", s.entryBand, t.entry)
            LegendItem("SL", s.stopLoss, t.stop)
            LegendItem("TP", s.target1 + "/2", t.target)
        }
    }
}

@Composable
private fun LegendItem(tag: String, label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(color.copy(alpha = 0.35f)).border(1.dp, color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(4.dp))
        Text("$tag · $label", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ZonesCard(r: AnalysisResult, mode: TimeDisplay) {
    val s = LocalStrings.current
    val t = AppTheme.trade
    SectionCard(title = s.supportZones + " / " + s.resistanceZones, icon = Icons.Filled.Layers) {
        r.zones?.interaction?.let {
            ZoneHeader(s.interactionZone, t.wait)
            ZoneRow("Z", it, r, mode, t.wait)
        }
        ZoneHeader(s.resistanceZones, t.resistance)
        Text(s.zoneResistanceExplain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.resistances.isEmpty()) Text("—", modifier = Modifier.padding(vertical = 6.dp))
        r.resistances.forEachIndexed { k, z -> ZoneRow("R${k + 1}", z, r, mode, t.resistance) }
        Spacer(Modifier.height(8.dp))
        ZoneHeader(s.supportZones, t.support)
        Text(s.zoneSupportExplain, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (r.supports.isEmpty()) Text("—", modifier = Modifier.padding(vertical = 6.dp))
        r.supports.forEachIndexed { k, z -> ZoneRow("S${k + 1}", z, r, mode, t.support) }
        if (r.zones?.limitedStructure == true) {
            Spacer(Modifier.height(8.dp))
            Bullet(s.limitedStructure, marker = t.wait)
        }
        Spacer(Modifier.height(8.dp))
        Text(s.strengthNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun ZoneHeader(text: String, color: Color) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = color, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
}

@Composable
private fun ZoneRow(tag: String, z: Zone, r: AnalysisResult, mode: TimeDisplay, color: Color) {
    val s = LocalStrings.current
    val strength = when {
        z.visitCount >= 3 -> s.strengthStrong
        z.visitCount == 2 -> s.strengthModerate
        else -> s.strengthLight
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) { Text(tag, style = MaterialTheme.typography.labelMedium, color = color, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${Fmt.price(z.lower, r.instrument)} – ${Fmt.price(z.upper, r.instrument)}", style = MaterialTheme.typography.bodyLarge.merge(Numeric), fontWeight = FontWeight.Medium)
            Text(
                "$strength · ${z.visitCount} ${s.visits} · ${s.lastVisit} ${Fmt.shortTime(z.lastVisit, mode)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (z.breakStatus) {
                BreakStatus.CANDIDATE_BREAK_UP -> Text(s.candidateBreakUp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                BreakStatus.CANDIDATE_BREAK_DOWN -> Text(s.candidateBreakDown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                BreakStatus.NONE -> Unit
            }
        }
    }
}

@Composable
internal fun ContextCard(r: AnalysisResult, mode: TimeDisplay) {
    val s = LocalStrings.current
    val i = r.instrument
    SectionCard(title = s.context, icon = Icons.Filled.Public) {
        BiasRow(s.direction4h, r.bias)
        if (i.base != "BTC") BiasRow(s.btcContext, r.btcContext)
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        val f = r.funding
        LabeledValue(
            s.funding,
            f?.let { Fmt.fundingRate(it.rate) } ?: s.unavailable,
            caption = f?.let {
                listOfNotNull(
                    it.intervalHours?.let { h -> "${s.every} ${h}h" },
                    it.nextFundingTime?.let { n -> "${s.nextFunding} ${Fmt.shortTime(n, mode)}" },
                ).joinToString(" · ").ifBlank { null }
            },
        )
        val oi = r.openInterest
        LabeledValue(s.openInterest, oi?.let { Fmt.compact(it.value) + " " + i.base } ?: s.unavailable, caption = oi?.sourceTime?.let { Fmt.shortTime(it, mode) })
        val q = r.quote
        val spread = q?.let { if (it.mid.signum() > 0) (it.ask - it.bid).multiply(BigDecimal(100)).divide(it.mid, java.math.MathContext.DECIMAL64) else null }
        LabeledValue(s.spread, Fmt.percent(spread))
    }
}

@Composable
private fun BiasRow(label: String, b: BiasResult?) {
    val s = LocalStrings.current
    val t = AppTheme.trade
    val (text, color) = when (b?.bias) {
        Bias.LONG -> s.direction(true) to t.long
        Bias.SHORT -> s.direction(false) to t.short
        Bias.NEUTRAL -> s.status(OverallStatus.WAIT) + " · " + s.reason(ReasonCode.MIXED_STRUCTURE).substringBefore('.') to t.wait
        else -> s.unavailable to t.noTrade
    }
    LabeledValue(label, text, valueColor = color)
}

@Composable
internal fun LimitationsCard(r: AnalysisResult) {
    val s = LocalStrings.current
    val t = AppTheme.trade
    SectionCard(title = s.limitations, icon = Icons.Filled.WarningAmber) {
        r.limitations.forEach { Bullet(s.reason(it), marker = t.wait) }
        r.dataIssues.forEach { Bullet(s.reason(it.code), marker = t.unavailable) }
    }
}

@Composable
internal fun TechnicalCard(r: AnalysisResult, mode: TimeDisplay) {
    val s = LocalStrings.current
    var open by rememberSaveable { mutableStateOf(false) }
    val i = r.instrument
    SectionCard(title = s.technical, icon = Icons.Filled.Code, trailing = {
        TextButton(onClick = { open = !open }) {
            Text(if (open) s.hideTechnical else s.showTechnical)
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
        }
    }) {
        AnimatedVisibility(open) {
            Column {
                Mono(s.strategyVersion, r.strategyVersion)
                Mono(s.analysisId, r.analysisId)
                r.indicators?.let { ind ->
                    Mono("4H EMA20 / 50 / 200", listOf(ind.ema20h4, ind.ema50h4, ind.ema200h4).joinToString(" / ") { Fmt.price(it, i) })
                    Mono("1H ATR14", Fmt.price(ind.atr1h, i))
                    Mono("15M ATR14", Fmt.price(ind.atr15, i))
                    Mono("15M RSI14", Fmt.ratio(ind.rsi15))
                    Mono("1H vol mean(20)", Fmt.compact(ind.volumeMean1h))
                }
                r.bias?.let { b ->
                    Mono(s.swingHighs + " (4H)", b.swingHighs.joinToString(" → ") { Fmt.price(it.price, i) }.ifBlank { "—" })
                    Mono(s.swingLows + " (4H)", b.swingLows.joinToString(" → ") { Fmt.price(it.price, i) }.ifBlank { "—" })
                }
                r.plan?.let { p ->
                    Spacer(Modifier.height(8.dp))
                    Text(s.breakoutEvidence, style = MaterialTheme.typography.labelLarge)
                    val edge = if (p.direction == Direction.LONG) p.zone.upper + p.breakBuffer else p.zone.lower - p.breakBuffer
                    Mono("Zone (frozen)", "${Fmt.price(p.zone.lower, i)} – ${Fmt.price(p.zone.upper, i)} · ${p.zone.visitCount} ${s.visits}")
                    Mono(s.closeVsEdge, "${Fmt.price(p.triggerClose, i)} vs ${Fmt.price(edge, i)}")
                    Mono(s.volumeVsMean, "${Fmt.compact(p.triggerVolume)} / ${Fmt.compact(p.volumeMean)} = ${Fmt.ratio(p.triggerVolume.divide(p.volumeMean, java.math.MathContext.DECIMAL64))}×")
                    Mono("1H ATR (frozen)", Fmt.price(p.atr1h, i))
                    Mono("Breakout candle", Fmt.time(p.triggerOpenTime, mode))
                    p.retestOpenTime?.let {
                        Text(s.retestEvidence, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        Mono("Candle", Fmt.time(it, mode))
                        Mono("High / Low", "${Fmt.price(p.retestHigh, i)} / ${Fmt.price(p.retestLow, i)}")
                        Mono("15M ATR (frozen)", Fmt.price(p.atr15, i))
                    }
                    p.confirmationOpenTime?.let {
                        Text(s.confirmationEvidence, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                        Mono("Candle", Fmt.time(it, mode))
                        Mono("Close / RSI", "${Fmt.price(p.confirmationClose, i)} / ${Fmt.ratio(p.confirmationRsi)}")
                    }
                    Mono("Plan key", p.planKey)
                }
                Spacer(Modifier.height(6.dp))
                Mono(s.dataIssues, r.dataIssues.joinToString { it.code.name + (if (it.timeframe != null) "(${it.timeframe!!.code})" else "") }.ifBlank { s.none })
            }
        }
    }
}

@Composable
private fun Mono(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall.merge(com.coinfutures.app.ui.theme.MonoNumeric))
    }
}
