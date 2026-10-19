package com.coinfutures.app.data.market

import com.coinfutures.engine.Candle
import com.coinfutures.engine.ContractType
import com.coinfutures.engine.DataQuality
import com.coinfutures.engine.ErrorCode
import com.coinfutures.engine.Funding
import com.coinfutures.engine.Instrument
import com.coinfutures.engine.MarketSnapshot
import com.coinfutures.engine.OpenInterest
import com.coinfutures.engine.Quote
import com.coinfutures.engine.Timeframe
import com.coinfutures.engine.TradingStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Binance USDⓈ-M futures public market data (no API keys, read-only).
 *
 * PROVIDER VERIFICATION REQUIRED (section 2): field names, weights, regional availability and
 * usage rights must be re-checked against the current Binance documentation before release.
 * Endpoints used: /fapi/v1/time, /exchangeInfo, /klines, /ticker/bookTicker, /ticker/price,
 * /premiumIndex, /fundingInfo, /openInterest.
 */
class BinanceFuturesSource(
    private val http: OkHttpClient,
    private val baseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
) : MarketDataSource {

    override val venueId = Venues.BINANCE_USDM
    override val venueName = "Binance USDⓈ-M"
    override val simulated = false

    private val json = Json { ignoreUnknownKeys = true }
    private val metaMutex = Mutex()
    @Volatile private var instrumentCache: List<Instrument> = emptyList()
    @Volatile private var instrumentsAt = 0L
    @Volatile private var fundingIntervals: Map<String, Int> = emptyMap()
    @Volatile private var clockOffset = 0L
    @Volatile private var offsetAt = 0L

    private data class SeriesKey(val symbol: String, val tf: Timeframe)
    private val candleCache = ConcurrentHashMap<SeriesKey, List<Candle>>()
    private val oiCache = ConcurrentHashMap<String, OpenInterest>()

    /** Venue clock estimate (local clock corrected by the measured server offset). */
    fun venueNow(): Long = clock() + clockOffset

    override suspend fun ping(): Long {
        val t0 = clock()
        val body = get("/fapi/v1/time")
        val server = body.jsonObject["serverTime"]!!.jsonPrimitive.long
        val t1 = clock()
        clockOffset = server - (t0 + t1) / 2
        offsetAt = t1
        return t1 - t0
    }

    override suspend fun instruments(forceRefresh: Boolean): List<Instrument> = metaMutex.withLock {
        val fresh = clock() - instrumentsAt < 60 * 60_000L
        if (!forceRefresh && fresh && instrumentCache.isNotEmpty()) return instrumentCache
        val info = get("/fapi/v1/exchangeInfo").jsonObject
        val list = info["symbols"]!!.jsonArray.mapNotNull { parseInstrument(it.jsonObject) }
        fundingIntervals = runCatching {
            get("/fapi/v1/fundingInfo").jsonArray.associate {
                val o = it.jsonObject
                o.str("symbol") to (o["fundingIntervalHours"]?.jsonPrimitive?.content?.toIntOrNull() ?: 8)
            }
        }.getOrDefault(emptyMap())
        instrumentCache = list
        instrumentsAt = clock()
        list
    }

    private fun parseInstrument(o: JsonObject): Instrument? {
        val symbol = o.str("symbol")
        val filters = o["filters"]?.jsonArray?.map { it.jsonObject }.orEmpty()
        fun filter(type: String) = filters.firstOrNull { it.str("filterType") == type }
        val tick = filter("PRICE_FILTER")?.dec("tickSize") ?: return null
        val lot = filter("LOT_SIZE") ?: return null
        val step = lot.dec("stepSize") ?: return null
        val minQty = lot.dec("minQty") ?: step
        val minNotional = filter("MIN_NOTIONAL")?.dec("notional") ?: BigDecimal.ZERO
        val contractType = when (o.str("contractType")) {
            "PERPETUAL" -> ContractType.PERPETUAL
            "CURRENT_QUARTER", "NEXT_QUARTER", "CURRENT_MONTH", "NEXT_MONTH" -> ContractType.DELIVERY
            else -> ContractType.OTHER
        }
        val status = when (o.str("status")) {
            "TRADING" -> TradingStatus.TRADING
            "PENDING_TRADING", "PRE_TRADING" -> TradingStatus.PRE_TRADING
            "SETTLING" -> TradingStatus.SETTLING
            "CLOSE", "DELIVERING", "DELIVERED", "PRE_DELIVERING", "PRE_SETTLE" -> TradingStatus.DELISTED
            else -> TradingStatus.UNKNOWN
        }
        return Instrument(
            instrumentId = "$venueId:$symbol",
            venue = venueId,
            venueName = venueName,
            symbol = symbol,
            base = o.str("baseAsset"),
            quote = o.str("quoteAsset"),
            contractType = contractType,
            linear = o.str("marginAsset") == o.str("quoteAsset"),
            multiplier = BigDecimal.ONE,
            tickSize = tick.stripTrailingZeros(),
            qtyStep = step.stripTrailingZeros(),
            minQty = minQty.stripTrailingZeros(),
            minNotional = minNotional,
            tradingStatus = status,
            displayName = null,
            volumeUnit = o.str("baseAsset"),
        )
    }

    override suspend fun snapshot(instrument: Instrument): MarketSnapshot = coroutineScope {
        if (clock() - offsetAt > 10 * 60_000L) runCatching { ping() }
        val s = instrument.symbol
        val c4 = async { series(s, Timeframe.H4) }
        val c1 = async { series(s, Timeframe.H1) }
        val c15 = async { series(s, Timeframe.M15) }
        val q = async { quote(s) }
        val f = async { runCatching { funding(s) }.getOrNull() }
        val oi = async { runCatching { openInterest(s) }.getOrNull() }
        val btc = if (instrument.base != "BTC") async { runCatching { series("BTCUSDT", Timeframe.H4) }.getOrNull() } else null
        val quote = q.await()
        MarketSnapshot(
            instrument = instrument,
            candles = mapOf(Timeframe.H4 to c4.await(), Timeframe.H1 to c1.await(), Timeframe.M15 to c15.await()),
            quote = quote,
            funding = f.await(),
            openInterest = oi.await(),
            btcContext4h = btc?.await(),
            evaluatedAt = venueNow(),
            source = venueName,
        )
    }

    /**
     * Candles with incremental refresh. A full window is fetched when the cache is empty, when the
     * next expected candle is missing, or when a gap is found (DATA02: fetch missing intervals
     * before analysis; never forward-fill). The in-progress candle is kept for the chart only.
     */
    private suspend fun series(symbol: String, tf: Timeframe): List<Candle> {
        val key = SeriesKey(symbol, tf)
        val now = venueNow()
        val cached = candleCache[key]
        val expectedLastClosedOpen = Math.floorDiv(now, tf.millis) * tf.millis - tf.millis
        val needsFetch = cached == null || cached.none { it.openTime == expectedLastClosedOpen } ||
            DataQuality.validateSeries(DataQuality.closedOnly(cached, now), tf).isNotEmpty()
        val list = if (needsFetch) {
            var fetched = klines(symbol, tf, 499)
            if (DataQuality.validateSeries(DataQuality.closedOnly(fetched, now), tf).isNotEmpty()) {
                fetched = klines(symbol, tf, 499) // one retry for a transient gap
            }
            fetched
        } else {
            // Refresh only the most recent candles and merge by open time.
            val recent = klines(symbol, tf, 3)
            val merged = java.util.TreeMap<Long, Candle>()
            cached!!.forEach { merged[it.openTime] = it }
            recent.forEach { merged[it.openTime] = it }
            merged.values.toList().takeLast(500)
        }
        candleCache[key] = list
        return list
    }

    private suspend fun klines(symbol: String, tf: Timeframe, limit: Int): List<Candle> {
        val arr = get("/fapi/v1/klines", "symbol" to symbol, "interval" to tf.code, "limit" to limit.toString()).jsonArray
        return arr.map { e ->
            val a = e.jsonArray
            val open = a[0].jsonPrimitive.long
            Candle(
                openTime = open,
                closeTime = open + tf.millis, // Binance closeTime is inclusive (open + interval - 1)
                open = a.decAt(1), high = a.decAt(2), low = a.decAt(3), close = a.decAt(4), volume = a.decAt(5),
            )
        }
    }

    private suspend fun quote(symbol: String): Quote = coroutineScope {
        val book = async { get("/fapi/v1/ticker/bookTicker", "symbol" to symbol).jsonObject }
        val price = async { get("/fapi/v1/ticker/price", "symbol" to symbol).jsonObject }
        val premium = async { runCatching { get("/fapi/v1/premiumIndex", "symbol" to symbol).jsonObject }.getOrNull() }
        val b = book.await()
        val p = price.await()
        val received = venueNow()
        Quote(
            bid = b.dec("bidPrice") ?: throw MarketException(ErrorCode.STALE_DATA, "No bid"),
            ask = b.dec("askPrice") ?: throw MarketException(ErrorCode.STALE_DATA, "No ask"),
            last = p.dec("price") ?: throw MarketException(ErrorCode.STALE_DATA, "No last price"),
            mark = premium.await()?.dec("markPrice"),
            sourceTime = b["time"]?.jsonPrimitive?.longOrNull ?: p["time"]?.jsonPrimitive?.longOrNull,
            receivedAt = received,
        )
    }

    private suspend fun funding(symbol: String): Funding? {
        val o = get("/fapi/v1/premiumIndex", "symbol" to symbol).jsonObject
        val rate = o.dec("lastFundingRate") ?: return null
        return Funding(
            rate = rate,
            intervalHours = fundingIntervals[symbol],
            nextFundingTime = o["nextFundingTime"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 },
            sourceTime = o["time"]?.jsonPrimitive?.longOrNull,
            receivedAt = venueNow(),
        )
    }

    private suspend fun openInterest(symbol: String): OpenInterest? {
        val cached = oiCache[symbol]
        if (cached != null && venueNow() - cached.receivedAt < 60_000) return cached
        val o = get("/fapi/v1/openInterest", "symbol" to symbol).jsonObject
        val v = o.dec("openInterest") ?: return null
        // USDⓈ-M open interest is reported in base-asset units (contracts with multiplier 1).
        return OpenInterest(v, "base", o["time"]?.jsonPrimitive?.longOrNull, venueNow()).also { oiCache[symbol] = it }
    }

    private suspend fun get(path: String, vararg params: Pair<String, String>): JsonElement = withContext(Dispatchers.IO) {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply { params.forEach { addQueryParameter(it.first, it.second) } }.build()
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        try {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 429 || resp.code == 418 -> throw MarketException(
                        ErrorCode.RATE_LIMITED, "Rate limited by venue", resp.header("Retry-After")?.toLongOrNull(),
                    )
                    resp.code == 451 || resp.code == 403 -> throw MarketException(ErrorCode.REGION_BLOCKED, "Venue unavailable from this region (HTTP ${resp.code})")
                    resp.code == 400 && text.contains("-1121") -> throw MarketException(ErrorCode.UNKNOWN_INSTRUMENT, "Invalid symbol")
                    !resp.isSuccessful -> throw MarketException(ErrorCode.NETWORK, "HTTP ${resp.code}")
                }
                json.parseToJsonElement(text)
            }
        } catch (e: MarketException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            throw MarketException(ErrorCode.NETWORK, e.message ?: "Network error", cause = e)
        } catch (e: Exception) {
            throw MarketException(ErrorCode.NETWORK, "Unexpected response", cause = e)
        }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.dec(k: String): BigDecimal? = this[k]?.jsonPrimitive?.content?.toBigDecimalOrNull()
    private fun JsonArray.decAt(i: Int): BigDecimal = BigDecimal(this[i].jsonPrimitive.content)
}
