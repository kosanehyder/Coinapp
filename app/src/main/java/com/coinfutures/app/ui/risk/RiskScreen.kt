package com.coinfutures.app.ui.risk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.coinfutures.app.AppContainer
import com.coinfutures.app.data.repo.AnalysisBundle
import com.coinfutures.app.ui.components.Bullet
import com.coinfutures.app.ui.components.EmptyState
import com.coinfutures.app.ui.components.LabeledValue
import com.coinfutures.app.ui.components.LoadingColumn
import com.coinfutures.app.ui.components.Pill
import com.coinfutures.app.ui.components.SectionCard
import com.coinfutures.app.ui.format.Fmt
import com.coinfutures.app.ui.l10n.LocalStrings
import com.coinfutures.app.ui.navigation.LocalContainer
import com.coinfutures.app.ui.theme.AppTheme
import com.coinfutures.engine.CostModel
import com.coinfutures.engine.Direction
import com.coinfutures.engine.PlanState
import com.coinfutures.engine.RiskCalculator
import com.coinfutures.engine.RiskInputs
import com.coinfutures.engine.RiskResult
import com.coinfutures.engine.Slippage
import com.coinfutures.engine.TargetSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode

data class RiskForm(
    val equity: String = "",
    val riskPct: String = "0.5",
    val entry: String = "",
    val leverage: String = "3",
    val margin: String = "",
    val feePct: String = "0.05",
    val slipPct: String = "0.02",
    val funding: String = "0",
    val remember: Boolean = false,
)

data class RiskUi(
    val loading: Boolean = true,
    val bundle: AnalysisBundle? = null,
    val form: RiskForm = RiskForm(),
    val result: RiskResult? = null,
    val outsideBand: Boolean = false,
)

class RiskViewModel(private val c: AppContainer, private val instrumentId: String) : ViewModel() {
    private val _ui = MutableStateFlow(RiskUi())
    val ui = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val b = c.markets.cached(instrumentId) ?: runCatching { c.markets.analyze(c.markets.instrument(instrumentId)) }.getOrNull()
            val d = c.settings.current().risk
            val scale = b?.result?.instrument?.let { Fmt.priceScale(it) } ?: 2
            val worst = b?.result?.plan?.metrics?.worstEntry?.setScale(scale, RoundingMode.HALF_EVEN)?.toPlainString().orEmpty()
            _ui.value = RiskUi(
                loading = false, bundle = b,
                form = RiskForm(
                    equity = d.equity.orEmpty(), riskPct = d.riskPercent, entry = worst, leverage = d.leverage,
                    feePct = d.feePercent, slipPct = d.slippagePercent, remember = d.rememberEquity,
                ),
            )
            recompute()
        }
    }

    fun update(f: RiskForm) { _ui.value = _ui.value.copy(form = f); recompute() }

    private fun recompute() {
        val b = _ui.value.bundle ?: return
        val p = b.result.plan ?: return
        val stop = p.stop ?: return
        val band = p.entryBand ?: return
        val f = _ui.value.form
        val hundred = BigDecimal(100)
        val entry = f.entry.toBigDecimalOrNull()
        val inputs = RiskInputs(
            equity = f.equity.toBigDecimalOrNull(),
            riskPercent = f.riskPct.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            entryPrice = entry,
            leverage = f.leverage.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            availableMargin = f.margin.toBigDecimalOrNull(),
            costs = CostModel(
                feeRate = (f.feePct.toBigDecimalOrNull() ?: BigDecimal(-1)).divide(hundred),
                slippage = Slippage.Percent((f.slipPct.toBigDecimalOrNull() ?: BigDecimal(-1)).divide(hundred)),
                fundingDebitPerCoin = f.funding.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            ),
        )
        val targets = p.targets.map { TargetSpec(it.price, it.allocation) }
        val res = RiskCalculator.estimate(p.direction, stop, targets, b.result.instrument, inputs)
        _ui.value = _ui.value.copy(result = res, outsideBand = entry != null && !band.contains(entry))
    }

    fun saveDefaults() = viewModelScope.launch {
        val f = _ui.value.form
        c.settings.setRiskDefaults(f.riskPct, f.leverage, f.feePct, f.slipPct)
        c.settings.setRememberEquity(f.remember, f.equity)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiskScreen(instrumentId: String, onBack: () -> Unit) {
    val container = LocalContainer.current
    val vm: RiskViewModel = viewModel(key = "risk-$instrumentId") { RiskViewModel(container, instrumentId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s = LocalStrings.current
    val t = AppTheme.trade

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.riskTitle) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = s.close) } },
            )
        },
    ) { inner ->
        val b = ui.bundle
        val plan = b?.result?.plan
        when {
            ui.loading -> LoadingColumn(s.loading)
            plan == null || plan.state.isTerminal -> EmptyState(Icons.Filled.ErrorOutline, s.error(com.coinfutures.engine.ErrorCode.PLAN_EXPIRED), s.planExpired, Modifier.padding(inner))
            plan.stop == null || plan.entryBand == null -> EmptyState(Icons.Filled.Calculate, s.riskTitle, s.needsLevels, Modifier.padding(inner))
            else -> {
                val i = b!!.result.instrument
                val f = ui.form
                Column(
                    Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(s.riskIntro, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (plan.state != PlanState.ENTRY_READY) Pill(s.provisional, color = t.wait, container = t.waitContainer)
                    SectionCard(title = s.tradePlan, icon = Icons.Filled.Tune) {
                        LabeledValue(s.entryBand, "${Fmt.price(plan.entryBand!!.lower, i)} – ${Fmt.price(plan.entryBand!!.upper, i)}")
                        LabeledValue(s.stopLoss, Fmt.price(plan.stop, i))
                        plan.targets.forEachIndexed { k, tg -> LabeledValue(if (k == 0) s.target1 else s.target2, Fmt.price(tg.price, i)) }
                        LabeledValue("Direction", s.direction(plan.direction == Direction.LONG))
                    }
                    Num(s.equity, f.equity) { vm.update(f.copy(equity = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Num(s.riskPercent, f.riskPct, Modifier.weight(1f)) { vm.update(f.copy(riskPct = it)) }
                        Num(s.leverage, f.leverage, Modifier.weight(1f)) { vm.update(f.copy(leverage = it)) }
                    }
                    Num(s.entryPrice, f.entry, supporting = s.entryWorstHint, error = ui.outsideBand) { vm.update(f.copy(entry = it)) }
                    Num(s.availableMargin, f.margin) { vm.update(f.copy(margin = it)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Num(s.feePercent, f.feePct, Modifier.weight(1f)) { vm.update(f.copy(feePct = it)) }
                        Num(s.slippagePercent, f.slipPct, Modifier.weight(1f)) { vm.update(f.copy(slipPct = it)) }
                    }
                    Num(s.fundingDebit, f.funding) { vm.update(f.copy(funding = it)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.rememberEquity, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(checked = f.remember, onCheckedChange = { vm.update(f.copy(remember = it)) })
                    }
                    Button(onClick = { vm.saveDefaults() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text(s.save) }

                    if (ui.outsideBand) Bullet(s.riskError("ENTRY_OUTSIDE_BAND"), marker = t.short)
                    when (val r = ui.result) {
                        is RiskResult.Invalid -> SectionCard(title = s.error(com.coinfutures.engine.ErrorCode.INVALID_RISK_INPUT), icon = Icons.Filled.ErrorOutline) {
                            r.errors.forEach { Bullet(s.riskError(it.name), marker = t.short) }
                            r.minimumBudgetHint?.let { Bullet(s.minimumBudget(Fmt.money(it)), marker = t.wait) }
                        }
                        is RiskResult.Ok -> {
                            val e = r.estimate
                            SectionCard(title = s.quantity, icon = Icons.Filled.Calculate) {
                                LabeledValue(s.quantity, "${Fmt.qty(e.quantity, i)} (${Fmt.qty(e.coins, i)} ${s.coins})", emphasize = true)
                                LabeledValue(s.notional, Fmt.money(e.notional) + " USDT")
                                LabeledValue(s.marginEstimate, Fmt.money(e.marginEstimate) + " USDT")
                                LabeledValue(s.riskBudget, Fmt.money(e.riskBudget) + " USDT")
                                LabeledValue(s.plannedLoss, Fmt.money(e.plannedLoss) + " USDT", valueColor = t.stop, emphasize = true)
                                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                                Text(s.perCoinRisk, style = MaterialTheme.typography.labelLarge)
                                LabeledValue(s.priceRisk, Fmt.money(e.perCoin.priceRisk, 6))
                                LabeledValue(s.fees, Fmt.money(e.perCoin.fees, 6))
                                LabeledValue(s.slippage, Fmt.money(e.perCoin.slippage, 6))
                                LabeledValue(s.fundingCost, Fmt.money(e.perCoin.funding, 6))
                                LabeledValue(s.perCoinRisk, Fmt.money(e.perCoin.total, 6), emphasize = true)
                            }
                            SectionCard(title = s.targetOutcomes, icon = Icons.Filled.Tune) {
                                if (!e.splitAccepted) Bullet(s.singleExitFallback, marker = t.wait)
                                e.targets.forEachIndexed { k, o ->
                                    LabeledValue(
                                        (if (e.targets.size == 1) s.target2 else if (k == 0) s.target1 else s.target2) + " · " + Fmt.price(o.price, i),
                                        Fmt.money(o.netProfit) + " USDT",
                                        caption = "${Fmt.qty(o.quantity, i)} · ${s.grossRr} ${Fmt.ratio(o.grossRr)} · net ${Fmt.ratio(o.netRr)}",
                                        valueColor = t.target,
                                    )
                                }
                                LabeledValue(s.grossRr, Fmt.ratio(e.grossRrFinal))
                                LabeledValue("Net R:R (final target)", Fmt.ratio(e.netRrFinal))
                                LabeledValue(s.blendedRr, Fmt.ratio(e.blendedNetRr))
                            }
                            Bullet(s.liquidationNote, marker = t.wait)
                            Bullet(s.plannedLossNote, marker = t.wait)
                            Bullet(s.leverageNote, marker = t.wait)
                        }
                        null -> Unit
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun Num(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), supporting: String? = null, error: Boolean = false, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '.' }.take(24)) },
        label = { Text(label) },
        singleLine = true,
        isError = error,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
