package dev.davidjosearaujo.zaptun.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpNatTest {
    private val tun = Ip4.addr("10.215.0.1")
    private val fake = Ip4.addr("10.215.0.2")
    private val remote = Ip4.addr("93.184.216.34")

    private fun tcp(src: Int, srcPort: Int, dst: Int, dstPort: Int, flags: Int, payload: Int = 0, ack: Int = 0): ByteArray {
        val p = ByteArray(40 + payload)
        p[0] = 0x45
        Ip4.put16(p, 2, p.size)
        p[8] = 64
        p[9] = Ip4.PROTO_TCP.toByte()
        Ip4.put32(p, 12, src)
        Ip4.put32(p, 16, dst)
        Ip4.put16(p, 20, srcPort)
        Ip4.put16(p, 22, dstPort)
        Ip4.put32(p, 24, 1234)
        Ip4.put32(p, 28, ack)
        p[32] = 0x50
        p[33] = flags.toByte()
        Ip4.put16(p, 34, 65535)
        for (i in 0 until payload) p[40 + i] = i.toByte()
        Ip4.fixChecksums(p)
        return p
    }

    @Test
    fun segmentsForConnectionsItDoesNotHaveAreSpottedAndReset() {
        val nat = TcpNat(tun, fake).apply { listenPort = 40000 }

        // An app still talking on a connection from before a reconnect.
        val stale = tcp(tun, 51000, remote, 443, Ip4.TCP_ACK, payload = 5, ack = 777)
        assertFalse(nat.translate(stale, 1))
        assertTrue(nat.isUnknownAppSegment(stale))
        val rst = Ip4.resetForSegment(stale, stale.size)
        assertEquals(remote, Ip4.src(rst))
        assertEquals(443, Ip4.u16(rst, 20))
        assertEquals(tun, Ip4.dst(rst))
        assertEquals(51000, Ip4.u16(rst, 22))
        assertEquals(777, Ip4.u32(rst, 24)) // the sequence number the app expects next, so it accepts the RST
        assertEquals(Ip4.TCP_RST, Ip4.u8(rst, 33))
        assertTrue(Ip4.checksumsValid(rst))

        // Not for RSTs, new connections, connections it has, or the relay's own packets.
        assertFalse(nat.isUnknownAppSegment(tcp(tun, 51000, remote, 443, Ip4.TCP_RST)))
        assertFalse(nat.isUnknownAppSegment(tcp(tun, 51000, remote, 443, Ip4.TCP_SYN)))
        assertTrue(nat.translate(tcp(tun, 52000, remote, 443, Ip4.TCP_SYN), 2))
        assertFalse(nat.isUnknownAppSegment(tcp(tun, 52000, remote, 443, Ip4.TCP_ACK)))
        assertFalse(nat.isUnknownAppSegment(tcp(tun, 40000, fake, 1024, Ip4.TCP_ACK)))
    }

    @Test
    fun aSegmentWithoutAckGetsARstThatAcknowledgesIt() {
        val p = tcp(tun, 51000, remote, 443, Ip4.TCP_FIN, payload = 10)
        val rst = Ip4.resetForSegment(p, p.size)
        assertEquals(Ip4.TCP_RST or Ip4.TCP_ACK, Ip4.u8(rst, 33))
        assertEquals(0, Ip4.u32(rst, 24))
        assertEquals(1234 + 10 + 1, Ip4.u32(rst, 28)) // the data, and the FIN
        assertTrue(Ip4.checksumsValid(rst))
    }

    @Test
    fun openConnectionsCanBeResetAsIfByTheirDestinations() {
        val nat = TcpNat(tun, fake).apply { listenPort = 40000 }
        // Open, and the app has acknowledged up to 9999.
        assertTrue(nat.translate(tcp(tun, 51000, remote, 443, Ip4.TCP_SYN), 1))
        assertTrue(nat.translate(tcp(tun, 51000, remote, 443, Ip4.TCP_ACK, ack = 9999), 2))
        // Still connecting: the app hasn't acknowledged anything, so there's no sequence number to use.
        assertTrue(nat.translate(tcp(tun, 51001, remote, 443, Ip4.TCP_SYN), 3))
        // Already reset.
        assertTrue(nat.translate(tcp(tun, 51002, remote, 80, Ip4.TCP_SYN), 4))
        assertTrue(nat.translate(tcp(tun, 51002, remote, 80, Ip4.TCP_ACK, ack = 5), 5))
        assertTrue(nat.translate(tcp(tun, 51002, remote, 80, Ip4.TCP_RST), 6))

        val resets = nat.resetsForOpenConnections()

        assertEquals(1, resets.size)
        val rst = resets.single()
        assertEquals(remote, Ip4.src(rst))
        assertEquals(443, Ip4.u16(rst, 20))
        assertEquals(tun, Ip4.dst(rst))
        assertEquals(51000, Ip4.u16(rst, 22))
        assertEquals(9999, Ip4.u32(rst, 24))
        assertEquals(Ip4.TCP_RST, Ip4.u8(rst, 33))
        assertTrue(Ip4.checksumsValid(rst))
    }

    @Test
    fun roundTrip() {
        val nat = TcpNat(tun, fake).apply { listenPort = 40000 }

        val syn = tcp(tun, 51000, remote, 443, Ip4.TCP_SYN)
        assertTrue(nat.translate(syn, 1))
        assertEquals(fake, Ip4.src(syn))
        assertEquals(tun, Ip4.dst(syn))
        assertEquals(40000, Ip4.u16(syn, 22))
        assertTrue(Ip4.checksumsValid(syn))
        val fakePort = Ip4.u16(syn, 20)

        val session = nat.lookup(fakePort)!!
        assertEquals(remote, session.dstIp)
        assertEquals(443, session.dstPort)

        val reply = tcp(tun, 40000, fake, fakePort, Ip4.TCP_SYN or Ip4.TCP_ACK, payload = 7)
        assertTrue(nat.translate(reply, 2))
        assertEquals(remote, Ip4.src(reply))
        assertEquals(443, Ip4.u16(reply, 20))
        assertEquals(tun, Ip4.dst(reply))
        assertEquals(51000, Ip4.u16(reply, 22))
        assertTrue(Ip4.checksumsValid(reply))

        // Follow-up packets of the same connection reuse the session.
        val ack = tcp(tun, 51000, remote, 443, Ip4.TCP_ACK, payload = 3)
        assertTrue(nat.translate(ack, 3))
        assertEquals(fakePort, Ip4.u16(ack, 20))
    }

    @Test
    fun translatesInPlaceInsideALargerBuffer() {
        // The VPN reads packets into one reusable MTU-sized buffer and rewrites them there.
        val nat = TcpNat(tun, fake).apply { listenPort = 40000 }
        val packet = tcp(tun, 51000, remote, 443, Ip4.TCP_SYN, payload = 5)
        val buffer = ByteArray(1500).also { packet.copyInto(it) }

        assertTrue(nat.translate(buffer, 1))
        assertEquals(fake, Ip4.src(buffer))
        assertEquals(packet.size, Ip4.totalLen(buffer))
        assertTrue(Ip4.checksumsValid(buffer))
    }

    @Test
    fun sameAppPortDifferentDestinationsGetDifferentSessions() {
        val nat = TcpNat(tun, fake).apply { listenPort = 40000 }
        val a = tcp(tun, 51000, remote, 443, Ip4.TCP_SYN)
        val b = tcp(tun, 51000, remote + 1, 443, Ip4.TCP_SYN)
        nat.translate(a, 1)
        nat.translate(b, 1)
        assertTrue(Ip4.u16(a, 20) != Ip4.u16(b, 20))
    }

    @Test
    fun dropsUnknownNonSyn() {
        val nat = TcpNat(tun, fake).apply { listenPort = 40000 }
        assertFalse(nat.translate(tcp(tun, 51000, remote, 443, Ip4.TCP_ACK), 1))
        assertFalse(nat.translate(tcp(tun, 40000, fake, 1234, Ip4.TCP_ACK), 1))
    }

    /** Opens a connection and returns its fake port. */
    private fun open(nat: TcpNat, appPort: Int = 51000, now: Long = 1): Int {
        val syn = tcp(tun, appPort, remote, 443, Ip4.TCP_SYN)
        assertTrue(nat.translate(syn, now))
        return Ip4.u16(syn, 20)
    }

    private fun fromApp(nat: TcpNat, flags: Int, now: Long, appPort: Int = 51000) =
        nat.translate(tcp(tun, appPort, remote, 443, flags, payload = 3), now)

    private fun fromRelay(nat: TcpNat, fakePort: Int, flags: Int, now: Long) =
        nat.translate(tcp(tun, 40000, fake, fakePort, flags, payload = 3), now)

    @Test
    fun expiresASessionOnlyAfterBothSidesClosed() {
        val nat = TcpNat(tun, fake).apply { listenPort = 40000 }
        val fakePort = open(nat)
        fromApp(nat, Ip4.TCP_FIN or Ip4.TCP_ACK, now = 2)
        fromRelay(nat, fakePort, Ip4.TCP_FIN or Ip4.TCP_ACK, now = 3)

        nat.expire(30_000)
        assertEquals(1, nat.size())
        nat.expire(70_000)
        assertEquals(0, nat.size())
    }

    @Test
    fun aHalfClosedConnectionKeepsItsMappingForAsLongAsDataFlows() {
        val nat = TcpNat(tun, fake).apply { listenPort = 40000 }
        val fakePort = open(nat)
        // The app sends its request and half-closes; the response keeps coming for minutes.
        fromApp(nat, Ip4.TCP_FIN or Ip4.TCP_ACK, now = 2)

        for (t in listOf(70_000L, 140_000L, 300_000L)) {
            assertTrue("response data at $t ms still translated", fromRelay(nat, fakePort, Ip4.TCP_ACK, now = t))
            nat.expire(t)
        }
        assertEquals(1, nat.size())

        // Only when the other side finishes too does it start counting down.
        fromRelay(nat, fakePort, Ip4.TCP_FIN or Ip4.TCP_ACK, now = 301_000)
        nat.expire(330_000)
        assertEquals(1, nat.size())
        nat.expire(362_000)
        assertEquals(0, nat.size())
    }

    @Test
    fun aResetEndsTheConnectionAtOnce() {
        val nat = TcpNat(tun, fake).apply { listenPort = 40000 }
        open(nat)
        fromApp(nat, Ip4.TCP_RST or Ip4.TCP_ACK, now = 2)

        nat.expire(30_000)
        assertEquals(1, nat.size())
        nat.expire(70_000)
        assertEquals(0, nat.size())
    }

    @Test
    fun aNewSynAfterACloseStartsAFreshConnection() {
        val nat = TcpNat(tun, fake).apply { listenPort = 40000 }
        val first = open(nat)
        fromApp(nat, Ip4.TCP_FIN or Ip4.TCP_ACK, now = 2) // only the app has closed so far

        val second = open(nat, now = 3) // the app reuses the same source port for a new connection
        assertTrue(first != second)
        assertEquals(null, nat.lookup(first))
        assertEquals(remote, nat.lookup(second)!!.dstIp)
    }

    @Test
    fun aRetransmittedSynKeepsTheSameConnection() {
        val nat = TcpNat(tun, fake).apply { listenPort = 40000 }
        val first = open(nat)
        assertEquals(first, open(nat, now = 2))
        assertEquals(1, nat.size())
    }
}
