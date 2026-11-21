package com.treko.core.network

import com.treko.core.common.model.TrekoState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaozhiNetworkTest {

    @Test
    fun testInvalidServerUrlSetsErrorAndNotifiesWithoutHanging() {
        val client = XiaozhiWebSocketClient(
            serverUrl = "http://not-a-websocket-url",
            macAddress = "02:aa:bb:cc:dd:ee",
            clientId = "test-client"
        )
        var capturedError: String? = null
        client.onError = { capturedError = it }

        client.connect()

        assertEquals(TrekoState.ERROR, client.connectionState.value)
        assertNotNull(capturedError)
        assertTrue(capturedError!!.contains("ws://"))
        client.release()
    }

    @Test
    fun testExponentialReconnectBackoffBounded() {
        val client = XiaozhiWebSocketClient(
            serverUrl = "wss://api.tenclass.net/xiaozhi/v1/",
            macAddress = "02:aa:bb:cc:dd:ee",
            clientId = "test-client"
        )
        val d0 = client.computeReconnectDelayMs(0)
        val d1 = client.computeReconnectDelayMs(1)
        val d2 = client.computeReconnectDelayMs(2)
        val d10 = client.computeReconnectDelayMs(10)

        assertTrue(d0 in 2000L..2500L)
        assertTrue(d1 in 4000L..4500L)
        assertTrue(d2 in 8000L..8500L)
        assertTrue(d10 in 30_000L..30_500L)
        client.release()
    }

    @Test
    fun testBackoffRestartsOnlyAfterAStableSession() {
        val client = XiaozhiWebSocketClient(
            serverUrl = "wss://example.invalid/xiaozhi/v1/",
            macAddress = "02:aa:bb:cc:dd:ee",
            clientId = "test-client"
        )
        // Idle session closed by the server after about a minute: start over.
        assertEquals(0, client.nextReconnectAttempt(3, sessionLivedMs = 61_000L))
        assertEquals(0, client.nextReconnectAttempt(0, sessionLivedMs = XiaozhiWebSocketClient.STABLE_SESSION_MS))
        // Closed right after opening, or never opened: keep backing off.
        assertEquals(3, client.nextReconnectAttempt(3, sessionLivedMs = 1_500L))
        assertEquals(2, client.nextReconnectAttempt(2, sessionLivedMs = null))
        client.release()
    }
}
