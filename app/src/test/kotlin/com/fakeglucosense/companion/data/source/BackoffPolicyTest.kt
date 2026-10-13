package com.fakeglucosense.companion.data.source

import kotlin.test.Test
import kotlin.test.assertEquals

class BackoffPolicyTest {

    private val policy = BackoffPolicy(initialMillis = 1_000, maxMillis = 30_000)

    @Test
    fun `delay doubles per attempt and then caps`() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (1..7).map(policy::delayFor))
    }

    @Test
    fun `attempts below one are treated as the first`() {
        assertEquals(1_000L, policy.delayFor(0))
        assertEquals(1_000L, policy.delayFor(-3))
    }

    @Test
    fun `very large attempt counts do not overflow`() {
        assertEquals(30_000L, policy.delayFor(10_000))
    }
}
