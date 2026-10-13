package com.fakeglucosense.companion.data.db

import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.model.Trend

fun GlucoseReading.toEntity(source: String): GlucoseEntity = GlucoseEntity(
    epochMillis = epochMillis,
    mgdl = mgdl,
    trend = trend.name,
    rateMgdlPerMin = rateMgdlPerMin,
    source = source,
)

fun GlucoseEntity.toReading(): GlucoseReading = GlucoseReading(
    epochMillis = epochMillis,
    mgdl = mgdl,
    // An unknown name (from a future build that wrote more trends) degrades to "not computable".
    trend = runCatching { Trend.valueOf(trend) }.getOrDefault(Trend.NOT_COMPUTABLE),
    rateMgdlPerMin = rateMgdlPerMin,
)
