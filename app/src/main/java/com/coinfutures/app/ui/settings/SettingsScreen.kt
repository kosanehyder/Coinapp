package com.coinfutures.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coinfutures.app.AppContainer
import com.coinfutures.app.BuildConfig
import com.coinfutures.app.CoinFuturesApp
import com.coinfutures.app.data.market.Venues
import com.coinfutures.app.data.settings.AppLanguage
import com.coinfutures.app.data.settings.AppSettings
import com.coinfutures.app.data.settings.ThemeMode
import com.coinfutures.app.data.settings.TimeDisplay
import com.coinfutures.app.ui.components.SectionCard
import com.coinfutures.app.ui.format.Fmt
import com.coinfutures.app.ui.l10n.LocalStrings
import com.coinfutures.app.ui.navigation.LocalContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun language(v: AppLanguage) = viewModelScope.launch { c.settings.setLanguage(v) }
    fun theme(v: ThemeMode) = viewModelScope.launch { c.settings.setTheme(v) }
    fun time(v: TimeDisplay) = viewModelScope.launch { c.settings.setTimeDisplay(v) }
    fun venue(v: String) = viewModelScope.launch { c.settings.setVenue(v) }
    fun notifications(on: Boolean) = viewModelScope.launch {
        c.settings.setNotifications(on)
        (c.app as? CoinFuturesApp)?.syncBackgroundWork()
    }
    fun risk(r: String, l: String, f: String, sl: String) = viewModelScope.launch { c.settings.setRiskDefaults(r, l, f, sl) }
    fun wipe(done: () -> Unit) = viewModelScope.launch {
        c.activity.wipeAll()
        c.settings.clearAll()
        (c.app as? CoinFuturesApp)?.syncBackgroundWork()
        done()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val container = LocalContainer.current
    val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
    val st by vm.settings.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirmWipe by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.notifications(granted) }
    val cur = st ?: AppSettings()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text(s.settingsTitle) }) },
        snackbarHost = { SnackbarHost(snack) },
    ) { inner ->
        Column(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard(s.language, icon = Icons.Filled.Language) {
                Choice(listOf(AppLanguage.ENGLISH to "English", AppLanguage.ROMAN_URDU to "Roman Urdu"), cur.language) { vm.language(it) }
            }
            SectionCard(s.theme, icon = Icons.Filled.Palette) {
                Choice(listOf(ThemeMode.SYSTEM to s.themeSystem, ThemeMode.LIGHT to s.themeLight, ThemeMode.DARK to s.themeDark), cur.theme) { vm.theme(it) }
            }
            SectionCard(s.notifications, icon = Icons.Filled.Notifications) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.notificationsDesc, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = cur.notificationsEnabled, onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.notifications(on)
                    })
                }
                Spacer(Modifier.height(6.dp))
                Text(s.backgroundNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard(s.exchange, icon = Icons.Filled.Storefront) {
                Choice(listOf(Venues.BINANCE_USDM to container.binance.venueName, Venues.DEMO to container.demo.venueName), cur.venue) { vm.venue(it) }
            }
            SectionCard(s.strategyProfile, icon = Icons.Filled.Tune) {
                Choice(listOf("intraday-v1.0.0" to "Intraday v1.0.0 (4H / 1H / 15M)"), cur.strategyProfile) {}
                Text(s.swingLater, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            RiskDefaultsCard(cur) { r, l, f, sl -> vm.risk(r, l, f, sl); scope.launch { snack.showSnackbar(s.save + " ✓") } }
            SectionCard(s.timezone, icon = Icons.Filled.Schedule) {
                Choice(listOf(TimeDisplay.LOCAL_AND_UTC to s.timeLocalUtc, TimeDisplay.UTC_ONLY to s.timeUtc), cur.timeDisplay) { vm.time(it) }
                Spacer(Modifier.height(6.dp))
                Text("${s.deviceZone}: ${Fmt.zoneLabel()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SectionCard(s.privacy, icon = Icons.Filled.Shield) {
                Text(s.privacyBody, style = MaterialTheme.typography.bodyMedium, maxLines = if (showPrivacy) Int.MAX_VALUE else 3)
                TextButton(onClick = { showPrivacy = !showPrivacy }) { Text(if (showPrivacy) s.close else s.privacy + " …") }
                Button(
                    onClick = { confirmWipe = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(s.deleteData) }
            }
            SectionCard(s.about, icon = Icons.Filled.Info) {
                Text(s.aboutBody, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text("${s.version} ${BuildConfig.VERSION_NAME} · ${s.strategyVersion} ${BuildConfig.STRATEGY_VERSION}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            icon = { androidx.compose.material3.Icon(Icons.Filled.DeleteForever, null) },
            title = { Text(s.deleteData) },
            text = { Text(s.deleteDataBody) },
            confirmButton = {
                Button(onClick = {
                    confirmWipe = false
                    vm.wipe { scope.launch { snack.showSnackbar(s.dataDeleted) } }
                }) { Text(s.delete) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text(s.cancel) } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun <T> Choice(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) ->
            FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label) })
        }
    }
}

@Composable
private fun RiskDefaultsCard(cur: AppSettings, onSave: (String, String, String, String) -> Unit) {
    val s = LocalStrings.current
    var r by remember(cur.risk) { mutableStateOf(cur.risk.riskPercent) }
    var l by remember(cur.risk) { mutableStateOf(cur.risk.leverage) }
    var f by remember(cur.risk) { mutableStateOf(cur.risk.feePercent) }
    var sl by remember(cur.risk) { mutableStateOf(cur.risk.slippagePercent) }
    LaunchedEffect(cur.risk) { }
    SectionCard(s.riskDefaults, icon = Icons.Filled.Tune) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(s.riskPercent, r, Modifier.weight(1f)) { r = it }
            Field(s.leverage, l, Modifier.weight(1f)) { l = it }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(s.feePercent, f, Modifier.weight(1f)) { f = it }
            Field(s.slippagePercent, sl, Modifier.weight(1f)) { sl = it }
        }
        Spacer(Modifier.height(8.dp))
        val valid = listOf(r, l, f, sl).all { it.toBigDecimalOrNull()?.let { v -> v.signum() >= 0 } == true }
        Button(onClick = { onSave(r, l, f, sl) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text(s.save) }
    }
}

@Composable
private fun Field(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(12)) },
        label = { Text(label, maxLines = 1) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    )
}
