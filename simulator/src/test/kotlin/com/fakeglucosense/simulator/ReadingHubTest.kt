package com.fakeglucosense.simulator

import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadingHubTest {

    @Test
    fun `history keeps only the newest lines`() {
        val hub = ReadingHub(historyLimit = 3)
        (1..5).forEach { hub.publish("line $it") }
        assertEquals(listOf("line 3", "line 4", "line 5"), hub.snapshot())
    }

    @Test
    fun `subscribers receive lines published after they subscribe`() = runBlocking {
        val hub = ReadingHub(historyLimit = 10)
        hub.publish("before")
        val channel = hub.subscribe()
        hub.publish("after")
        assertEquals("after", channel.receive())
        hub.unsubscribe(channel)
        assertEquals(0, hub.subscriberCount())
    }

    @Test
    fun `a subscriber that falls behind is dropped instead of blocking`() = runBlocking {
        val hub = ReadingHub(historyLimit = 10, subscriberCapacity = 2)
        val slow = hub.subscribe()
        repeat(5) { hub.publish("line $it") }
        assertEquals(0, hub.subscriberCount(), "overflowing subscriber should be removed")
        // Lines buffered before the overflow are still delivered; then the channel reports closed.
        assertEquals(listOf("line 0", "line 1"), listOf(slow.receive(), slow.receive()))
        assertFailsWith<ClosedReceiveChannelException> { slow.receive() }
        // Publishing with no subscribers must not throw.
        hub.publish("still fine")
        assertTrue(hub.snapshot().last() == "still fine")
    }
}
