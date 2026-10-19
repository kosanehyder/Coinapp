package com.coinfutures.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.coinfutures.app.data.market.Venues
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class AppLanguage(val code: String) { ENGLISH("en"), ROMAN_URDU("ur-Latn") }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class TimeDisplay { LOCAL_AND_UTC, UTC_ONLY }

/** User risk profile defaults (section 7 "User risk profile"). Decimal values stored as strings. */
data class RiskDefaults(
    val riskPercent: String = "0.5",
    val leverage: String = "3",
    val feePercent: String = "0.05",
    val slippagePercent: String = "0.02",
    val rememberEquity: Boolean = false,
    val equity: String? = null,
)

data class AppSettings(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val venue: String = Venues.BINANCE_USDM,
    val notificationsEnabled: Boolean = false,
    val timeDisplay: TimeDisplay = TimeDisplay.LOCAL_AND_UTC,
    val strategyProfile: String = "intraday-v1.0.0",
    val disclaimerAccepted: Boolean = false,
    val risk: RiskDefaults = RiskDefaults(),
)

class SettingsRepository(private val context: Context, private val secure: SecureStore) {

    private object K {
        val language = stringPreferencesKey("language")
        val theme = stringPreferencesKey("theme")
        val venue = stringPreferencesKey("venue")
        val notifications = booleanPreferencesKey("notifications")
        val timeDisplay = stringPreferencesKey("time_display")
        val profile = stringPreferencesKey("strategy_profile")
        val disclaimer = booleanPreferencesKey("disclaimer_v1")
        val riskPct = stringPreferencesKey("risk_pct")
        val leverage = stringPreferencesKey("leverage")
        val fee = stringPreferencesKey("fee_pct")
        val slippage = stringPreferencesKey("slippage_pct")
        val rememberEquity = booleanPreferencesKey("remember_equity")
        val equityEnc = stringPreferencesKey("equity_enc")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            language = p[K.language]?.let { v -> AppLanguage.entries.firstOrNull { it.name == v } } ?: AppLanguage.ENGLISH,
            theme = p[K.theme]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: ThemeMode.SYSTEM,
            venue = p[K.venue] ?: Venues.BINANCE_USDM,
            notificationsEnabled = p[K.notifications] ?: false,
            timeDisplay = p[K.timeDisplay]?.let { v -> TimeDisplay.entries.firstOrNull { it.name == v } } ?: TimeDisplay.LOCAL_AND_UTC,
            strategyProfile = p[K.profile] ?: "intraday-v1.0.0",
            disclaimerAccepted = p[K.disclaimer] ?: false,
            risk = RiskDefaults(
                riskPercent = p[K.riskPct] ?: "0.5",
                leverage = p[K.leverage] ?: "3",
                feePercent = p[K.fee] ?: "0.05",
                slippagePercent = p[K.slippage] ?: "0.02",
                rememberEquity = p[K.rememberEquity] ?: false,
                equity = p[K.equityEnc]?.let { secure.decrypt(it) },
            ),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setLanguage(v: AppLanguage) = context.dataStore.edit { it[K.language] = v.name }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setVenue(v: String) = context.dataStore.edit { it[K.venue] = v }
    suspend fun setNotifications(v: Boolean) = context.dataStore.edit { it[K.notifications] = v }
    suspend fun setTimeDisplay(v: TimeDisplay) = context.dataStore.edit { it[K.timeDisplay] = v.name }
    suspend fun acceptDisclaimer() = context.dataStore.edit { it[K.disclaimer] = true }

    suspend fun setRiskDefaults(riskPct: String, leverage: String, feePct: String, slippagePct: String) =
        context.dataStore.edit {
            it[K.riskPct] = riskPct
            it[K.leverage] = leverage
            it[K.fee] = feePct
            it[K.slippage] = slippagePct
        }

    /** Equity is stored only when the user opts in, encrypted with a Keystore key (SEC03). */
    suspend fun setRememberEquity(remember: Boolean, equity: String?) = context.dataStore.edit {
        it[K.rememberEquity] = remember
        if (remember && !equity.isNullOrBlank()) it[K.equityEnc] = secure.encrypt(equity) else it.remove(K.equityEnc)
    }

    suspend fun clearAll() {
        context.dataStore.edit { it.clear() }
        secure.deleteKey()
    }
}
