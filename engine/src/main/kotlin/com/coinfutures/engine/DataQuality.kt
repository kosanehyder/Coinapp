package com.coinfutures.engine

data class DataIssue(val code: ReasonCode, val timeframe: Timeframe? = null, val detail: String = "") {
    val errorCode: ErrorCode
        get() = when (code) {
            ReasonCode.CANDLE_GAP, ReasonCode.CANDLE_DUPLICATE, ReasonCode.CANDLE_ORDER, ReasonCode.CANDLE_INVALID -> ErrorCode.DATA_GAP
            ReasonCode.INSUFFICIENT_HISTORY, ReasonCode.INDICATOR_WARMUP -> ErrorCode.INSUFFICIENT_HISTORY
            else -> ErrorCode.STALE_DATA
        }
}

data class QualityReport(
    val issues: List<DataIssue>,
    val closedCandles: Map<Timeframe, List<Candle>>,
    val quoteAgeMs: Long?,
) {
    /** Any blocking issue sets Data Unavailable for new signals (DATA03). */
    val ok: Boolean get() = issues.isEmpty()
}

object DataQuality {

    /** DATA05: only confirmed closed candles may change structure; in-progress candles are dropped. */
    fun closedOnly(candles: List<Candle>, now: Long): List<Candle> = candles.filter { it.closeTime <= now }

    /** DATA01: uniqueness, chronological order, OHLC consistency, nonnegative volume, interval continuity. */
    fun validateSeries(candles: List<Candle>, tf: Timeframe): List<DataIssue> {
        val issues = ArrayList<DataIssue>()
        for (i in candles.indices) {
            val c = candles[i]
            val bodyHigh = if (c.open >= c.close) c.open else c.close
            val bodyLow = if (c.open <= c.close) c.open else c.close
            if (c.high < bodyHigh || c.low > bodyLow || c.high < c.low || c.volume.signum() < 0 ||
                c.closeTime - c.openTime != tf.millis
            ) {
                issues += DataIssue(ReasonCode.CANDLE_INVALID, tf, "openTime=${c.openTime}")
                break
            }
            if (i > 0) {
                val d = c.openTime - candles[i - 1].openTime
                when {
                    d == 0L -> { issues += DataIssue(ReasonCode.CANDLE_DUPLICATE, tf, "openTime=${c.openTime}"); break }
                    d < 0L -> { issues += DataIssue(ReasonCode.CANDLE_ORDER, tf, "openTime=${c.openTime}"); break }
                    d != tf.millis -> { issues += DataIssue(ReasonCode.CANDLE_GAP, tf, "after=${candles[i - 1].openTime}"); break }
                }
            }
        }
        return issues
    }

    /**
     * DATA03: quote older than the limit, a closed candle missing more than the grace period after
     * it should exist, or an unresolved gap makes new signals unavailable.
     */
    fun assess(snapshot: MarketSnapshot, config: StrategyConfig): QualityReport {
        val now = snapshot.evaluatedAt
        val issues = ArrayList<DataIssue>()
        val closed = HashMap<Timeframe, List<Candle>>()
        for (tf in Timeframe.entries) {
            val list = closedOnly(snapshot.candles[tf].orEmpty(), now)
            closed[tf] = list
            issues += validateSeries(list, tf)
            if (list.size < config.minClosedCandles) {
                issues += DataIssue(ReasonCode.INSUFFICIENT_HISTORY, tf, "have=${list.size} need=${config.minClosedCandles}")
            }
            val expectedLastOpen = Math.floorDiv(now, tf.millis) * tf.millis - tf.millis
            val lastOpen = list.lastOrNull()?.openTime
            val expectedClose = expectedLastOpen + tf.millis
            if (lastOpen == null || (lastOpen < expectedLastOpen && now - expectedClose > config.candleGraceMs)) {
                issues += DataIssue(ReasonCode.CANDLE_STALE, tf, "last=$lastOpen expected=$expectedLastOpen")
            }
        }
        val quote = snapshot.quote
        val quoteAge = quote?.let { now - it.receivedAt }
        when {
            quote == null -> issues += DataIssue(ReasonCode.QUOTE_MISSING)
            quoteAge!! > config.quoteMaxAgeMs -> issues += DataIssue(ReasonCode.QUOTE_STALE, detail = "ageMs=$quoteAge")
            quote.bid.signum() <= 0 || quote.ask < quote.bid -> issues += DataIssue(ReasonCode.QUOTE_MISSING, detail = "crossed or empty book")
        }
        return QualityReport(issues, closed, quoteAge)
    }

    /** DATA04: optional inputs only add visible limitations. */
    fun limitations(snapshot: MarketSnapshot, config: StrategyConfig): List<ReasonCode> {
        val now = snapshot.evaluatedAt
        val out = ArrayList<ReasonCode>()
        out += ReasonCode.NEWS_NOT_CHECKED
        val f = snapshot.funding
        when {
            f == null -> out += ReasonCode.FUNDING_UNAVAILABLE
            now - f.receivedAt > config.fundingMaxAgeMs -> out += ReasonCode.FUNDING_STALE
        }
        val oi = snapshot.openInterest
        when {
            oi == null -> out += ReasonCode.OI_UNAVAILABLE
            now - oi.receivedAt > config.oiMaxAgeMs -> out += ReasonCode.OI_STALE
        }
        if (snapshot.instrument.base != "BTC" && snapshot.btcContext4h == null) out += ReasonCode.BTC_CONTEXT_UNAVAILABLE
        out += ReasonCode.LIQUIDATION_NOT_CALCULATED
        if (snapshot.instrument.simulated) out += ReasonCode.SIMULATED_DATA
        return out
    }
}
