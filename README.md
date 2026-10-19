# Coin Futures — Android MVP

Android app (Kotlin, Jetpack Compose, Material 3) built from *Coin Futures App Requirements v1.0 (18 Oct 2026)*.
A user types a coin name, picks the exact USDT-margined perpetual market, and gets explainable
support/resistance zones, a conditional long or short plan (entry band, stop, two targets), or a
clear **Wait / No Trade / Data Unavailable** with the reason. The app never places orders.

> Analysis only — not financial advice. Thresholds are the document's proposed engineering defaults
> and are not evidence of a profitable strategy. Release requires out-of-sample evaluation (section 10).

---

## 1. Project layout

```
CoinFutures/
├── engine/                     Pure Kotlin/JVM deterministic analysis engine (no Android deps)
│   ├── src/main/kotlin/com/coinfutures/engine/
│   │   ├── Model.kt            Candle, Instrument, Quote, Funding, OI, snapshot, states, reason/error codes
│   │   ├── Dec.kt              BigDecimal helpers, tick/step floor & ceiling rounding (ARCH01)
│   │   ├── StrategyConfig.kt   Every parameter + strategy_version "intraday-v1.0.0"
│   │   ├── Indicators.kt       EMA (SMA seed), Wilder ATR14 / RSI14, 20-candle volume mean
│   │   ├── Pivots.kt           Strict 2-left/2-right pivots with pivot_time + confirmed_at
│   │   ├── Zones.kt            Bands, union-merge, distinct visits, ranking, roles, break flags
│   │   ├── DataQuality.kt      DATA01–DATA05 validation & freshness, limitations
│   │   ├── Bias.kt             4H direction filter
│   │   ├── SetupFinder.kt      Breakout → retest → confirmation → entry-ready lifecycle, gates
│   │   ├── AnalysisEngine.kt   Pipeline → AnalysisResult (status, reasons, next condition, plan)
│   │   ├── Risk.kt             Cost-aware position sizing (RISK01–RISK03)
│   │   ├── InstrumentResolver  Coin search / ambiguity (FR01, FR02)
│   │   ├── PaperTracking.kt    Paper-trade touches, ambiguity, review events (EXIT02–EXIT04)
│   │   ├── AlertRules.kt       Dedup keys, transitions, zone-approach cooldown (ALERT01–04)
│   │   └── demo/Fixtures.kt    Deterministic fictional TESTUSDT fixtures (tests + Demo venue)
│   └── src/test/kotlin/        Acceptance tests AT01–AT12 (+ indicator/zone unit tests)
├── app/                        Android application
│   └── src/main/java/com/coinfutures/app/
│       ├── data/market/        Binance USDⓈ-M adapter, Demo (simulated) adapter
│       ├── data/db/            Room: markets, plan revisions, alerts, events, zone state, paper trades
│       ├── data/settings/      DataStore settings, Android-Keystore AES-GCM secure store
│       ├── data/repo/          MarketRepository (fetch → engine → persist → alerts), ActivityRepository
│       ├── domain/             PlanTracker (revisions, ARCH02/03), AlertDispatcher (ALERT01–05)
│       ├── notifications/      Notifier (channels, deep links), AlertWorker (WorkManager)
│       └── ui/                 Compose screens, theme, chart, l10n (English + Roman Urdu)
└── .github/workflows/android.yml   CI: tests + debug/release APK artifacts
```

Architecture: **UI (Compose + ViewModel/StateFlow) → repositories → data sources / Room / DataStore**,
with the **engine** as the single place a signal is computed. The engine is a plain JVM module so the same
code can run unchanged inside the typed backend proposed in section 7; the app's `MarketRepository` is the
only seam that would switch from on-device evaluation to `GET /v1/analysis/{instrument_id}`.

## 2. Screens and flows (section 1)

| Screen | What it does |
|---|---|
| **Markets (Home)** | Search entry, watchlist (saved), recently viewed, venue connection status + latency, last analysis time, pull-to-refresh, demo shortcut |
| **Search** | Autocomplete by name or symbol (e.g. *Bitcoin*, *BTC*, *pepe* → 1000PEPE). Shows venue, base/quote, contract type, status. Unsupported (quarterly, delisted, non-USDT) rows are shown but disabled with the reason. Duplicate symbols require a choice. "No supported futures market found" otherwise. Enter opens the market only when one supported match exists (FR02). |
| **Analysis** | Status hero (Long setup / Short setup / Wait / No trade / Data unavailable, icon + text + color), plain-language reason, supporting reasons, next required condition, plan summary sentence; market header with exact symbol, contract, venue, last/mark/bid/ask, quote age, snapshot and last-closed candle times in local + UTC; trade-plan card (entry band, stop, TP1/TP2 with 50/50 allocation, gross/net/blended R:R, spread, validity, cancellation conditions, "do not chase"); chart; zones with visits & recency; market context (4H bias, BTC 4H context, funding, OI, spread); limitations ("News not checked", "Liquidation not calculated" …); expandable technical breakdown (strategy version, analysis id, EMAs, ATRs, RSI, pivots, breakout/retest/confirmation evidence, data issues). Live refresh every 5 s while visible. |
| **Chart** | Custom Canvas candles for 4H / 1H / 15M; support/resistance bands labelled S1–S3 / R1–R3, entry band, SL and TP lines with values, last-price tag; pinch-zoom, horizontal pan, tap a candle for exact OHLCV + time. Up candles hollow, down candles filled (not color-only). |
| **Risk calculator** | Optional. Equity, risk %, entry (defaults to least favourable band price), leverage, available margin, fee %, slippage %, funding debit. Shows quantity (step-rounded down), notional, margin estimate, planned loss, per-coin cost breakdown, per-target outcomes, gross/net/blended R:R, single-exit fallback, "Liquidation not calculated". Rejects obsolete/terminal plans. |
| **Activity** | Tabs: Alerts (per-market toggle, event types, delete), Events (history with delivery status: Sent to system / Opened / Not sent + reason), Plans (every revision with frozen levels and state), Paper (manually recorded trades). |
| **Paper trade** | Record entry against a plan revision (never implies a fill), record partial exits, observed target/stop touches, Ambiguous bars, "Review time reached" / "Review direction changed", close or delete. |
| **Settings** | Language (English / Roman Urdu), theme, notifications (runtime permission on Android 13+), exchange (Binance USDⓈ-M or Demo), strategy profile (Intraday v1; swing = later), risk defaults, time display (local + UTC or UTC only) and device zone, privacy statement, delete all local data, about/version. |

First launch shows a non-dismissable disclaimer (analysis, not advice; no orders).

## 3. Requirement coverage

| Requirement | Where |
|---|---|
| FR01 duplicates / delisted / spot, unknown → "No supported futures market found" | `InstrumentResolver`, `Instrument.unsupportedReason`, `SearchScreen` |
| FR02 coin name only when unique | `SearchResult.uniqueMarket`, IME *Search* action |
| FR03 venue, symbol, contract, TF, last closed candle, freshness | `MarketHeader` |
| FR04 sizing optional | Analysis never requires equity; `RiskScreen` |
| FR05 short + technical explanation, not color-only | `Strings.reason`, `TechnicalCard`, `StatusBadge` |
| DATA01–DATA05 | `DataQuality`, adapter backfill/one retry, live candle excluded from structure |
| Steps 1–4, ZONE01–ZONE04, frozen plan levels | `Indicators`, `Pivots`, `Zones`, `SetupFinder` (zones/ATR frozen at the breakout, retest + 15M ATR frozen at the retest) |
| Direction filter, ENT01–ENT04, short inverse, mandatory gates, Wait vs No Trade | `Bias`, `SetupFinder`, `AnalysisEngine` |
| Stops, TP1/TP2, EXIT01–EXIT04 | `SetupFinder`, `RiskCalculator`, `PaperTracker` |
| RISK01–RISK03 | `RiskCalculator`, `RiskScreen` |
| Records (section 7), ARCH01–ARCH03 | Room entities (decimal strings), `PlanTracker` (revisions, unique index, mutex) |
| ARCH04 | No language model is used; all text is templated from structured results |
| Lifecycle & precedence (section 8) | `PlanState`, `PlanTracker` (cancelled/expired/superseded never reactivate; Data Unavailable suspends transitions) |
| Error codes (section 8) | `ErrorCode` + localized messages and retry guidance |
| ALERT01–ALERT05 | `AlertRules`, `AlertDispatcher`, `Notifier`, `AlertWorker`, deep link + superseded banner |
| SEC01–SEC03 | Guest only; no keys/credentials; TLS-only network config; Keystore-encrypted optional equity; backups disabled; delete-all |
| Accessibility | Dynamic type (sp), 48 dp targets, content descriptions, headings, tabular numerals, non-color state labels, light/dark themes |
| AT01–AT12 | `engine/src/test` (28 tests). AT13/AT14: see section 7 below |

## 4. Running the project

Requirements: **Android Studio Ladybug (2024.2) or newer**, **JDK 17**, Android SDK 35. Internet access to
Google Maven / Maven Central for the first sync.

```bash
git clone <your repo> && cd CoinFutures
./gradlew :engine:test              # deterministic engine + acceptance tests
./gradlew :app:testDebugUnitTest    # adapter + localization tests
./gradlew :app:assembleDebug        # APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # install on a connected device/emulator
```

Or open the folder in Android Studio and press **Run**. Min SDK 26 (Android 8.0), target/compile SDK 35.

**Release APK:** `./gradlew :app:assembleRelease` (R8 minify + resource shrinking). To sign, set
`CF_KEYSTORE_PATH`, `CF_KEYSTORE_PASSWORD`, `CF_KEY_ALIAS`, `CF_KEY_PASSWORD` in the environment
(no secret is committed). Without them the output is `app-release-unsigned.apk`.

**CI:** push to GitHub — `.github/workflows/android.yml` runs both test suites and uploads the debug and
release APKs as the `coin-futures-apk` artifact.

### Trying it without a network
Settings → Exchange → **Demo (simulated)**. Search `TEST`, `SHORTY`, `RETEST`, `CONFIRM`, `WATCH`, `RANGE`,
`NOTGT`, `WIDE`, `EXPIRED`, `STALE`, `DUP`, `OLD` to see Long setup, Short setup, Waiting retest / confirmation,
Watching breakout, mixed structure, No trade (missing targets / wide spread), expired entry, Data unavailable,
a duplicate symbol that needs a selection, a quarterly contract and a delisted market. Every demo screen is
labelled *Simulated data*.

## 5. Configuration and API integration

| Item | Value |
|---|---|
| Venue | Binance USDⓈ-M futures, public REST, base URL `BuildConfig.BINANCE_FAPI_BASE_URL` (`https://fapi.binance.com`) |
| Endpoints | `/fapi/v1/time`, `/exchangeInfo`, `/fundingInfo`, `/klines` (499 per TF, then 3-candle incremental merge), `/ticker/bookTicker`, `/ticker/price`, `/premiumIndex`, `/openInterest` |
| Keys | None. No trading keys, no account data |
| Clock | Server offset measured from `/time` every 10 min; freshness uses the venue clock |
| Errors | 429/418 → `RATE_LIMITED` (+ Retry-After), 451/403 → `REGION_BLOCKED`, invalid symbol → `UNKNOWN_INSTRUMENT`, I/O → `NETWORK` |
| Strategy | `StrategyConfig.DEFAULT` (`intraday-v1.0.0`). Any change must bump `version` and re-run tests |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS` (requested only when the user enables alerts) |
| Main libraries | Compose BOM 2024.12.01, Material 3, Navigation 2.8.5, Lifecycle 2.8.7, Room 2.6.1 (KSP), WorkManager 2.10.0, DataStore 1.1.1, OkHttp 4.12, kotlinx-serialization 1.7.3, Kotlin 2.0.21, AGP 8.7.3 |

**Provider verification is still required** (section 2): field names, weights/rate limits, usage rights and
regional availability of Binance endpoints must be checked against current documentation before release.
If Binance is not available in the target region, implement another `MarketDataSource` (e.g. Bybit v5 linear)
— nothing else changes.

## 6. Assumptions and decisions

1. **Android only, native Kotlin/Compose.** The request asked for Android with Kotlin + Compose; the document
   proposes Flutter/React Native for Android + iOS. iOS is out of scope here; the engine module is portable to
   a Kotlin backend or Kotlin Multiplatform.
2. **No backend in this delivery.** The engine runs on-device inside `MarketRepository`, but it is the same
   deterministic module the backend would host, so there is still exactly one signal implementation. The
   `/v1/*` API contract, PostgreSQL/Redis/job-queue, FCM push, accounts and sync are backend work to follow.
3. **Guest mode only.** All records are local and owner-scoped (`ownerId`), excluded from backups. "Account
   deletion" = *Delete all local data*.
4. **Alerts are local.** WorkManager runs about every 15 minutes (Android minimum) while notifications are on and
   something is watched; live 5-second refresh happens while a market is open. The p95 10-second dispatch target
   needs the backend + push provider. Delivery status distinguishes *Sent to system* from *Opened*; true device
   delivery is not observable locally.
5. **Freezing points.** Zones and 1H ATR are frozen from the closed snapshot before the breakout candle; retest
   high/low and the 15M ATR used for stop and entry band are frozen at the retest candle; targets come from the
   frozen zones.
6. **Gate costs.** The TP2 ≥ 2.0 net R:R gate uses 0.05 % fee and 0.02 % slippage per side (configurable in
   `StrategyConfig.gateCosts`). The risk calculator uses the user's own cost inputs.
7. **Spread gate** is evaluated on the live quote while Entry Ready; a temporary wide spread shows No Trade with
   that reason and recovers when the spread narrows (the frozen plan is unchanged).
8. **Visits.** The first intersection in the 200-candle window counts as a visit; later visits need at least three
   non-intersecting candles in between.
9. **Liquidation.** No exact liquidation is shown ("Liquidation not calculated"). The calculator does reject a
   stop farther than 1/leverage (the position would lose its whole initial margin before the stop).
10. **Quote freshness** uses the time the venue answered the REST request (the book's own timestamp is stored as
    source time); a quote older than 10 s blocks new signals.
11. **Funding interval** comes from `/fundingInfo` when Binance lists the symbol there; otherwise it is shown as
    not provided rather than assumed.
12. **Roman Urdu** is selected in-app (it is not an Android locale). Full Urdu RTL is a later release.

### Inconsistency found in the document
The page 7 example says entry band 100.00–100.25 (0.25 × 15M ATR ⇒ ATR = 1.00) and stop buffer 0.50
(0.25 × 15M ATR ⇒ ATR = 2.00). Both are defined from the same frozen 15M ATR, so they cannot both hold.
The engine follows the formulas; the **AT09** risk fixture (entry 100.25, stop 98.00) reproduces the document's
numbers exactly: 2.092 coins, planned loss ≤ 5 USDT, notional ≈ 209.72, margin ≈ 69.91, TP2 net R:R ≈ 3.18,
50/50 blend ≈ 2.35. Please confirm which value the owner intended.

## 7. Testing status and limitations

* `engine`: **28 tests pass** (compiled and run with Kotlin 2.0.21): indicators, pivots (AT02), zone merging and
  visits, AT01 ambiguity, AT03 replay, AT04 wick, AT05 mirrored symmetry (levels, side-specific rounding,
  targets), AT06 gates, AT07 stale/gap/missing quote/overdue candle, AT08 expiry, AT09 example, AT10 limits,
  AT11 ambiguity, AT12 dedup, plus lifecycle, invalidation, live-candle and leverage tests.
* `app` unit tests: Binance adapter normalization and error mapping (MockWebServer), and a localization test for
  every state/reason/error in both languages (AT14, partial).
* **The Android module was written in an environment without the Android SDK or Google Maven**, so it has not
  been compiled there. Run the CI workflow (or Android Studio) to build; fix-ups, if any, should be small.
* AT13 (authorization between users) applies to the future backend; locally every query is scoped to the single
  guest owner.
* Not done (needs owner decisions or backend): historical replay/out-of-sample evaluation report, load tests,
  security review, real-device beta, store assets, analytics dictionary, iOS.
