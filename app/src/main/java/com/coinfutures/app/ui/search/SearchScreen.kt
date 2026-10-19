package com.coinfutures.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coinfutures.app.AppContainer
import com.coinfutures.app.data.market.MarketException
import com.coinfutures.app.ui.components.EmptyState
import com.coinfutures.app.ui.components.ErrorState
import com.coinfutures.app.ui.components.Pill
import com.coinfutures.app.ui.l10n.LocalStrings
import com.coinfutures.app.ui.l10n.Strings
import com.coinfutures.app.ui.navigation.LocalContainer
import com.coinfutures.app.ui.theme.AppTheme
import com.coinfutures.engine.ContractType
import com.coinfutures.engine.ErrorCode
import com.coinfutures.engine.Instrument
import com.coinfutures.engine.SearchCandidate
import com.coinfutures.engine.SearchResult
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SearchUi(
    val query: String = "",
    val loading: Boolean = false,
    val result: SearchResult? = null,
    val error: ErrorCode? = null,
    val venueName: String = "",
)

class SearchViewModel(private val c: AppContainer, initial: String) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUi(query = initial))
    val ui = _ui.asStateFlow()
    private var job: Job? = null

    init { if (initial.isNotBlank()) onQuery(initial) }

    @OptIn(FlowPreview::class)
    fun onQuery(q: String) {
        _ui.value = _ui.value.copy(query = q, error = null)
        job?.cancel()
        if (q.isBlank()) { _ui.value = _ui.value.copy(result = null, loading = false); return }
        job = viewModelScope.launch {
            delay(220) // debounce typing
            _ui.value = _ui.value.copy(loading = true)
            try {
                val venue = c.settings.current().venue
                val r = c.markets.search(q, venue)
                _ui.value = _ui.value.copy(result = r, loading = false, venueName = c.markets.source(venue).venueName)
            } catch (e: MarketException) {
                _ui.value = _ui.value.copy(error = e.code, loading = false)
            }
        }
    }

    fun retry() = onQuery(_ui.value.query)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(initialQuery: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val container = LocalContainer.current
    val vm: SearchViewModel = viewModel { SearchViewModel(container, initialQuery) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.searchTitle) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.close) } },
            )
        },
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            OutlinedTextField(
                value = ui.query,
                onValueChange = vm::onQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
                placeholder = { Text(s.searchHint) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (ui.query.isNotEmpty()) IconButton(onClick = { vm.onQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = s.cancel) }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { ui.result?.uniqueMarket?.let { onOpen(it.instrumentId) } }),
            )
            Spacer(Modifier.height(8.dp))
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) else Spacer(Modifier.height(4.dp))

            val r = ui.result
            when {
                ui.error != null -> ErrorState(ui.error!!, onRetry = vm::retry)
                ui.query.isBlank() -> EmptyState(Icons.Filled.Search, s.searchTitle, s.startTyping)
                r != null && r.noSupportedMarket && r.candidates.isEmpty() ->
                    EmptyState(Icons.Filled.SearchOff, s.noSupportedMarket, s.noSupportedMarketBody)
                r != null -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val supported = r.candidates.count { it.supported }
                    if (r.noSupportedMarket) {
                        item { Notice(Icons.Filled.SearchOff, s.noSupportedMarket + ". " + s.noSupportedMarketBody) }
                    } else if (supported > 1 && r.uniqueMarket == null) {
                        item { Notice(Icons.Filled.Info, s.selectMarketHint) }
                    }
                    items(r.candidates, key = { it.instrument.instrumentId }) { c ->
                        CandidateRow(c, r.uniqueMarket?.instrumentId == c.instrument.instrumentId) { onOpen(c.instrument.instrumentId) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Notice(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun contractLabel(s: Strings, i: Instrument) = when (i.contractType) {
    ContractType.PERPETUAL -> s.perpetual
    ContractType.DELIVERY -> s.delivery
    ContractType.SPOT -> s.spot
    ContractType.OTHER -> s.otherContract
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandidateRow(c: SearchCandidate, unique: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
    val i = c.instrument
    val t = AppTheme.trade
    Card(
        onClick = onClick,
        enabled = c.supported,
        modifier = Modifier.fillMaxWidth().alpha(if (c.supported) 1f else 0.72f).semantics {
            contentDescription = "${i.symbol} ${contractLabel(s, i)} ${i.venueName}" + if (!c.supported) ", ${s.unsupported}" else ""
        },
        colors = CardDefaults.cardColors(containerColor = if (unique) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(i.symbol, style = MaterialTheme.typography.titleMedium)
                Text(listOfNotNull(i.displayName, i.base + " / " + i.quote).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill(i.venueName)
                    Pill(contractLabel(s, i))
                    if (i.linear && i.quote == "USDT") Pill(s.linearUsdt)
                    if (!c.supported) Pill(s.unsupported + ": " + (i.unsupportedReason?.let { s.reason(it) } ?: ""), color = t.short, container = t.shortContainer)
                }
            }
            if (c.supported) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}
