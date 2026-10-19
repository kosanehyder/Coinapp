package com.coinfutures.app.ui.format

import com.coinfutures.app.data.settings.TimeDisplay
import com.coinfutures.app.ui.l10n.Strings
import com.coinfutures.engine.Instrument
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Number and time formatting. Prices use the instrument tick precision (legible price precision). */
object Fmt {
    fun priceScale(i: Instrument?): Int = i?.tickSize?.stripTrailingZeros()?.scale()?.coerceAtLeast(0) ?: 2

    fun price(v: BigDecimal?, i: Instrument?): String =
        v?.setScale(priceScale(i), RoundingMode.HALF_EVEN)?.toPlainString()?.let { group(it) } ?: "—"

    fun price(v: String?, i: Instrument?): String = price(v?.toBigDecimalOrNull(), i)

    fun qty(v: BigDecimal?, i: Instrument?): String {
        if (v == null) return "—"
        val s = i?.qtyStep?.stripTrailingZeros()?.scale()?.coerceAtLeast(0) ?: 3
        return group(v.setScale(s, RoundingMode.DOWN).toPlainString())
    }

    fun money(v: BigDecimal?, scale: Int = 2): String = v?.let { group(it.setScale(scale, RoundingMode.HALF_EVEN).toPlainString()) } ?: "—"

    fun ratio(v: BigDecimal?): String = v?.setScale(2, RoundingMode.HALF_EVEN)?.toPlainString() ?: "—"

    fun percent(v: BigDecimal?, scale: Int = 3): String = v?.let { it.setScale(scale, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString() + "%" } ?: "—"

    /** Funding rate as a percentage, e.g. 0.0001 -> 0.0100%. */
    fun fundingRate(v: BigDecimal?): String = v?.multiply(BigDecimal(100))?.setScale(4, RoundingMode.HALF_EVEN)?.toPlainString()?.plus("%") ?: "—"

    fun compact(v: BigDecimal?): String {
        if (v == null) return "—"
        val d = v.toDouble()
        return when {
            d >= 1e9 -> String.format(Locale.US, "%.2fB", d / 1e9)
            d >= 1e6 -> String.format(Locale.US, "%.2fM", d / 1e6)
            d >= 1e3 -> String.format(Locale.US, "%.2fK", d / 1e3)
            else -> String.format(Locale.US, "%.2f", d)
        }
    }

    fun group(plain: String): String {
        val neg = plain.startsWith("-")
        val body = if (neg) plain.substring(1) else plain
        val intPart = body.substringBefore('.')
        val frac = body.substringAfter('.', "")
        val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "-" else "") + grouped + if (frac.isNotEmpty()) ".$frac" else ""
    }

    private val hm = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val dhm = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.US)
    private val dhms = DateTimeFormatter.ofPattern("dd MMM HH:mm:ss", Locale.US)

    fun zoneLabel(): String = ZoneId.systemDefault().id

    /** Shows UTC and local time (FR03, page 7 "Data time"). */
    fun time(epoch: Long?, mode: TimeDisplay, withSeconds: Boolean = false): String {
        if (epoch == null) return "—"
        val inst = Instant.ofEpochMilli(epoch)
        val f = if (withSeconds) dhms else dhm
        val utc = f.format(inst.atZone(ZoneOffset.UTC)) + " UTC"
        if (mode == TimeDisplay.UTC_ONLY) return utc
        val local = inst.atZone(ZoneId.systemDefault())
        if (local.offset == ZoneOffset.UTC) return utc
        return hm.format(local) + " " + local.zone.id.substringAfterLast('/').replace('_', ' ') + " · " + utc
    }

    fun shortTime(epoch: Long?, mode: TimeDisplay): String {
        if (epoch == null) return "—"
        val inst = Instant.ofEpochMilli(epoch)
        return if (mode == TimeDisplay.UTC_ONLY) dhm.format(inst.atZone(ZoneOffset.UTC)) + " UTC" else dhm.format(inst.atZone(ZoneId.systemDefault()))
    }

    fun ago(s: Strings, from: Long?, now: Long): String {
        if (from == null) return "—"
        val d = ((now - from) / 1000).coerceAtLeast(0)
        return when {
            d < 60 -> s.agoSeconds(d)
            d < 3600 -> s.agoMinutes(d / 60)
            else -> s.agoHours(d / 3600)
        }
    }
}
