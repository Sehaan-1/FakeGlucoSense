package com.fakeglucosense.companion.data.source

import com.fakeglucosense.core.wire.ReadingJsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Reads NDJSON from the simulator's TCP server (ADR-0011).
 *
 * The socket is blocking, so cancellation closes it from a completion handler. Without that, a
 * cancelled collector would wait forever inside readLine().
 */
class TcpSimulatorSource(
    private val host: String,
    private val port: Int,
    private val backoff: BackoffPolicy = BackoffPolicy(),
) : GlucoseSource {

    override val sourceName: String = "simulator"

    override fun events(): Flow<SourceEvent> = reconnecting("$host:$port", backoff) { markConnected ->
        withContext(Dispatchers.IO) {
            Socket().use { socket ->
                coroutineScope {
                    // The read below blocks in native socket I/O, which cancellation cannot interrupt.
                    // This child waits for cancellation and then closes the socket, which unblocks it.
                    val closer = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                    try {
                        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                        socket.keepAlive = true
                        markConnected()
                        send(SourceEvent.Link(LinkState(LinkPhase.CONNECTED, "$host:$port")))
                        socket.getInputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                            while (true) {
                                // null means the server closed the stream. The caller reconnects.
                                val line = reader.readLine() ?: break
                                val decoded = ReadingJsonCodec.decode(line) ?: continue
                                send(SourceEvent.Reading(decoded.reading))
                            }
                        }
                    } finally {
                        closer.cancel()
                    }
                }
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
    }
}
