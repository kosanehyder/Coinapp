package com.coinfutures.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import com.coinfutures.app.data.db.AppDatabase
import com.coinfutures.app.data.market.BinanceFuturesSource
import com.coinfutures.app.data.market.DemoMarketSource
import com.coinfutures.app.data.market.Venues
import com.coinfutures.app.data.repo.ActivityRepository
import com.coinfutures.app.data.repo.MarketRepository
import com.coinfutures.app.data.settings.SecureStore
import com.coinfutures.app.data.settings.SettingsRepository
import com.coinfutures.app.domain.AlertDispatcher
import com.coinfutures.app.domain.PlanTracker
import com.coinfutures.app.notifications.AlertWorker
import com.coinfutures.app.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Manual dependency container: small, explicit and free of reflection. */
class AppContainer(context: Context) {
    val app: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val db: AppDatabase = AppDatabase.build(context)
    val settings = SettingsRepository(context, SecureStore())
    val notifier = Notifier(context)

    val binance = BinanceFuturesSource(http, BuildConfig.BINANCE_FAPI_BASE_URL)
    val demo = DemoMarketSource()

    private val tracker = PlanTracker(db.plans())
    private val dispatcher = AlertDispatcher(db.alerts(), settings, notifier)

    val markets = MarketRepository(mapOf(Venues.BINANCE_USDM to binance, Venues.DEMO to demo), db, tracker, dispatcher)
    val activity = ActivityRepository(db, markets, dispatcher)
}

class CoinFuturesApp : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.appScope.launch { syncBackgroundWork() }
    }

    /** Background checks run only while notifications are on and something is being watched. */
    suspend fun syncBackgroundWork() {
        val on = container.settings.current().notificationsEnabled && container.activity.hasEnabledAlerts()
        if (on) AlertWorker.schedule(this) else AlertWorker.cancel(this)
    }
}
