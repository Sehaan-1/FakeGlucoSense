package com.fakeglucosense.simulator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NdjsonTcpServerTest {

    @Test
    fun `new clients receive history first and then live lines`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = ReadingHub(historyLimit = 10)
            hub.publish("""{"seq":0}""")
            hub.publish("""{"seq":1}""")
            val server = NdjsonTcpServer(hub, scope)
            val port = server.start(InetAddress.getLoopbackAddress().hostAddress, 0)

            withContext(Dispatchers.IO) {
                Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    assertEquals("""{"seq":0}""", reader.readLine())
                    assertEquals("""{"seq":1}""", reader.readLine())
                    hub.publish("""{"seq":2}""")
                    assertEquals("""{"seq":2}""", reader.readLine())
                }
            }
            server.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a client that closes while idle is unsubscribed without waiting for the next reading`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = ReadingHub(historyLimit = 10)
            val server = NdjsonTcpServer(hub, scope)
            val port = server.start(InetAddress.getLoopbackAddress().hostAddress, 0)

            withContext(Dispatchers.IO) {
                val socket = Socket(InetAddress.getLoopbackAddress(), port)
                // Wait for the server to register the subscription before closing.
                val deadline = System.currentTimeMillis() + 5_000
                while (hub.subscriberCount() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(1, hub.subscriberCount())
                socket.close()
                // No reading is published, so only the read-side watcher can notice the close.
                val closeDeadline = System.currentTimeMillis() + 5_000
                while (hub.subscriberCount() != 0 && System.currentTimeMillis() < closeDeadline) Thread.sleep(10)
            }
            assertEquals(0, hub.subscriberCount())
            server.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `server reports the bound port when asked for port zero`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val server = NdjsonTcpServer(ReadingHub(historyLimit = 1), scope)
            val port = server.start("127.0.0.1", 0)
            assertTrue(port in 1..65535)
            server.stop()
        } finally {
            scope.cancel()
        }
    }
}
