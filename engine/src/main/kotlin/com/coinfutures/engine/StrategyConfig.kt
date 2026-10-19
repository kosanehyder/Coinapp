package com.coinfutures.engine

import java.math.BigDecimal

/**
 * Every tunable parameter of the deterministic baseline, stored with [version].
 * Changing any value must create a new version and requires regression testing (section 3).
 * Values are the proposed engineering defaults from the requirements document.
 */
data class StrategyConfig(
    val version: String = "intraday-v1.0.0",
    val profileName: String = "Intraday (4H / 1H / 15M)",
    // Indicators
    val emaFast: Int = 20,
    val emaMid: Int = 50,
    val emaSlow: Int = 200,
    val rsiPeriod: Int = 14,
    val atrPeriod: Int = 14,
    val volumeMeanPeriod: Int = 20,
    // Data
    val minClosedCandles: Int = 300,
    val quoteMaxAgeMs: Long = 10_000,
    val candleGraceMs: Long = 30_000,
    val fundingMaxAgeMs: Long = 5 * 60_000,
    val oiMaxAgeMs: Long = 10 * 60_000,
    // Pivots and zones
    val pivotLeft: Int = 2,
    val pivotRight: Int = 2,
    val zoneLookback1h: Int = 200,
    val zoneHalfWidthAtr: BigDecimal = BigDecimal("0.25"),
    val zoneMinHalfWidthTicks: Int = 2,
    val visitGapCandles: Int = 3,
    val zonesPerSide: Int = 3,
    val breakBufferAtr: BigDecimal = BigDecimal("0.10"),
    // Entry
    val breakoutLookback1h: Int = 6,
    val breakoutVolumeMultiple: BigDecimal = BigDecimal("1.2"),
    val retestWindow15m: Int = 8,
    val confirmWindow15m: Int = 4,
    val entryWindow15m: Int = 4,
    val rsiThreshold: BigDecimal = BigDecimal(50),
    val entryBandAtr: BigDecimal = BigDecimal("0.25"),
    val stopBufferAtr: BigDecimal = BigDecimal("0.25"),
    // Gates
    val maxSpreadPercent: BigDecimal = BigDecimal("0.10"),
    val minNetRrTp2: BigDecimal = BigDecimal("2.0"),
    val gateCosts: CostModel = CostModel(
        feeRate = BigDecimal("0.0005"),
        slippage = Slippage.Percent(BigDecimal("0.0002")),
        fundingDebitPerCoin = BigDecimal.ZERO,
    ),
    // Exits and review
    val tp1Allocation: BigDecimal = BigDecimal("0.5"),
    val reviewAfter15m: Int = 16,
    // Alerts
    val zoneApproachCooldownMs: Long = 15 * 60_000,
) {
    companion object {
        val DEFAULT = StrategyConfig()
    }
}
