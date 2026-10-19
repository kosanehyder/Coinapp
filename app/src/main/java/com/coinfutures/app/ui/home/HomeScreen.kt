package com.coinfutures.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coinfutures.app.AppContainer
import com.coinfutures.app.data.db.MarketEntity
import com.coinfutures.app.data.market.MarketException
import com.coinfutures.app.data.market.Venues
import com.coinfutures.app.data.settings.AppSettings
import com.coinfutures.app.ui.components.EmptyState
import com.coinfutures.app.ui.components.Pill
import com.coinfutures.app.ui.components.StatusBadge
import com.coinfutures.app.ui.format.Fmt
import com.coinfutures.app.ui.l10n.LocalStrings
import com.coinfutures.app.ui.navigation.LocalContainer
import com.coinfutures.app.ui.theme.AppTheme
import com.coinfutures.app.ui.theme.Numeric
import com.coinfutures.engine.ErrorCode
import com.coinfutures.engine.OverallStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface Connection {
    data object Checking : Connection
    data class Online(val latencyMs: Long, val at: Long) : Connection
    data class Failed(val code: ErrorCode, val at: Long) : Connection
    data object Simulated : Connection
}

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val saved = c.markets.saved().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recent = c.markets.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _connection = MutableStateFlow<Connection>(Connection.Checking)
    val connection = _connection.asStateFlow()
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()
    val now = MutableStateFlow(System.currentTimeMillis())

    init {
        viewModelScope.launch {
            c.settings.settings.map { it.venue }.distinctUntilChanged().collect { probe(it) }
        }
        viewModelScope.launch {
            while (true) { delay(30_000); now.value = System.currentTimeMillis() }
        }
    }

    private suspend fun probe(venue: String) {
        if (venue == Venues.DEMO) { _connection.value = Connection.Simulated; return }
        _connection.value = Connection.Checking
        _connection.value = try {
            Connection.Online(c.markets.ping(venue), System.currentTimeMillis())
        } catch (e: MarketException) {
            Connection.Failed(e.code, System.currentTimeMillis())
        }
    }

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        settings.value?.venue?.let { probe(it) }
        for (m in saved.value) {
            runCatching { c.markets.analyze(c.markets.instrument(m.instrumentId)) }
        }
        now.value = System.currentTimeMillis()
        _refreshing.value = false
    }

    fun useDemo() = viewModelScope.launch { c.settings.setVenue(Venues.DEMO) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(onSearch: (String?) -> Unit, onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val vm: HomeViewModel = viewModel { HomeViewModel(container) }
    val s = LocalStrings.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val demo = settings?.venue == Venues.DEMO

    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) { inner ->
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.padding(inner).fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 20.dp, vertical = 16.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.homeTitle, style = MaterialTheme.typography.headlineMedium)
                                Text(s.homeSubtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, contentDescription = s.refreshAll) }
                        }
                        Spacer(Modifier.height(12.dp))
                        ConnectionPill(connection, settings?.let { if (demo) container.demo.venueName else container.binance.venueName } ?: "")
                        Spacer(Modifier.height(16.dp))
                        SearchEntry(s.searchHint) { onSearch(null) }
                    }
                }

                if (saved.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.StarBorder,
                            title = s.emptyWatchlistTitle,
                            body = s.emptyWatchlistBody,
                            action = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(s.trySuggestions, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.height(8.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val sug = if (demo) listOf("TEST", "SHORTY", "CONFIRM", "RANGE", "DUP") else listOf("BTC", "Ethereum", "SOL", "XRP")
                                        sug.forEach { q -> AssistChip(onClick = { onSearch(q) }, label = { Text(q) }) }
                                    }
                                }
                            },
                        )
                    }
                } else {
                    item { SectionHeader(s.watchlist) }
                    items(saved, key = { it.instrumentId }) { m ->
                        MarketRow(m, now) { onOpen(m.instrumentId) }
                    }
                }

                if (recent.isNotEmpty()) {
                    item { SectionHeader(s.recentlyViewed) }
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recent, key = { it.instrumentId }) { m -> RecentCard(m) { onOpen(m.instrumentId) } }
                        }
                    }
                }

                if (!demo) {
                    item {
                        Card(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        ) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Science, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(Modifier.width(12.dp))
                                Text(s.tryDemo, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { vm.useDemo(); onSearch("TEST") }) { Text(s.trySuggestions) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun SearchEntry(hint: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = hint },
    ) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ConnectionPill(c: Connection, venue: String) {
    val s = LocalStrings.current
    val t = AppTheme.trade
    val (text, color) = when (c) {
        Connection.Checking -> "$venue · ${s.checking}" to MaterialTheme.colorScheme.onSurfaceVariant
        is Connection.Online -> "$venue · ${s.connected} · ${c.latencyMs} ms ${s.latency}" to t.long
        is Connection.Failed -> "$venue · " + (if (c.code == ErrorCode.REGION_BLOCKED) s.regionBlocked else s.offline) to t.short
        Connection.Simulated -> "$venue · ${s.simulatedVenue}" to t.unavailable
    }
    Pill(text = text, color = color, icon = Icons.Filled.Circle)
}

@Composable
private fun MarketRow(m: MarketEntity, now: Long, onClick: () -> Unit) {
    val s = LocalStrings.current
    Card(
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    listOfNotNull(m.displayName, m.venueName).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text("${s.lastAnalysis}: ${Fmt.ago(s, m.lastAnalysisAt, now)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(m.lastPrice?.let { Fmt.group(it) } ?: "—", style = MaterialTheme.typography.titleMedium.merge(Numeric))
                Spacer(Modifier.height(6.dp))
                m.lastStatus?.let { st -> runCatching { OverallStatus.valueOf(st) }.getOrNull() }?.let { StatusBadge(it) }
            }
        }
    }
}

@Composable
private fun RecentCard(m: MarketEntity, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.widthIn(min = 132.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Text(m.symbol, style = MaterialTheme.typography.titleSmall)
            Text(m.venueName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            m.lastStatus?.let { st -> runCatching { OverallStatus.valueOf(st) }.getOrNull() }?.let { StatusBadge(it) }
        }
    }
}
