package com.coinfutures.app.ui.paper

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coinfutures.app.AppContainer
import com.coinfutures.app.data.db.PaperTradeEntity
import com.coinfutures.app.data.settings.TimeDisplay
import com.coinfutures.app.ui.components.Bullet
import com.coinfutures.app.ui.components.EmptyState
import com.coinfutures.app.ui.components.LabeledValue
import com.coinfutures.app.ui.components.Pill
import com.coinfutures.app.ui.components.SectionCard
import com.coinfutures.app.ui.format.Fmt
import com.coinfutures.app.ui.l10n.LocalStrings
import com.coinfutures.app.ui.navigation.LocalContainer
import com.coinfutures.app.ui.theme.AppTheme
import com.coinfutures.engine.PaperTrackResult
import com.coinfutures.engine.TouchType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.math.BigDecimal

class PaperViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val trade = c.activity.paperTrade(id).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val mode = c.settings.settings.map { it.timeDisplay }.stateIn(viewModelScope, SharingStarted.Eagerly, TimeDisplay.LOCAL_AND_UTC)
    private val _track = MutableStateFlow<PaperTrackResult?>(null)
    val track = _track.asStateFlow()

    fun refreshTracking(t: PaperTradeEntity) = viewModelScope.launch {
        _track.value = runCatching { c.activity.trackPaper(t, dispatch = false) }.getOrNull()
    }

    fun exits(t: PaperTradeEntity) = c.activity.exits(t)
    fun recordExit(price: BigDecimal, qty: BigDecimal, note: String) = viewModelScope.launch { c.activity.recordExit(id, price, qty, note) }
    fun close() = viewModelScope.launch { c.activity.closeTrade(id) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { c.activity.deleteTrade(id); onDone() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperTradeScreen(tradeId: Long, onBack: () -> Unit, onOpenMarket: (String) -> Unit) {
    val container = LocalContainer.current
    val vm: PaperViewModel = viewModel(key = "paper-$tradeId") { PaperViewModel(container, tradeId) }
    val trade by vm.trade.collectAsStateWithLifecycle()
    val track by vm.track.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val t = AppTheme.trade
    var exitDialog by remember { mutableStateOf(false) }

    LaunchedEffect(trade?.tradeId, trade?.updatedAt) { trade?.let { vm.refreshTracking(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.paperTitle + (trade?.let { " · ${it.symbol}" } ?: "")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.close) } },
                actions = { IconButton(onClick = { vm.delete(onBack) }) { Icon(Icons.Filled.Delete, contentDescription = s.delete) } },
            )
        },
    ) { inner ->
        val tr = trade
        if (tr == null) {
            EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, s.noPaper, s.noPaperBody, Modifier.padding(inner))
            return@Scaffold
        }
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(s.paperIntro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SectionCard(title = "${tr.symbol} · ${s.direction(tr.direction == "LONG")}", icon = Icons.Filled.Flag, trailing = {
                Pill(if (tr.status == "OPEN") s.statusOpen else s.statusClosed, color = if (tr.status == "OPEN") t.long else t.noTrade)
            }) {
                LabeledValue(s.enteredAt, Fmt.time(tr.enteredByUserAt, mode))
                LabeledValue(s.entryPrice, Fmt.group(tr.entryPrice))
                LabeledValue(s.quantity, tr.quantity)
                LabeledValue(s.remaining, tr.remainingQuantity)
                LabeledValue(s.stopLoss, Fmt.group(tr.stop), valueColor = t.stop)
                tr.tp1?.let { LabeledValue(s.target1, Fmt.group(it), valueColor = t.target) }
                tr.tp2?.let { LabeledValue(s.target2, Fmt.group(it), valueColor = t.target) }
                LabeledValue(s.planRevision, tr.planRevision.toString(), caption = tr.planKey)
                if (tr.notes.isNotBlank()) Text(tr.notes, style = MaterialTheme.typography.bodyMedium)
            }
            SectionCard(title = s.touches, icon = Icons.Filled.Visibility) {
                val tk = track
                if (tk == null || tk.events.isEmpty()) Text(s.none, color = MaterialTheme.colorScheme.onSurfaceVariant)
                tk?.events?.forEach { e ->
                    val label = when (e.type) {
                        TouchType.TARGET_TOUCHED -> (if (e.targetIndex == 0) s.target1 else s.target2) + " · " + s.eventType(com.coinfutures.engine.AlertEventType.TARGET_TOUCHED)
                        TouchType.STOP_TOUCHED -> s.eventType(com.coinfutures.engine.AlertEventType.STOP_TOUCHED)
                        TouchType.AMBIGUOUS -> s.ambiguousNote
                        TouchType.REVIEW_TIME, TouchType.REVIEW_DIRECTION, TouchType.THESIS_INVALIDATED -> s.eventType(com.coinfutures.engine.AlertEventType.REVIEW_NEEDED) + " · " + e.type.name.replace('_', ' ').lowercase()
                    }
                    Bullet("${Fmt.shortTime(e.time, mode)} — $label" + (e.price?.let { " @ ${Fmt.group(it.toPlainString())}" } ?: ""))
                }
                Spacer(Modifier.height(6.dp))
                Text(s.touchNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            SectionCard(title = s.recordedExits, icon = Icons.AutoMirrored.Filled.ReceiptLong) {
                val list = vm.exits(tr)
                if (list.isEmpty()) Text(s.none, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.forEach { x -> LabeledValue("${Fmt.shortTime(x.at, mode)} · ${x.note}", "${x.quantity} @ ${Fmt.group(x.price)}") }
            }
            if (tr.status == "OPEN") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { exitDialog = true }, modifier = Modifier.weight(1f).height(52.dp)) { Text(s.recordExit) }
                    OutlinedButton(onClick = { vm.close() }, modifier = Modifier.weight(1f).height(52.dp)) { Text(s.closeTrade) }
                }
            }
            TextButton(onClick = { onOpenMarket(tr.instrumentId) }, modifier = Modifier.fillMaxWidth()) { Text(s.analysis + " · " + tr.symbol) }
        }
        if (exitDialog) ExitDialog(tr, onDismiss = { exitDialog = false }) { p, q, n -> vm.recordExit(p, q, n); exitDialog = false }
    }
}

@Composable
private fun ExitDialog(tr: PaperTradeEntity, onDismiss: () -> Unit, onSave: (BigDecimal, BigDecimal, String) -> Unit) {
    val s = LocalStrings.current
    var price by remember { mutableStateOf(tr.tp1 ?: "") }
    var qty by remember { mutableStateOf(tr.remainingQuantity) }
    var note by remember { mutableStateOf("") }
    val p = price.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val remaining = BigDecimal(tr.remainingQuantity)
    val q = qty.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it <= remaining }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.recordExit) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(price, { price = it }, label = { Text(s.exitPrice) }, singleLine = true, isError = p == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(qty, { qty = it }, label = { Text(s.exitQty) }, singleLine = true, isError = q == null,
                    supportingText = { Text("${s.remaining}: ${tr.remainingQuantity}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(note, { note = it.take(120) }, label = { Text(s.notes) }, singleLine = true)
            }
        },
        confirmButton = { Button(onClick = { onSave(p!!, q!!, note) }, enabled = p != null && q != null) { Text(s.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}
