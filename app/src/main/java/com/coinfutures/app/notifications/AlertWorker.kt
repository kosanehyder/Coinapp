package com.coinfutures.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.coinfutures.app.CoinFuturesApp
import com.coinfutures.app.data.market.MarketException
import com.coinfutures.engine.ErrorCode
import java.util.concurrent.TimeUnit

/**
 * Periodic background re-analysis for markets with alerts and for open paper trades.
 * Android limits periodic work to >= 15 minutes; production push latency targets (p95 10 s)
 * require the backend + push provider described in section 7.
 */
class AlertWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = (applicationContext as CoinFuturesApp).container
        if (!c.settings.current().notificationsEnabled) return Result.success()
        var retry = false
        for (alert in c.db.alerts().enabled()) {
            try {
                c.markets.analyze(c.markets.instrument(alert.instrumentId))
            } catch (e: MarketException) {
                if (e.code == ErrorCode.NETWORK || e.code == ErrorCode.RATE_LIMITED) retry = true
            } catch (_: Exception) {
                // Never crash the worker; the next period retries.
            }
        }
        for (trade in c.db.paper().open()) {
            runCatching { c.activity.trackPaper(trade, dispatch = true) }
        }
        return if (retry && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "coinfutures-alerts"

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
