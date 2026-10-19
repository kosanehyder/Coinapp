package com.coinfutures.app.data.market

import com.coinfutures.engine.ErrorCode
import com.coinfutures.engine.Instrument
import com.coinfutures.engine.MarketSnapshot

/** One venue adapter. Each adapter normalizes its own data; venues are never mixed in one analysis. */
interface MarketDataSource {
    val venueId: String
    val venueName: String
    val simulated: Boolean

    /** Instrument metadata (cached by the adapter). */
    suspend fun instruments(forceRefresh: Boolean = false): List<Instrument>

    /**
     * A complete snapshot for one instrument from this venue only: closed + live candles for
     * 4H/1H/15M, best bid/ask, last and mark, funding, open interest and BTC 4H context.
     */
    suspend fun snapshot(instrument: Instrument): MarketSnapshot

    /** Lightweight connectivity probe used by the Home status chip. */
    suspend fun ping(): Long
}

/** Typed failure with a stable error code from the API contract. */
class MarketException(
    val code: ErrorCode,
    message: String,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

object Venues {
    const val BINANCE_USDM = "BINANCE_USDM"
    const val DEMO = "DEMO"

    fun venueOf(instrumentId: String): String = instrumentId.substringBefore(':')
}
