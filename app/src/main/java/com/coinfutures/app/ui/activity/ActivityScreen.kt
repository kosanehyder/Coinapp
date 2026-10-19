package com.coinfutures.app.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coinfutures.app.AppContainer
import com.coinfutures.app.CoinFuturesApp
import com.coinfutures.app.data.db.AlertEntity
import com.coinfutures.app.data.db.AlertEventEntity
import com.coinfutures.app.data.db.PaperTradeEntity
import com.coinfutures.app.data.db.PlanRevisionEntity
import com.coinfutures.app.data.settings.TimeDisplay
import com.coinfutures.app.domain.Delivery
import com.coinfutures.app.ui.components.EmptyState
import com.coinfutures.app.ui.components.Pill
import com.coinfutures.app.ui.format.Fmt
import com.coinfutures.app.ui.l10n.LocalStrings
import com.coinfutures.app.ui.navigation.LocalContainer
import com.coinfutures.app.ui.theme.AppTheme
import com.coinfutures.app.ui.theme.Numeric
import com.coinfutures.engine.AlertEventType
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ActivityViewModel(private val c: AppContainer) : ViewModel() {
    val alerts = c.activity.alerts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events = c.activity.events().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val plans = c.activity.plans().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val paper = c.activity.paperTrades().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val timeMode = c.settings.settings.map { it.timeDisplay }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimeDisplay.LOCAL_AND_UTC)

    fun setEnabled(a: AlertEntity, on: Boolean) = viewModelScope.launch {
        c.activity.setAlertEnabled(a.alertId, on)
        (c.app as? CoinFuturesApp)?.syncBackgroundWork()
    }

    fun delete(a: AlertEntity) = viewModelScope.launch {
        c.activity.deleteAlert(a.alertId)
        (c.app as? CoinFuturesApp)?.syncBackgroundWork()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(onOpenMarket: (String, String?) -> Unit, onOpenPaper: (Long) -> Unit) {
    val container = LocalContainer.current
    val vm: ActivityViewModel = viewModel { ActivityViewModel(container) }
    val s = LocalStrings.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val paper by vm.paper.collectAsStateWithLifecycle()
    val mode by vm.timeMode.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(s.activityTitle) }) },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            val tabs = listOf(s.tabAlerts, s.tabEvents, s.tabPlans, s.tabPaper)
            PrimaryTabRow(selectedTabIndex = tab) {
                tabs.forEachIndexed { i, title -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(title, maxLines = 1) }) }
            }
            when (tab) {
                0 -> if (alerts.isEmpty()) EmptyState(Icons.Filled.NotificationsNone, s.noAlerts, s.noAlertsBody) else
                    ListBody(alerts, { it.alertId }) { a -> AlertRow(a, { on -> vm.setEnabled(a, on) }, { vm.delete(a) }) { onOpenMarket(a.instrumentId, null) } }
                1 -> if (events.isEmpty()) EmptyState(Icons.AutoMirrored.Filled.EventNote, s.noEvents, s.noEventsBody) else
                    ListBody(events, { it.eventId }) { e -> EventRow(e, mode) { if (e.planKey?.startsWith("paper:") == true) onOpenPaper(e.planKey.removePrefix("paper:").toLongOrNull() ?: 0) else onOpenMarket(e.instrumentId, e.planKey) } }
                2 -> if (plans.isEmpty()) EmptyState(Icons.Filled.Timeline, s.noPlans, s.noPlansBody) else
                    ListBody(plans, { it.id }) { p -> PlanRow(p, mode) { onOpenMarket(p.instrumentId, null) } }
                else -> if (paper.isEmpty()) EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, s.noPaper, s.noPaperBody) else
                    ListBody(paper, { it.tradeId }) { t -> PaperRow(t, mode) { onOpenPaper(t.tradeId) } }
            }
        }
    }
}

@Composable
private fun <T> ListBody(items: List<T>, key: (T) -> Any, row: @Composable (T) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(items, key = key) { row(it) }
    }
}

@Composable
private fun RowCard(onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun AlertRow(a: AlertEntity, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, onClick: () -> Unit) {
    val s = LocalStrings.current
    RowCard(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a.symbol, style = MaterialTheme.typography.titleMedium)
                Text(a.venueName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = a.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = s.delete) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            a.eventTypes.split(',').mapNotNull { n -> AlertEventType.entries.firstOrNull { it.name == n } }.joinToString(" · ") { s.eventType(it) },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EventRow(e: AlertEventEntity, mode: TimeDisplay, onClick: () -> Unit) {
    val s = LocalStrings.current
    val t = AppTheme.trade
    val type = AlertEventType.entries.firstOrNull { it.name == e.eventType }
    val (label, color) = when (e.deliveryStatus) {
        Delivery.SENT -> s.sentToSystem to t.long
        Delivery.OPENED -> s.opened to t.entry
        Delivery.NOT_SENT_STALE -> s.notSentStale to t.wait
        Delivery.NOT_SENT_DISABLED -> s.notSentDisabled to t.noTrade
        Delivery.NOT_SENT_PERMISSION -> s.notSentPermission to t.short
        else -> s.recorded to t.noTrade
    }
    RowCard(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(e.symbol, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(Fmt.shortTime(e.triggeredAt, mode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            listOfNotNull(e.direction?.let { s.direction(it == "LONG") }, type?.let { s.eventType(it) }, e.triggerPrice?.let { Fmt.group(it) }).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(label, color = color)
            e.planRevision?.let { Pill("${s.planRevision} $it") }
        }
    }
}

@Composable
private fun PlanRow(p: PlanRevisionEntity, mode: TimeDisplay, onClick: () -> Unit) {
    val s = LocalStrings.current
    val t = AppTheme.trade
    RowCard(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${p.symbol} · ${s.direction(p.direction == "LONG")}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Pill("rev ${p.revision}")
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(s.planState(p.state), color = if (p.actionable) t.long else MaterialTheme.colorScheme.onSurfaceVariant)
            p.reasonCode?.let { rc -> runCatching { com.coinfutures.engine.ReasonCode.valueOf(rc) }.getOrNull()?.let { Text(s.reason(it), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            listOfNotNull(
                "Zone ${Fmt.group(p.zoneLower)}–${Fmt.group(p.zoneUpper)}",
                p.entryLower?.let { "Entry ${Fmt.group(it)}–${Fmt.group(p.entryUpper ?: it)}" },
                p.stop?.let { "SL ${Fmt.group(it)}" },
                p.tp1?.let { "TP1 ${Fmt.group(it)}" },
                p.tp2?.let { "TP2 ${Fmt.group(it)}" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall.merge(Numeric), color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("${p.strategyVersion} · ${Fmt.shortTime(p.stateChangedAt, mode)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun PaperRow(tr: PaperTradeEntity, mode: TimeDisplay, onClick: () -> Unit) {
    val s = LocalStrings.current
    val t = AppTheme.trade
    RowCard(onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${tr.symbol} · ${s.direction(tr.direction == "LONG")}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Pill(if (tr.status == "OPEN") s.statusOpen else s.statusClosed, color = if (tr.status == "OPEN") t.long else t.noTrade)
        }
        Spacer(Modifier.height(4.dp))
        Text("${s.entryPrice} ${Fmt.group(tr.entryPrice)} · ${s.quantity} ${tr.quantity} · ${s.remaining} ${tr.remainingQuantity}", style = MaterialTheme.typography.bodySmall.merge(Numeric))
        Text("${s.enteredAt} ${Fmt.shortTime(tr.enteredByUserAt, mode)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.width(1.dp))
    }
}
