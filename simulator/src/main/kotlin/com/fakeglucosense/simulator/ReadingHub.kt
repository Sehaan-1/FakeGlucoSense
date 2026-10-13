package com.fakeglucosense.simulator

import kotlinx.coroutines.channels.Channel

/**
 * Fan-out point between the simulation loop and TCP clients. It keeps a bounded history so a
 * client that connects late can be backfilled, and it gives each subscriber its own bounded queue.
 * A subscriber whose queue overflows is dropped. A slow client must not slow the simulator (ADR-0011).
 */
class ReadingHub(private val historyLimit: Int, private val subscriberCapacity: Int = DEFAULT_SUBSCRIBER_CAPACITY) {
    init {
        require(historyLimit >= 1) { "history limit must be positive" }
        require(subscriberCapacity >= 1) { "subscriber capacity must be positive" }
    }

    private val lock = Any()
    private val history = ArrayDeque<String>()
    private val subscribers = LinkedHashSet<Channel<String>>()

    /** Appends [line] to history and offers it to every subscriber. Never blocks. */
    fun publish(line: String) {
        synchronized(lock) {
            history.addLast(line)
            while (history.size > historyLimit) history.removeFirst()
            val dropped = subscribers.filter { channel -> !channel.trySend(line).isSuccess }
            dropped.forEach { channel ->
                channel.close()
                subscribers.remove(channel)
            }
        }
    }

    /** Current history, oldest first. */
    fun snapshot(): List<String> = synchronized(lock) { history.toList() }

    /**
     * Registers a new subscriber. Call this before [snapshot] so no line is lost between the two.
     * Duplicates are possible; clients dedupe by timestamp.
     */
    fun subscribe(): Channel<String> = synchronized(lock) {
        Channel<String>(subscriberCapacity).also { subscribers += it }
    }

    fun unsubscribe(channel: Channel<String>) {
        synchronized(lock) {
            subscribers.remove(channel)
            channel.close()
        }
    }

    fun subscriberCount(): Int = synchronized(lock) { subscribers.size }

    companion object {
        const val DEFAULT_SUBSCRIBER_CAPACITY = 256
    }
}
