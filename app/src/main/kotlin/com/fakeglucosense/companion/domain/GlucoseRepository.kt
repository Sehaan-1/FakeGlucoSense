package com.fakeglucosense.companion.domain

import com.fakeglucosense.companion.data.db.GlucoseDao
import com.fakeglucosense.companion.data.db.toEntity
import com.fakeglucosense.companion.data.db.toReading
import com.fakeglucosense.core.analysis.TrendCalculator
import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.model.TimeRange
import com.fakeglucosense.core.model.Trend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single entry point for stored readings. Sources push into [ingest]. The UI and workers
 * read through the observe functions. Nothing else touches the DAO.
 */
class GlucoseRepository(
    private val dao: GlucoseDao,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * Stores readings and returns only the ones that were new. Replayed readings are ignored, so
     * alerts never fire twice for the same sample. A reading with no trend from the sensor gets one
     * derived from the 15 minutes before it.
     */
    suspend fun ingest(incoming: List<GlucoseReading>, source: String): List<GlucoseReading> {
        if (incoming.isEmpty()) return emptyList()
        val prepared = incoming
            .sortedBy { it.epochMillis }
            .map { reading ->
                if (reading.trend == Trend.NOT_COMPUTABLE && reading.rateMgdlPerMin == null) {
                    withDerivedTrend(reading)
                } else {
                    reading
                }
            }
        val ids = dao.insertAll(prepared.map { it.toEntity(source) })
        return prepared.filterIndexed { index, _ -> ids[index] != IGNORED_ROW }
    }

    /** Readings from the last [range] hours, ascending, updated whenever the table changes. */
    fun observeWindow(range: TimeRange): Flow<List<GlucoseReading>> =
        dao.observeSince(nowMillis() - range.millis).map { rows -> rows.map { it.toReading() } }

    fun observeLatest(): Flow<GlucoseReading?> = dao.observeLatest().map { it?.toReading() }

    suspend fun latest(): GlucoseReading? = dao.latest()?.toReading()

    suspend fun purgeOlderThan(cutoffMillis: Long): Int = dao.deleteOlderThan(cutoffMillis)

    private suspend fun withDerivedTrend(reading: GlucoseReading): GlucoseReading {
        val from = reading.epochMillis - TrendCalculator.WINDOW_MILLIS
        val history = dao.between(from, reading.epochMillis).map { it.toReading() }
        val estimate = TrendCalculator.estimate(history + reading, reading.epochMillis)
        return reading.copy(trend = estimate.trend, rateMgdlPerMin = estimate.rateMgdlPerMin)
    }

    private companion object {
        /** Room's OnConflictStrategy.IGNORE reports -1 for a skipped row. */
        const val IGNORED_ROW = -1L
    }
}
