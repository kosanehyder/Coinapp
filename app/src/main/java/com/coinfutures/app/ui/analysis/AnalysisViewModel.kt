package com.coinfutures.app.ui.analysis

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.coinfutures.app.AppContainer
import com.coinfutures.app.CoinFuturesApp
import com.coinfutures.app.data.db.AlertEntity
import com.coinfutures.app.data.db.MarketEntity
import com.coinfutures.app.data.market.MarketException
import com.coinfutures.app.data.repo.AnalysisBundle
import com.coinfutures.app.data.settings.AppSettings
import com.coinfutures.engine.AlertEventType
import com.coinfutures.engine.ErrorCode
import com.coinfutures.engine.Instrument
import com.coinfutures.engine.Timeframe
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.math.BigDecimal

data class AnalysisUi(
    val instrument: Instrument? = null,
    val bundle: AnalysisBundle? = null,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: ErrorCode? = null,
    val timeframe: Timeframe = Timeframe.H1,
    val superseded: Boolean = false,
    val now: Long = System.currentTimeMillis(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AnalysisViewModel(
    private val c: AppContainer,
    private val instrumentId: String,
    private val fromPlanKey: String?,
    private val fromEventId: Long?,
) : ViewModel() {
    private val _ui = MutableStateFlow(AnalysisUi())
    val ui = _ui.asStateFlow()
    val settings: StateFlow<AppSettings?> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val alert: StateFlow<AlertEntity?> = c.activity.alertFor(instrumentId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val market: StateFlow<MarketEntity?> = c.markets.observeMarket(instrumentId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var poller: Job? = null
    private var eventRevision: Int? = null

    init {
        viewModelScope.launch {
            fromEventId?.let { id ->
                c.activity.markOpened(id)
                eventRevision = c.db.alerts().event(id)?.planRevision
            }
            try {
                val inst = c.markets.instrument(instrumentId)
                _ui.value = _ui.value.copy(instrument = inst, bundle = c.markets.cached(instrumentId))
                c.markets.markViewed(inst)
                load(initial = true)
            } catch (e: MarketException) {
                _ui.value = _ui.value.copy(loading = false, error = e.code)
            }
        }
    }

    /** Live polling while the screen is visible (quote freshness limit is 10 s). */
    fun start() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (isActive) {
                delay(POLL_MS)
                _ui.value = _ui.value.copy(now = System.currentTimeMillis())
                val inst = _ui.value.instrument ?: continue
                if (inst.simulated) continue
                load(initial = false)
            }
        }
    }

    fun stop() { poller?.cancel() }

    fun refresh() = viewModelScope.launch {
        _ui.value = _ui.value.copy(refreshing = true)
        load(initial = false)
        _ui.value = _ui.value.copy(refreshing = false)
    }

    private suspend fun load(initial: Boolean) {
        val inst = _ui.value.instrument ?: return
        try {
            val b = c.markets.analyze(inst)
            val superseded = fromPlanKey != null && (b.result.plan?.planKey != fromPlanKey || (eventRevision != null && b.planRevision != eventRevision))
            _ui.value = _ui.value.copy(bundle = b, loading = false, error = null, superseded = superseded, now = System.currentTimeMillis())
        } catch (e: MarketException) {
            // Keep the last result visible; it is re-evaluated as stale by the engine on the next success.
            _ui.value = _ui.value.copy(loading = false, error = if (_ui.value.bundle == null || initial) e.code else _ui.value.error ?: e.code)
            if (e.code == ErrorCode.RATE_LIMITED) delay((e.retryAfterSeconds ?: 10) * 1000)
        }
    }

    fun setTimeframe(tf: Timeframe) { _ui.value = _ui.value.copy(timeframe = tf) }

    fun toggleSaved() = viewModelScope.launch {
        val inst = _ui.value.instrument ?: return@launch
        c.markets.setSaved(inst, !(market.value?.saved ?: false))
    }

    fun saveAlert(types: Set<AlertEventType>, enabled: Boolean) = viewModelScope.launch {
        val inst = _ui.value.instrument ?: return@launch
        c.activity.saveAlert(inst.instrumentId, inst.symbol, inst.venueName, types, enabled)
        (c.app as? CoinFuturesApp)?.syncBackgroundWork()
    }

    fun enableNotifications() = viewModelScope.launch {
        c.settings.setNotifications(true)
        (c.app as? CoinFuturesApp)?.syncBackgroundWork()
    }

    fun recordPaper(entry: BigDecimal, qty: BigDecimal, notes: String, onDone: (Long) -> Unit) = viewModelScope.launch {
        val b = _ui.value.bundle ?: return@launch
        val p = b.result.plan ?: return@launch
        val stop = p.stop ?: return@launch
        val farEdge = if (p.direction == com.coinfutures.engine.Direction.LONG) p.zone.lower else p.zone.upper
        val id = c.activity.recordPaperTrade(
            p.planKey, b.planRevision ?: 1, p.instrumentId, b.result.instrument.symbol, p.direction, entry, qty, stop,
            p.targets.getOrNull(0)?.price, p.targets.getOrNull(1)?.price, farEdge, notes, b.snapshot.evaluatedAt,
        )
        (c.app as? CoinFuturesApp)?.syncBackgroundWork()
        onDone(id)
    }

    companion object {
        const val POLL_MS = 5_000L
    }
}
