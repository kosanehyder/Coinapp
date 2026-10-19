package com.coinfutures.app.ui.analysis

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coinfutures.app.data.settings.TimeDisplay
import com.coinfutures.app.ui.components.ErrorState
import com.coinfutures.app.ui.components.LoadingColumn
import com.coinfutures.app.ui.components.SecondaryAction
import com.coinfutures.app.ui.format.Fmt
import com.coinfutures.app.ui.l10n.LocalStrings
import com.coinfutures.app.ui.navigation.LocalContainer
import com.coinfutures.engine.AlertEventType
import com.coinfutures.engine.Direction
import com.coinfutures.engine.PlanState
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalysisScreen(
    instrumentId: String,
    fromPlanKey: String?,
    fromEventId: Long?,
    onBack: () -> Unit,
    onRisk: () -> Unit,
    onPaperTrade: (Long) -> Unit,
) {
    val container = LocalContainer.current
    val vm: AnalysisViewModel = viewModel(key = "analysis-$instrumentId") { AnalysisViewModel(container, instrumentId, fromPlanKey, fromEventId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val alert by vm.alert.collectAsStateWithLifecycle()
    val market by vm.market.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val timeMode = settings?.timeDisplay ?: TimeDisplay.LOCAL_AND_UTC
    var showAlertSheet by rememberSaveable { mutableStateOf(false) }
    var showPaper by rememberSaveable { mutableStateOf(false) }

    LifecycleStartEffect(Unit) {
        vm.start()
        onStopOrDispose { vm.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(ui.instrument?.symbol ?: instrumentId.substringAfter(':'), style = MaterialTheme.typography.titleLarge)
                        Text(ui.instrument?.venueName ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.close) } },
                actions = {
                    val saved = market?.saved == true
                    IconButton(onClick = { vm.toggleSaved() }, enabled = ui.instrument != null) {
                        Icon(
                            if (saved) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = if (saved) s.removeFromWatchlist else s.saveToWatchlist,
                            tint = if (saved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showAlertSheet = true }, enabled = ui.instrument != null) {
                        Icon(
                            if (alert?.enabled == true) Icons.Filled.NotificationsActive else Icons.Filled.Notifications,
                            contentDescription = s.actionAlert,
                        )
                    }
                },
            )
        },
    ) { inner ->
        val b = ui.bundle
        PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(inner).fillMaxSize()) {
            when {
                b == null && ui.loading -> LoadingColumn(s.loading)
                b == null && ui.error != null -> ErrorState(ui.error!!, onRetry = { vm.refresh() })
                b != null -> {
                    val r = b.result
                    val inst = r.instrument
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (ui.superseded) item { Banner(s.supersededBanner) }
                        if (ui.error != null) item { Banner(s.error(ui.error!!)) }
                        item { MarketHeader(r, ui.now, timeMode) }
                        item { StatusHero(r) }
                        r.plan?.let { p ->
                            if (p.entryBand != null && p.stop != null) {
                                item { PlanCard(r, p, b.planRevision, timeMode, ui.now) }
                                item {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        val usable = !p.state.isTerminal
                                        SecondaryAction(s.actionRisk, Icons.Filled.Calculate, onRisk, Modifier.weight(1f), enabled = usable)
                                        SecondaryAction(s.actionPaper, Icons.Filled.EditNote, { showPaper = true }, Modifier.weight(1f), enabled = p.state == PlanState.ENTRY_READY && r.qualityOk)
                                    }
                                }
                            }
                        }
                        item { ChartCard(b, ui.timeframe, timeMode, vm::setTimeframe) }
                        item { ZonesCard(r, timeMode) }
                        item { ContextCard(r, timeMode) }
                        item { LimitationsCard(r) }
                        item { TechnicalCard(r, timeMode) }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                    if (showPaper && r.plan != null) {
                        PaperDialog(
                            defaultEntry = r.plan!!.let { p -> if (r.currentPrice != null && p.entryBand?.contains(r.currentPrice!!) == true) r.currentPrice!! else p.metrics?.worstEntry },
                            scale = Fmt.priceScale(inst),
                            onDismiss = { showPaper = false },
                            onSave = { e, q, n -> showPaper = false; vm.recordPaper(e, q, n, onPaperTrade) },
                        )
                    }
                }
            }
        }
    }

    if (showAlertSheet) {
        AlertSheet(
            initialTypes = alert?.eventTypes?.split(',')?.mapNotNull { n -> AlertEventType.entries.firstOrNull { it.name == n } }?.toSet()
                ?: setOf(AlertEventType.ENTRY_READY, AlertEventType.SETUP_INVALIDATED, AlertEventType.SETUP_EXPIRED),
            initialEnabled = alert?.enabled ?: true,
            notificationsOn = settings?.notificationsEnabled == true,
            onEnableNotifications = { vm.enableNotifications() },
            onDismiss = { showAlertSheet = false },
            onSave = { types, enabled -> vm.saveAlert(types, enabled); showAlertSheet = false },
        )
    }
}

@Composable
private fun Banner(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f))) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlertSheet(
    initialTypes: Set<AlertEventType>,
    initialEnabled: Boolean,
    notificationsOn: Boolean,
    onEnableNotifications: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (Set<AlertEventType>, Boolean) -> Unit,
) {
    val s = LocalStrings.current
    var types by remember { mutableStateOf(initialTypes) }
    var enabled by remember { mutableStateOf(initialEnabled) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onEnableNotifications()
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.alertSheetTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Spacer(Modifier.height(8.dp))
            val plan = listOf(AlertEventType.ZONE_APPROACH, AlertEventType.ENTRY_READY, AlertEventType.SETUP_INVALIDATED, AlertEventType.SETUP_EXPIRED)
            plan.forEach { t ->
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = t in types, onCheckedChange = { on -> types = if (on) types + t else types - t }, enabled = enabled)
                    Text(s.eventType(t), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!notificationsOn) {
                Text(s.enableNotificationsFirst, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                TextButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else onEnableNotifications()
                }) { Text(s.notifications) }
            }
            Text(s.backgroundNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onSave(types, enabled) }, modifier = Modifier.fillMaxWidth().height(52.dp), enabled = types.isNotEmpty() || !enabled) {
                Text(s.save)
            }
        }
    }
}

@Composable
private fun PaperDialog(defaultEntry: BigDecimal?, scale: Int, onDismiss: () -> Unit, onSave: (BigDecimal, BigDecimal, String) -> Unit) {
    val s = LocalStrings.current
    var entry by remember { mutableStateOf(defaultEntry?.setScale(scale, java.math.RoundingMode.HALF_EVEN)?.toPlainString() ?: "") }
    var qty by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val e = entry.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    val q = qty.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(s.paperTitle) },
        text = {
            Column(Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.paperIntro, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(entry, { entry = it }, label = { Text(s.entryPrice) }, singleLine = true, isError = e == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(qty, { qty = it }, label = { Text(s.quantity) }, singleLine = true, isError = qty.isNotEmpty() && q == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(notes, { notes = it.take(500) }, label = { Text(s.notes) })
            }
        },
        confirmButton = { Button(onClick = { onSave(e!!, q!!, notes) }, enabled = e != null && q != null) { Text(s.recordEntry) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(s.cancel) } },
    )
}

internal fun isLong(d: Direction) = d == Direction.LONG
