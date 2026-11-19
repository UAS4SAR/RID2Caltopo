/*
 * Copyright (C) 2026 Ken Taylor
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.ncssar.rid2caltopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerReconnectPolicyTest {
    private val policy = TrackerReconnectPolicy()

    @Test
    fun connectFailuresClimbTheLadderAndStayAtTenSeconds() {
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 10_000L, 10_000L),
            List(5) {
                policy.takeRetryDelay(TrackerReconnectPolicy.RetryKind.CONNECT_FAILURE, false)
            }
        )
    }

    @Test
    fun ackTimeoutUsesTheSameLadderAndDoesNotRedialImmediately() {
        assertEquals(
            listOf(2_000L, 4_000L, 8_000L, 10_000L),
            List(4) {
                policy.takeRetryDelay(TrackerReconnectPolicy.RetryKind.ACK_TIMEOUT, false)
            }
        )
        assertTrue(
            policy.peekDelayMs(TrackerReconnectPolicy.RetryKind.ACK_TIMEOUT, false) > 0L
        )
    }

    @Test
    fun socketOpenDoesNotResetUntilHelloAck() {
        assertEquals(2_000L, policy.takeRetryDelay(TrackerReconnectPolicy.RetryKind.CONNECT_FAILURE, false))
        assertEquals(4_000L, policy.takeRetryDelay(TrackerReconnectPolicy.RetryKind.ACK_TIMEOUT, false))
        policy.onTransportOpened(false)
        assertEquals(
            8_000L,
            policy.peekDelayMs(TrackerReconnectPolicy.RetryKind.CONNECT_FAILURE, false)
        )

        policy.onHelloAcknowledged()
        assertEquals(
            2_000L,
            policy.peekDelayMs(TrackerReconnectPolicy.RetryKind.ACK_TIMEOUT, false)
        )
    }

    @Test
    fun openSocketToleratesTwelveSecondHelloAndClosesAfterThirty() {
        val sentAtMs = 1_000L
        assertFalse(TrackerReconnectPolicy.helloAckTimedOut(sentAtMs, 13_000L, true, false))
        assertFalse(TrackerReconnectPolicy.helloAckTimedOut(sentAtMs, 31_000L, true, false))
        assertTrue(TrackerReconnectPolicy.helloAckTimedOut(sentAtMs, 31_001L, true, false))
        assertEquals(30_000L, TrackerReconnectPolicy.helloAckTimeoutMs(true, false))
    }

    @Test
    fun closedSocketAndLegacyModeKeepTheTenSecondHelloBudget() {
        val sentAtMs = 5_000L
        assertFalse(TrackerReconnectPolicy.helloAckTimedOut(sentAtMs, 15_000L, false, false))
        assertTrue(TrackerReconnectPolicy.helloAckTimedOut(sentAtMs, 15_001L, false, false))
        assertFalse(TrackerReconnectPolicy.helloAckTimedOut(sentAtMs, 15_000L, true, true))
        assertTrue(TrackerReconnectPolicy.helloAckTimedOut(sentAtMs, 15_001L, true, true))
        assertEquals(10_000L, TrackerReconnectPolicy.HEARTBEAT_ACK_TIMEOUT_MS)
    }

    @Test
    fun legacyAckTimeoutRedialsImmediatelyWithoutAdvancingOrSurvivingOpen() {
        assertEquals(2_000L, policy.takeRetryDelay(TrackerReconnectPolicy.RetryKind.CONNECT_FAILURE, true))
        assertEquals(4_000L, policy.peekDelayMs(TrackerReconnectPolicy.RetryKind.CONNECT_FAILURE, true))
        assertEquals(0L, policy.peekDelayMs(TrackerReconnectPolicy.RetryKind.ACK_TIMEOUT, true))

        assertEquals(0L, policy.takeRetryDelay(TrackerReconnectPolicy.RetryKind.ACK_TIMEOUT, true))
        assertEquals(4_000L, policy.peekDelayMs(TrackerReconnectPolicy.RetryKind.CONNECT_FAILURE, true))

        policy.onTransportOpened(true)
        assertEquals(2_000L, policy.peekDelayMs(TrackerReconnectPolicy.RetryKind.CONNECT_FAILURE, true))
    }

    @Test
    fun peekDoesNotAdvanceTheLadder() {
        assertEquals(2_000L, policy.peekDelayMs(TrackerReconnectPolicy.RetryKind.CONNECT_FAILURE, false))
        assertEquals(2_000L, policy.peekDelayMs(TrackerReconnectPolicy.RetryKind.ACK_TIMEOUT, false))
        assertEquals(2_000L, policy.takeRetryDelay(TrackerReconnectPolicy.RetryKind.CONNECT_FAILURE, false))
        assertEquals(4_000L, policy.peekDelayMs(TrackerReconnectPolicy.RetryKind.CONNECT_FAILURE, false))
    }
}

class TrackerReconnectPrefsTest {
    @Test
    fun missingOrFalseStorageKeepsBackoff() {
        assertFalse(TrackerReconnectPrefs.resolveLegacyImmediateAckRetry(null))
        assertFalse(TrackerReconnectPrefs.resolveLegacyImmediateAckRetry(false))
        assertTrue(TrackerReconnectPrefs.resolveLegacyImmediateAckRetry(true))
    }
}
