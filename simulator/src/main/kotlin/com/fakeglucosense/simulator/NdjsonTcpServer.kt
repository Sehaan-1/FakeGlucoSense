package com.fakeglucosense.simulator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * TCP server that streams NDJSON readings from a [ReadingHub]. Each client first gets the recent
 * history (backfill), then live lines. Connect with `nc <host> 8765`.
 */
class NdjsonTcpServer(
    private val hub: ReadingHub,
    private val scope: CoroutineScope,
    private val onLog: (String) -> Unit = {},
) {
    private var server: ServerSocket? = null

    /** Binds and starts accepting. Returns the bound port (useful when [port] is 0). */
    fun start(bindAddress: String, port: Int): Int {
        check(server == null) { "server already started" }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, port))
        server = socket
        scope.launch(Dispatchers.IO) { acceptLoop(socket) }
        onLog("listening on ${bindAddress}:${socket.localPort}")
        return socket.localPort
    }

    fun stop() {
        server?.close()
        server = null
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: SocketException) {
                break
            }
            scope.launch(Dispatchers.IO) { serve(client) }
        }
    }

    private suspend fun serve(client: Socket) = coroutineScope {
        val remote = client.remoteSocketAddress.toString()
        onLog("client connected $remote")
        val channel = hub.subscribe()
        client.use { socket ->
            // Clients never send data, so a read only returns when the peer closes (-1) or the socket
            // fails. Closing the channel then wakes the writer below, even when no reading is due.
            launch(Dispatchers.IO) {
                try {
                    val input = socket.getInputStream()
                    while (input.read() != -1) {
                        // Discard anything the client sends.
                    }
                } catch (e: IOException) {
                    // Reset by the peer, or closed locally when serving ends.
                } finally {
                    hub.unsubscribe(channel)
                }
            }
            try {
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                for (line in hub.snapshot()) writer.writeLine(line)
                writer.flush()
                while (true) {
                    val line = channel.receive()
                    writer.writeLine(line)
                    writer.flush()
                }
            } catch (e: ClosedReceiveChannelException) {
                onLog("client dropped (closed or too slow) $remote")
            } catch (e: IOException) {
                onLog("client disconnected $remote")
            } finally {
                hub.unsubscribe(channel)
            }
        }
    }

    private fun BufferedWriter.writeLine(line: String) {
        write(line)
        write("\n")
    }
}
