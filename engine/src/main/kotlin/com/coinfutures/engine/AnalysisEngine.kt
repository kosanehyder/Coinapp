package com.coinfutures.engine

import java.math.BigDecimal
import java.security.MessageDigest

data class IndicatorSummary(
    val ema20h4: BigDecimal?,
    val ema50h4: BigDecimal?,
    val ema200h4: BigDecimal?,
    val atr1h: BigDecimal?,
    val atr15: BigDecimal?,
    val rsi15: BigDecimal?,
    val volumeMean1h: BigDecimal?,
)

/** Structured result matching the /v1/analysis response requirements (section 8). */
data class AnalysisResult(
    val analysisId: String,
    val instrument: Instrument,
    val strategyVersion: String,
    val snapshotTime: Long,
    val lastClosedCandleTimes: Map<Timeframe, Long?>,
    val source: String,
    val qualityOk: Boolean,
    val dataIssues: List<DataIssue>,
    val quoteAgeMs: Long?,
    val limitations: List<ReasonCode>,
    val status: OverallStatus,
    val primaryReason: ReasonCode,
    val supportingReasons: List<ReasonCode>,
    val nextCondition: NextCondition?,
    val bias: BiasResult?,
    val btcContext: BiasResult?,
    val currentPrice: BigDecimal?,
    val zones: ZoneSet?,
    val plan: TradePlan?,
    val indicators: IndicatorSummary?,
    val quote: Quote?,
    val funding: Funding?,
    val openInterest: OpenInterest?,
) {
    val supports: List<Zone> get() = zones?.supports.orEmpty()
    val resistances: List<Zone> get() = zones?.resistances.orEmpty()
}

/**
 * Deterministic analysis pipeline: validate -> bias -> zones -> setup lifecycle -> gates.
 * Pure function of (snapshot, config). Designed to run unchanged on a backend (JVM) so that
 * mobile clients never compute a competing signal.
 */
object AnalysisEngine {

    fun analyze(snapshot: MarketSnapshot, config: StrategyConfig = StrategyConfig.DEFAULT): AnalysisResult {
        val instrument = snapshot.instrument
        val quality = DataQuality.assess(snapshot, config)
        val limitations = DataQuality.limitations(snapshot, config)
        val closed = quality.closedCandles
        val c4h = closed[Timeframe.H4].orEmpty()
        val c1h = closed[Timeframe.H1].orEmpty()
        val c15 = closed[Timeframe.M15].orEmpty()
        val lastTimes = Timeframe.entries.associateWith { closed[it]?.lastOrNull()?.closeTime }
        val id = analysisId(snapshot, config)

        // Structure can be shown only when every series is internally valid and warm.
        val seriesBlocking = quality.issues.any {
            it.code in setOf(
                ReasonCode.CANDLE_INVALID, ReasonCode.CANDLE_DUPLICATE, ReasonCode.CANDLE_ORDER,
                ReasonCode.CANDLE_GAP, ReasonCode.INSUFFICIENT_HISTORY,
            )
        }
        if (seriesBlocking || c1h.size < 30 || c15.size < 30 || c4h.isEmpty()) {
            val first = quality.issues.firstOrNull()?.code ?: ReasonCode.INSUFFICIENT_HISTORY
            return AnalysisResult(
                id, instrument, config.version, snapshot.evaluatedAt, lastTimes, snapshot.source, false,
                quality.issues.ifEmpty { listOf(DataIssue(ReasonCode.INSUFFICIENT_HISTORY)) }, quality.quoteAgeMs, limitations,
                OverallStatus.DATA_UNAVAILABLE, first, quality.issues.drop(1).map { it.code }.distinct(), null, null, null,
                snapshot.quote?.last ?: c1h.lastOrNull()?.close, null, null, null, snapshot.quote, snapshot.funding, snapshot.openInterest,
            )
        }

        val tick = instrument.tickSize
        val bias = BiasEvaluator(c4h, config).at(snapshot.evaluatedAt)
        val btc = snapshot.btcContext4h?.let { BiasEvaluator(DataQuality.closedOnly(it, snapshot.evaluatedAt), config).at(snapshot.evaluatedAt) }

        val atr1hArr = Indicators.atr(c1h, config.atrPeriod)
        val atr1h = atr1hArr[c1h.lastIndex]
        val ref = snapshot.quote?.last ?: c1h.last().close
        val zones = atr1h?.let { Zones.build(c1h, c1h.lastIndex, it, tick, ref, config) }

        val closes4h = c4h.map { it.close }
        val indicators = IndicatorSummary(
            ema20h4 = Indicators.ema(closes4h, config.emaFast).lastOrNull(),
            ema50h4 = Indicators.ema(closes4h, config.emaMid).lastOrNull(),
            ema200h4 = Indicators.ema(closes4h, config.emaSlow).lastOrNull(),
            atr1h = atr1h,
            atr15 = Indicators.atr(c15, config.atrPeriod).lastOrNull(),
            rsi15 = Indicators.rsi(c15.map { it.close }, config.rsiPeriod).lastOrNull(),
            volumeMean1h = Indicators.meanVolumeBefore(c1h, c1h.size, config.volumeMeanPeriod),
        )

        // Run the lifecycle only in the direction the 4H filter allows. Countertrend/range trades are out of MVP scope.
        var plan: TradePlan? = null
        var watch: NextCondition? = null
        when (bias.bias) {
            Bias.LONG -> {
                val f = SetupFinder(instrument.instrumentId, c4h, c1h, c15, tick, snapshot.quote, snapshot.evaluatedAt, config)
                plan = f.find()
                watch = f.watchCondition()?.copy(direction = Direction.LONG)
            }
            Bias.SHORT -> {
                val f = SetupFinder(
                    instrument.instrumentId, c4h.map { it.mirrored() }, c1h.map { it.mirrored() }, c15.map { it.mirrored() },
                    tick, snapshot.quote?.mirrored(), snapshot.evaluatedAt, config,
                )
                plan = f.find()?.mirrored()
                watch = f.watchCondition()?.let { it.copy(price = it.price?.negate(), direction = Direction.SHORT) }
            }
            else -> Unit
        }

        val supporting = ArrayList<ReasonCode>()
        var status: OverallStatus
        var primary: ReasonCode
        var next: NextCondition? = null

        when {
            bias.bias == Bias.NEUTRAL || bias.bias == Bias.UNKNOWN -> {
                status = OverallStatus.WAIT
                primary = ReasonCode.MIXED_STRUCTURE
                supporting += bias.reasons.filter { it != ReasonCode.MIXED_STRUCTURE }
            }
            plan == null -> {
                status = OverallStatus.WAIT
                primary = ReasonCode.AWAITING_BREAKOUT
                supporting += bias.reasons
                next = watch
            }
            else -> {
                val p = plan
                supporting += bias.reasons
                when (p.state) {
                    PlanState.WAITING_RETEST -> {
                        status = OverallStatus.WAIT
                        primary = ReasonCode.WAITING_RETEST
                        supporting += listOf(ReasonCode.BREAKOUT_CONFIRMED, ReasonCode.VOLUME_PASSED)
                        next = NextCondition(ReasonCode.WAITING_RETEST, Timeframe.M15, price = if (p.direction == Direction.LONG) p.zone.upper else p.zone.lower, candlesRemaining = p.candlesRemaining, direction = p.direction)
                    }
                    PlanState.WAITING_CONFIRMATION -> {
                        supporting += listOf(ReasonCode.BREAKOUT_CONFIRMED, ReasonCode.VOLUME_PASSED, ReasonCode.RETEST_DONE)
                        if (p.gateFailures.isNotEmpty()) {
                            status = OverallStatus.NO_TRADE
                            primary = p.gateFailures.first()
                            supporting += p.gateFailures.drop(1)
                        } else {
                            status = OverallStatus.WAIT
                            primary = ReasonCode.WAITING_CONFIRMATION
                            val trig = if (p.direction == Direction.LONG) p.retestHigh!! + tick else p.retestLow!! - tick
                            next = NextCondition(ReasonCode.WAITING_CONFIRMATION, Timeframe.M15, price = trig, candlesRemaining = p.candlesRemaining, direction = p.direction)
                        }
                    }
                    PlanState.ENTRY_READY -> {
                        supporting += listOf(ReasonCode.BREAKOUT_CONFIRMED, ReasonCode.VOLUME_PASSED, ReasonCode.RETEST_DONE, ReasonCode.CONFIRMATION_DONE)
                        if (p.gateFailures.isNotEmpty()) {
                            status = OverallStatus.NO_TRADE
                            primary = p.gateFailures.first()
                            supporting += p.gateFailures.drop(1)
                        } else {
                            status = if (p.direction == Direction.LONG) OverallStatus.LONG_SETUP else OverallStatus.SHORT_SETUP
                            primary = ReasonCode.ENTRY_READY
                            p.liveRelation?.let { supporting += it }
                        }
                    }
                    PlanState.EXPIRED -> {
                        status = OverallStatus.WAIT
                        primary = ReasonCode.PREVIOUS_SETUP_EXPIRED
                        p.terminalReason?.let { supporting += it }
                        next = watch
                    }
                    PlanState.INVALIDATED -> {
                        status = OverallStatus.WAIT
                        primary = ReasonCode.PREVIOUS_SETUP_INVALIDATED
                        p.terminalReason?.let { supporting += it }
                        next = watch
                    }
                }
            }
        }
        if (zones?.limitedStructure == true) supporting += ReasonCode.LIMITED_STRUCTURE

        // Stale or missing live data: structure stays visible, but nothing is actionable (DATA03).
        if (!quality.ok) {
            supporting.add(0, primary)
            status = OverallStatus.DATA_UNAVAILABLE
            primary = quality.issues.first().code
            next = null
        }

        return AnalysisResult(
            analysisId = id,
            instrument = instrument,
            strategyVersion = config.version,
            snapshotTime = snapshot.evaluatedAt,
            lastClosedCandleTimes = lastTimes,
            source = snapshot.source,
            qualityOk = quality.ok,
            dataIssues = quality.issues,
            quoteAgeMs = quality.quoteAgeMs,
            limitations = limitations,
            status = status,
            primaryReason = primary,
            supportingReasons = supporting.distinct().filter { it != primary },
            nextCondition = next,
            bias = bias,
            btcContext = btc,
            currentPrice = ref,
            zones = zones,
            plan = plan,
            indicators = indicators,
            quote = snapshot.quote,
            funding = snapshot.funding,
            openInterest = snapshot.openInterest,
        )
    }

    private fun analysisId(s: MarketSnapshot, c: StrategyConfig): String {
        val key = buildString {
            append(s.instrument.instrumentId).append('|').append(c.version).append('|').append(s.evaluatedAt)
            for (tf in Timeframe.entries) append('|').append(s.candles[tf]?.lastOrNull()?.openTime)
            append('|').append(s.quote?.last?.toPlainString())
        }
        val d = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
        return d.take(8).joinToString("") { "%02x".format(it) }
    }
}
