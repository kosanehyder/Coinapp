package com.coinfutures.engine

import java.math.BigDecimal

/** Analysis timeframes used by the intraday profile (4H direction, 1H zones, 15M entry). */
enum class Timeframe(val millis: Long, val code: String) {
    M15(15 * 60_000L, "15m"),
    H1(60 * 60_000L, "1h"),
    H4(4 * 60 * 60_000L, "4h");
}

/**
 * Normalized OHLCV candle. [openTime] is UTC epoch millis; [closeTime] is exclusive
 * (openTime + interval). Volume unit is the base asset (see [Instrument.volumeUnit]).
 */
data class Candle(
    val openTime: Long,
    val closeTime: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
) {
    /** Price-mirrored candle (p -> -p) used to evaluate short setups with long logic. */
    fun mirrored(): Candle = copy(open = open.negate(), high = low.negate(), low = high.negate(), close = close.negate())

    fun intersects(lower: BigDecimal, upper: BigDecimal): Boolean = low <= upper && high >= lower
}

enum class ContractType { PERPETUAL, DELIVERY, SPOT, OTHER }

enum class TradingStatus { TRADING, PRE_TRADING, SETTLING, DELISTED, UNKNOWN }

data class Instrument(
    val instrumentId: String,
    val venue: String,
    val venueName: String,
    val symbol: String,
    val base: String,
    val quote: String,
    val contractType: ContractType,
    val linear: Boolean,
    val multiplier: BigDecimal,
    val tickSize: BigDecimal,
    val qtyStep: BigDecimal,
    val minQty: BigDecimal,
    val minNotional: BigDecimal,
    val tradingStatus: TradingStatus,
    val displayName: String? = null,
    val volumeUnit: String = base,
    val simulated: Boolean = false,
) {
    /** MVP scope: USDT-margined linear perpetual that is currently trading. */
    val isSupported: Boolean
        get() = contractType == ContractType.PERPETUAL && linear && quote == "USDT" && tradingStatus == TradingStatus.TRADING

    val unsupportedReason: ReasonCode?
        get() = when {
            tradingStatus == TradingStatus.DELISTED || tradingStatus == TradingStatus.SETTLING -> ReasonCode.INSTRUMENT_DELISTED
            contractType == ContractType.SPOT -> ReasonCode.SPOT_MARKET
            contractType != ContractType.PERPETUAL -> ReasonCode.NOT_PERPETUAL
            !linear || quote != "USDT" -> ReasonCode.NOT_USDT_LINEAR
            tradingStatus != TradingStatus.TRADING -> ReasonCode.NOT_TRADING
            else -> null
        }
}

/** Live quote. [receivedAt] is when the venue answered; [sourceTime] is the venue timestamp if supplied. */
data class Quote(
    val bid: BigDecimal,
    val ask: BigDecimal,
    val last: BigDecimal,
    val mark: BigDecimal?,
    val sourceTime: Long?,
    val receivedAt: Long,
) {
    fun mirrored(): Quote = copy(bid = ask.negate(), ask = bid.negate(), last = last.negate(), mark = mark?.negate())
    val mid: BigDecimal get() = (bid + ask).divide(BigDecimal(2), Dec.MC)
}

data class Funding(
    val rate: BigDecimal,
    val intervalHours: Int?,
    val nextFundingTime: Long?,
    val sourceTime: Long?,
    val receivedAt: Long,
)

data class OpenInterest(
    val value: BigDecimal,
    val unit: String,
    val sourceTime: Long?,
    val receivedAt: Long,
)

/**
 * Everything the engine needs, frozen at one moment. Identical snapshots + identical
 * [StrategyConfig] produce identical results (AT03).
 */
data class MarketSnapshot(
    val instrument: Instrument,
    val candles: Map<Timeframe, List<Candle>>,
    val quote: Quote?,
    val funding: Funding?,
    val openInterest: OpenInterest?,
    /** Same-venue BTC perpetual 4H candles, for altcoin context only. Null for BTC itself or when unavailable. */
    val btcContext4h: List<Candle>?,
    /** Evaluation time on the venue clock (UTC millis). */
    val evaluatedAt: Long,
    val source: String,
)

enum class Direction { LONG, SHORT;
    val opposite get() = if (this == LONG) SHORT else LONG
}

enum class Bias { LONG, SHORT, NEUTRAL, UNKNOWN }

enum class OverallStatus { LONG_SETUP, SHORT_SETUP, WAIT, NO_TRADE, DATA_UNAVAILABLE }

/** Plan lifecycle: Watching breakout -> Waiting retest -> Waiting confirmation -> Entry Ready -> Expired/Invalidated. */
enum class PlanState {
    WAITING_RETEST, WAITING_CONFIRMATION, ENTRY_READY, EXPIRED, INVALIDATED;
    val isTerminal get() = this == EXPIRED || this == INVALIDATED
}

/** Stable error codes from the API contract (section 8). */
enum class ErrorCode {
    UNKNOWN_INSTRUMENT, UNSUPPORTED_CONTRACT, INSUFFICIENT_HISTORY, STALE_DATA, DATA_GAP,
    RATE_LIMITED, PLAN_EXPIRED, INVALID_RISK_INPUT, MIN_SIZE_CONFLICT, NETWORK, REGION_BLOCKED,
}

/** Machine-readable reasons. The app maps each code to English and Roman Urdu text. */
enum class ReasonCode {
    // Data
    QUOTE_MISSING, QUOTE_STALE, CANDLE_STALE, CANDLE_GAP, CANDLE_INVALID, CANDLE_DUPLICATE, CANDLE_ORDER,
    INSUFFICIENT_HISTORY, INDICATOR_WARMUP,
    // Direction
    BIAS_LONG, BIAS_SHORT, MIXED_STRUCTURE, NOT_ENOUGH_SWINGS, EMA_NOT_ALIGNED, CLOSE_VS_EMA200,
    // Lifecycle
    AWAITING_BREAKOUT, BREAKOUT_CONFIRMED, VOLUME_PASSED, WAITING_RETEST, RETEST_DONE, WAITING_CONFIRMATION,
    CONFIRMATION_DONE, ENTRY_READY, PRICE_IN_BAND, PRICE_ABOVE_BAND, PRICE_BELOW_BAND,
    RETEST_TIMEOUT, CONFIRMATION_TIMEOUT, ENTRY_WINDOW_EXPIRED, FAR_SIDE_CLOSE, STOP_TOUCHED, STOP_TOUCHED_LIVE,
    PREVIOUS_SETUP_EXPIRED, PREVIOUS_SETUP_INVALIDATED, CONFLICTING_PLANS,
    // Gates
    SPREAD_TOO_WIDE, MISSING_TARGETS, RR_TOO_LOW, PRICES_NOT_ORDERED, LIMITED_STRUCTURE,
    // Limitations
    NEWS_NOT_CHECKED, FUNDING_UNAVAILABLE, FUNDING_STALE, OI_UNAVAILABLE, OI_STALE, LIQUIDATION_NOT_CALCULATED,
    BTC_CONTEXT_UNAVAILABLE, SIMULATED_DATA,
    // Instruments
    INSTRUMENT_DELISTED, SPOT_MARKET, NOT_PERPETUAL, NOT_USDT_LINEAR, NOT_TRADING,
}
