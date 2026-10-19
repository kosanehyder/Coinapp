package com.coinfutures.app.domain

import com.coinfutures.app.data.db.PlanDao
import com.coinfutures.app.data.db.PlanRevisionEntity
import com.coinfutures.engine.AlertCandidate
import com.coinfutures.engine.AlertRules
import com.coinfutures.engine.AnalysisResult
import com.coinfutures.engine.PlanState
import com.coinfutures.engine.TradePlan
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class TrackOutcome(val revision: Int?, val candidates: List<AlertCandidate>)

/**
 * Persists every plan revision and state transition (ARCH02) and emits transition-based alert
 * candidates. Idempotent: a unique (planKey, revision) index plus an in-process mutex means
 * overlapping foreground/background runs cannot create duplicate revisions (ARCH03).
 */
class PlanTracker(private val dao: PlanDao, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()

    suspend fun record(result: AnalysisResult): TrackOutcome = mutex.withLock {
        val plan = result.plan ?: return@withLock TrackOutcome(null, emptyList())
        val latest = dao.latest(plan.planKey)
        // Data Unavailable suspends transitions: nothing new is recorded or alerted (section 8).
        if (!result.qualityOk) return@withLock TrackOutcome(latest?.revision, emptyList())

        val previousForInstrument = dao.latestForInstrument(plan.instrumentId)
        if (previousForInstrument != null && previousForInstrument.planKey != plan.planKey && !isClosed(previousForInstrument.state)) {
            dao.insert(
                previousForInstrument.copy(
                    id = 0, revision = previousForInstrument.revision + 1, parentId = previousForInstrument.id,
                    state = SUPERSEDED, actionable = false, reasonCode = "SUPERSEDED", recordedAt = clock(),
                    snapshotTime = result.snapshotTime, analysisId = result.analysisId,
                )
            )
        }

        if (latest == null) {
            dao.insert(entity(plan, result, 1, null))
            val c = AlertRules.planTransition(null, false, plan, 1)
            return@withLock TrackOutcome(1, listOfNotNull(c))
        }
        // Cancelled, expired or superseded plans never reactivate.
        if (isClosed(latest.state)) return@withLock TrackOutcome(latest.revision, emptyList())
        if (latest.state == plan.state.name && latest.actionable == plan.actionable) {
            return@withLock TrackOutcome(latest.revision, emptyList())
        }
        val rev = latest.revision + 1
        val id = dao.insert(entity(plan, result, rev, latest.id))
        if (id == -1L) return@withLock TrackOutcome(rev, emptyList())
        val prevState = runCatching { PlanState.valueOf(latest.state) }.getOrNull()
        TrackOutcome(rev, listOfNotNull(AlertRules.planTransition(prevState, latest.actionable, plan, rev)))
    }

    private fun isClosed(state: String) = state == SUPERSEDED || state == PlanState.EXPIRED.name || state == PlanState.INVALIDATED.name

    private fun entity(p: TradePlan, r: AnalysisResult, revision: Int, parent: Long?) = PlanRevisionEntity(
        planKey = p.planKey,
        revision = revision,
        parentId = parent,
        instrumentId = p.instrumentId,
        symbol = r.instrument.symbol,
        strategyVersion = p.strategyVersion,
        direction = p.direction.name,
        state = p.state.name,
        actionable = p.actionable,
        reasonCode = (p.terminalReason ?: p.gateFailures.firstOrNull())?.name,
        createdAt = p.createdAt,
        stateChangedAt = p.stateChangedAt,
        recordedAt = clock(),
        snapshotTime = r.snapshotTime,
        analysisId = r.analysisId,
        zoneLower = p.zone.lower.toPlainString(),
        zoneUpper = p.zone.upper.toPlainString(),
        entryLower = p.entryBand?.lower?.toPlainString(),
        entryUpper = p.entryBand?.upper?.toPlainString(),
        stop = p.stop?.toPlainString(),
        tp1 = p.targets.getOrNull(0)?.price?.toPlainString(),
        tp2 = p.targets.getOrNull(1)?.price?.toPlainString(),
        validUntil = p.validUntil,
    )

    companion object {
        const val SUPERSEDED = "SUPERSEDED"
    }
}
