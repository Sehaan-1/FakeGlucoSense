package com.fakeglucosense.companion.data.source

import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.io.IOException

/**
 * Runs [connectOnce] until the collector cancels. After a failure or a clean disconnect it
 * reports RETRYING, waits per [backoff], and tries again.
 *
 * [connectOnce] calls `markConnected()` once the link is usable. That resets the backoff, so a
 * link that worked for a while retries quickly after its next drop.
 */
internal fun reconnecting(
    label: String,
    backoff: BackoffPolicy,
    connectOnce: suspend ProducerScope<SourceEvent>.(markConnected: () -> Unit) -> Unit,
): Flow<SourceEvent> = channelFlow {
    var attempt = 0
    while (true) {
        attempt++
        send(SourceEvent.Link(LinkState(LinkPhase.CONNECTING, label)))
        val reason = try {
            connectOnce { attempt = 0 }
            "connection closed"
        } catch (e: IOException) {
            e.message ?: e.javaClass.simpleName
        }
        val delayMs = backoff.delayFor(attempt)
        send(
            SourceEvent.Link(
                LinkState(
                    phase = LinkPhase.RETRYING,
                    detail = "$label: $reason",
                    retryInSeconds = ((delayMs + 999) / 1000).toInt(),
                ),
            ),
        )
        delay(delayMs)
    }
}
