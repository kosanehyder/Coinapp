package com.coinfutures.app

import com.coinfutures.app.data.market.BinanceFuturesSource
import com.coinfutures.app.data.market.MarketException
import com.coinfutures.engine.ContractType
import com.coinfutures.engine.ErrorCode
import com.coinfutures.engine.Timeframe
import com.coinfutures.engine.TradingStatus
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

/** Adapter normalization against recorded-shape responses (no network). */
class BinanceFuturesSourceTest {
    private lateinit var server: MockWebServer
    private val now = 1_767_225_600_000L + 400L * 3_600_000L + 5_000L

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/fapi/v1/time") -> ok("""{"serverTime":$now}""")
                    path.startsWith("/fapi/v1/exchangeInfo") -> ok(EXCHANGE_INFO)
                    path.startsWith("/fapi/v1/fundingInfo") -> ok("""[{"symbol":"BTCUSDT","fundingIntervalHours":8}]""")
                    path.startsWith("/fapi/v1/klines") -> ok(klines(path))
                    path.startsWith("/fapi/v1/ticker/bookTicker") -> ok("""{"symbol":"BTCUSDT","bidPrice":"100.00","askPrice":"100.01","time":$now}""")
                    path.startsWith("/fapi/v1/ticker/price") -> ok("""{"symbol":"BTCUSDT","price":"100.00","time":$now}""")
                    path.startsWith("/fapi/v1/premiumIndex") -> ok("""{"symbol":"BTCUSDT","markPrice":"100.02","lastFundingRate":"0.00010000","nextFundingTime":${now + 3_600_000},"time":$now}""")
                    path.startsWith("/fapi/v1/openInterest") -> ok("""{"symbol":"BTCUSDT","openInterest":"12345.678","time":$now}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun klines(path: String): String {
        val interval = Regex("interval=([0-9a-z]+)").find(path)!!.groupValues[1]
        val tf = Timeframe.entries.first { it.code == interval }
        val limit = Regex("limit=(\\d+)").find(path)!!.groupValues[1].toInt()
        val lastOpen = Math.floorDiv(now, tf.millis) * tf.millis // in-progress candle
        return (limit - 1 downTo 0).joinToString(",", "[", "]") { k ->
            val o = lastOpen - k * tf.millis
            """[$o,"100.0","101.0","99.0","100.5","10.0",${o + tf.millis - 1},"1000",10,"5","500","0"]"""
        }
    }

    private fun source() = BinanceFuturesSource(OkHttpClient(), server.url("/").toString().trimEnd('/'), clock = { now })

    @Test fun parsesInstrumentsAndDistinguishesContracts() = runTest {
        val list = source().instruments()
        val perp = list.first { it.symbol == "BTCUSDT" }
        assertEquals(ContractType.PERPETUAL, perp.contractType)
        assertTrue(perp.isSupported)
        assertEquals(0, BigDecimal("0.1").compareTo(perp.tickSize))
        assertEquals(0, BigDecimal("0.001").compareTo(perp.qtyStep))
        val quarterly = list.first { it.symbol == "BTCUSDT_261225" }
        assertEquals(ContractType.DELIVERY, quarterly.contractType)
        val delisted = list.first { it.symbol == "OLDUSDT" }
        assertEquals(TradingStatus.SETTLING, delisted.tradingStatus)
    }

    @Test fun snapshotNormalizesCandleTimesAndQuote() = runTest {
        val src = source()
        src.ping()
        val inst = src.instruments().first { it.symbol == "BTCUSDT" }
        val snap = src.snapshot(inst)
        val c = snap.candles.getValue(Timeframe.H1)
        assertEquals(499, c.size)
        assertTrue(c.all { it.closeTime - it.openTime == Timeframe.H1.millis })
        assertEquals(0, BigDecimal("100.01").compareTo(snap.quote!!.ask))
        assertEquals(0, BigDecimal("0.00010000").compareTo(snap.funding!!.rate))
        assertEquals(8, snap.funding!!.intervalHours)
    }

    @Test fun rateLimitMapsToStableErrorCode() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(429).setHeader("Retry-After", "7")
        }
        try {
            source().instruments(forceRefresh = true)
            fail("expected exception")
        } catch (e: MarketException) {
            assertEquals(ErrorCode.RATE_LIMITED, e.code)
            assertEquals(7L, e.retryAfterSeconds)
        }
    }

    companion object {
        private fun sym(symbol: String, base: String, type: String, status: String) = """
            {"symbol":"$symbol","pair":"${base}USDT","contractType":"$type","status":"$status","baseAsset":"$base","quoteAsset":"USDT","marginAsset":"USDT",
             "filters":[{"filterType":"PRICE_FILTER","tickSize":"0.10"},{"filterType":"LOT_SIZE","stepSize":"0.001","minQty":"0.001"},{"filterType":"MIN_NOTIONAL","notional":"100"}]}
        """.trimIndent()

        val EXCHANGE_INFO = """{"symbols":[${listOf(
            sym("BTCUSDT", "BTC", "PERPETUAL", "TRADING"),
            sym("BTCUSDT_261225", "BTC", "CURRENT_QUARTER", "TRADING"),
            sym("OLDUSDT", "OLD", "PERPETUAL", "SETTLING"),
        ).joinToString(",")}]}"""
    }
}
