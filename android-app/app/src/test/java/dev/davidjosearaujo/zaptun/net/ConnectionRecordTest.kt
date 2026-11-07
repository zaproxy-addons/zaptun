package dev.davidjosearaujo.zaptun.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRecordTest {
    private fun record(start: Long = 1_000) = ConnectionRecord(1, start).apply {
        kind = Sniffer.Kind.TLS
        host = "api.example.com"
        port = 443
    }

    @Test
    fun describesAConnectionStillWaitingForData() {
        val r = ConnectionRecord(1, 1_000).apply { host = "10.0.0.5"; port = 8080 }
        assertEquals("silent 10.0.0.5:8080 · waiting for the app to send data (2.5 s) · ↑0 B ↓0 B", r.describe(3_500))
    }

    @Test
    fun describesAnOpenConnection() {
        val r = record().apply { route = TcpRelay.Route.PROXY; opened(); up.set(1_300); down.set(5_300_000) }
        assertEquals("TLS api.example.com:443 → ZAP · open 800 ms · ↑1.3 KB ↓5.1 MB", r.describe(1_800))
    }

    @Test
    fun describesACleanClose() {
        val r = record().apply { route = TcpRelay.Route.DIRECT; opened(); up.set(10); down.set(2048); closedFirstBy(ConnectionRecord.Closer.SERVER) }
        assertTrue(r.finish(66_000))
        assertEquals("TLS api.example.com:443 → direct · closed by server after 1 min 5 s · ↑10 B ↓2.0 KB", r.describe(99_999))
        assertEquals(ConnectionRecord.State.CLOSED, r.state)
    }

    @Test
    fun describesAFailure() {
        val r = record().apply { failedWith("proxy said: HTTP/1.1 502 Bad Gateway") }
        r.finish(1_250)
        assertEquals("TLS api.example.com:443 · failed after 250 ms: proxy said: HTTP/1.1 502 Bad Gateway · ↑0 B ↓0 B", r.describe(9_999))
        assertEquals(ConnectionRecord.State.FAILED, r.state)
    }

    @Test
    fun aRecordIsFinishedOnlyOnce() {
        val r = record()
        assertTrue(r.finish(2_000))
        assertFalse(r.finish(3_000))
        assertEquals(2_000, r.endedAt)
    }

    @Test
    fun keepsTheFirstCloserAndTheFirstError() {
        val r = record()
        r.closedFirstBy(ConnectionRecord.Closer.APP)
        r.closedFirstBy(ConnectionRecord.Closer.SERVER)
        r.failedWith("Connection reset")
        r.failedWith("Socket closed") // our own sockets closing afterwards
        assertEquals(ConnectionRecord.Closer.APP, r.closedBy)
        assertEquals("Connection reset", r.error)
    }

    @Test
    fun aConnectionWithNothingToSayHasNoCloserOrError() {
        val r = record()
        r.finish(2_000)
        assertNull(r.closedBy)
        assertNull(r.error)
    }

    @Test
    fun formatsDurationsAndSizes() {
        assertEquals("0 ms", ConnectionRecord.format(0))
        assertEquals("999 ms", ConnectionRecord.format(999))
        assertEquals("1.0 s", ConnectionRecord.format(1_000))
        assertEquals("59.9 s", ConnectionRecord.format(59_900))
        assertEquals("1 min 0 s", ConnectionRecord.format(60_000))
        assertEquals("3 min 7 s", ConnectionRecord.format(187_000))
        assertEquals("1023 B", ConnectionRecord.bytes(1023))
        assertEquals("1.0 KB", ConnectionRecord.bytes(1024))
        assertEquals("1.0 MB", ConnectionRecord.bytes(1024 * 1024))
    }

    @Test
    fun namesEachKind() {
        assertEquals("not intercepted", ConnectionRecord.kindLabel(Sniffer.Kind.PORT_SKIPPED))
        assertEquals("other protocol", ConnectionRecord.kindLabel(Sniffer.Kind.OTHER))
        assertEquals("HTTP", ConnectionRecord.kindLabel(Sniffer.Kind.HTTP))
    }
}
