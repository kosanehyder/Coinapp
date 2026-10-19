package com.coinfutures.engine

data class SearchCandidate(
    val instrument: Instrument,
    val matchedBy: MatchKind,
) {
    val supported: Boolean get() = instrument.isSupported
}

enum class MatchKind { EXACT_BASE, NAME, SYMBOL_PREFIX, PREFIXED_BASE }

data class SearchResult(
    val query: String,
    val candidates: List<SearchCandidate>,
    /** Non-null only when exactly one supported futures market matches (FR02). */
    val uniqueMarket: Instrument?,
) {
    val noSupportedMarket: Boolean get() = candidates.none { it.supported }
}

/**
 * Coin search (FR01/FR02, AT01). Names and symbols resolve to candidate instruments; a market
 * is never assumed when more than one supported instrument matches.
 */
class InstrumentResolver(
    private val instruments: List<Instrument>,
    /** Lower-case coin name -> base asset, e.g. "bitcoin" -> "BTC". */
    private val names: Map<String, String> = CoinNames.DEFAULT,
) {
    fun search(raw: String, limit: Int = 30): SearchResult {
        val q = normalize(raw)
        if (q.isEmpty()) return SearchResult(raw, emptyList(), null)
        val lower = raw.trim().lowercase()
        val nameBases = names.filter { (name, _) -> name == lower || (lower.length >= 3 && name.startsWith(lower)) }.values.toSet()

        val out = ArrayList<SearchCandidate>()
        for (i in instruments) {
            val kind = when {
                i.base.equals(q, true) -> MatchKind.EXACT_BASE
                i.base in nameBases -> MatchKind.NAME
                stripMultiplierPrefix(i.base).equals(q, true) -> MatchKind.PREFIXED_BASE
                i.displayName?.lowercase()?.startsWith(lower) == true && lower.length >= 3 -> MatchKind.NAME
                i.symbol.startsWith(q, true) -> MatchKind.SYMBOL_PREFIX
                else -> null
            } ?: continue
            out += SearchCandidate(i, kind)
        }
        val sorted = out.sortedWith(
            compareBy<SearchCandidate> { if (it.supported) 0 else 1 }
                .thenBy { it.matchedBy.ordinal }
                .thenBy { it.instrument.symbol.length }
                .thenBy { it.instrument.symbol }
        ).take(limit)
        val strong = sorted.filter { it.supported && it.matchedBy != MatchKind.SYMBOL_PREFIX }
        val unique = strong.singleOrNull()?.instrument
        return SearchResult(raw, sorted, unique)
    }

    companion object {
        fun normalize(raw: String): String {
            var s = raw.trim().uppercase().replace(" ", "").replace("/", "").replace("-", "")
            for (suffix in listOf("PERPETUAL", "PERP", "USDT")) if (s.endsWith(suffix) && s.length > suffix.length) s = s.removeSuffix(suffix)
            return s
        }

        /** "1000PEPE" -> "PEPE" so a user typing PEPE also sees the 1000x contract. */
        fun stripMultiplierPrefix(base: String): String =
            Regex("^(1000000|100000|10000|1000|1M)(.+)$").find(base)?.groupValues?.get(2) ?: base
    }
}

object CoinNames {
    val DEFAULT: Map<String, String> = mapOf(
        "bitcoin" to "BTC", "ethereum" to "ETH", "solana" to "SOL", "ripple" to "XRP", "xrp" to "XRP",
        "binance coin" to "BNB", "bnb" to "BNB", "dogecoin" to "DOGE", "cardano" to "ADA", "tron" to "TRX",
        "avalanche" to "AVAX", "chainlink" to "LINK", "polkadot" to "DOT", "polygon" to "POL", "litecoin" to "LTC",
        "bitcoin cash" to "BCH", "shiba inu" to "SHIB", "toncoin" to "TON", "near protocol" to "NEAR", "near" to "NEAR",
        "uniswap" to "UNI", "aptos" to "APT", "sui" to "SUI", "arbitrum" to "ARB", "optimism" to "OP",
        "pepe" to "PEPE", "stellar" to "XLM", "cosmos" to "ATOM", "filecoin" to "FIL", "internet computer" to "ICP",
        "ethereum classic" to "ETC", "hedera" to "HBAR", "aave" to "AAVE", "injective" to "INJ", "render" to "RENDER",
        "the graph" to "GRT", "algorand" to "ALGO", "fantom" to "FTM", "sonic" to "S", "maker" to "MKR",
        "worldcoin" to "WLD", "bonk" to "BONK", "dogwifhat" to "WIF", "floki" to "FLOKI", "sei" to "SEI",
        "celestia" to "TIA", "stacks" to "STX", "immutable" to "IMX", "kaspa" to "KAS", "ondo" to "ONDO",
        "jupiter" to "JUP", "pendle" to "PENDLE", "ethena" to "ENA", "hyperliquid" to "HYPE", "trump" to "TRUMP",
        "test coin" to "TEST", "test" to "TEST",
    )
}
