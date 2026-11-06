package dev.resproxy

import dev.resproxy.vpn.KeepAlive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAliveTest {

    @Test fun backoffGrowsThenStaysAtTwoMinutes() {
        assertEquals(listOf(0L, 5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 120_000L, 120_000L, 120_000L),
            (0..8).map(KeepAlive::backoffMs))
        // Never gives up and never overflows, however long the outage.
        assertEquals(120_000L, KeepAlive.backoffMs(10_000))
    }

    @Test fun reconnectsOnlyWhenWantedAndNothingIsRunning() {
        val now = 1_000_000L
        assertTrue(KeepAlive.shouldReconnect(want = true, plumbingUp = false, busy = false, now = now, nextAttemptAt = 0))
        // The user turned it off.
        assertFalse(KeepAlive.shouldReconnect(want = false, plumbingUp = false, busy = false, now = now, nextAttemptAt = 0))
        // Up (maybe PROXY_DOWN): the health loop repairs it.
        assertFalse(KeepAlive.shouldReconnect(want = true, plumbingUp = true, busy = false, now = now, nextAttemptAt = 0))
        // A connect is running.
        assertFalse(KeepAlive.shouldReconnect(want = true, plumbingUp = false, busy = true, now = now, nextAttemptAt = 0))
        // Waiting for the backoff.
        assertFalse(KeepAlive.shouldReconnect(want = true, plumbingUp = false, busy = false, now = now, nextAttemptAt = now + 1))
        assertTrue(KeepAlive.shouldReconnect(want = true, plumbingUp = false, busy = false, now = now, nextAttemptAt = now))
    }
}
