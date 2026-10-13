package com.fakeglucosense.core.sim

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/** Standard normal sample via Box–Muller. `kotlin.random` has no Gaussian in the stdlib. */
internal fun Random.nextGaussian(): Double {
    val u1 = 1.0 - nextDouble() // in (0, 1], avoids ln(0)
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * PI * u2)
}
