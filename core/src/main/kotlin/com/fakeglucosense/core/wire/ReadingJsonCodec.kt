package com.fakeglucosense.core.wire

import com.fakeglucosense.core.model.GlucoseReading
import com.fakeglucosense.core.model.Trend
import java.util.Locale

/**
 * Encodes one reading as a single JSON object line for the TCP simulator stream (ADR-0011):
 * `{"t":1700000000000,"mgdl":120,"direction":"Flat","rate":0.120,"seq":12}`.
 *
 * Keys: `t` epoch ms (UTC), `mgdl`, `direction` (Nightscout name), `rate` (optional, mg/dL per min),
 * `seq` (optional sequence number). The codec is hand-written and only handles flat objects of
 * strings and numbers, which is all this stream needs.
 */
object ReadingJsonCodec {
    private val FIELD = Regex("\"([A-Za-z_]+)\"\\s*:\\s*(\"([^\"]*)\"|(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)|null)")

    /** Parsed line: the reading plus an optional sequence number from the stream. */
    data class Line(val reading: GlucoseReading, val sequence: Long?)

    fun encode(reading: GlucoseReading, sequence: Long? = null): String {
        val parts = mutableListOf(
            "\"t\":${reading.epochMillis}",
            "\"mgdl\":${reading.mgdl}",
            "\"direction\":\"${reading.trend.nightscoutName}\"",
        )
        reading.rateMgdlPerMin?.let { parts += "\"rate\":${String.format(Locale.ROOT, "%.3f", it)}" }
        sequence?.let { parts += "\"seq\":$it" }
        return parts.joinToString(prefix = "{", postfix = "}", separator = ",")
    }

    /** Returns null for any line that is not a valid reading object. Never throws. */
    fun decode(line: String): Line? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val fields = FIELD.findAll(trimmed).associate { match ->
            val key = match.groupValues[1]
            val value = when {
                match.groups[3] != null -> match.groupValues[3]
                match.groups[4] != null -> match.groupValues[4]
                else -> null
            }
            key to value
        }
        val epoch = fields["t"]?.toLongOrNull() ?: return null
        val mgdl = fields["mgdl"]?.toIntOrNull() ?: return null
        val trend = fields["direction"]?.let { Trend.fromNightscoutName(it) } ?: Trend.NOT_COMPUTABLE
        val rate = fields["rate"]?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val sequence = fields["seq"]?.toLongOrNull()
        val reading = try {
            GlucoseReading(epochMillis = epoch, mgdl = mgdl, trend = trend, rateMgdlPerMin = rate)
        } catch (e: IllegalArgumentException) {
            return null
        }
        return Line(reading, sequence)
    }
}
