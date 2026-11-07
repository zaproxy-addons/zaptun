package dev.davidjosearaujo.zaptun.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuicTest {
    /** The start of a QUIC long-header packet, padded to [size] bytes like a client's first datagram. */
    private fun longHeader(version: Int, first: Int = 0xC3, size: Int = 1252, dcidLen: Int = 8): ByteArray {
        val p = ByteArray(size)
        p[0] = first.toByte()
        Ip4.put32(p, 1, version)
        p[5] = dcidLen.toByte()
        return p
    }

    private fun initial(p: ByteArray) = Quic.isInitial(p, 0, p.size)

    @Test
    fun recognisesTheInitialsOfCommonVersions() {
        assertTrue(initial(longHeader(0x00000001))) // QUIC v1
        assertTrue(initial(longHeader(0x6b3343cf, first = 0xD3))) // v2: Initial is type 1
        assertTrue(initial(longHeader(0xff00001d.toInt()))) // draft 29
        assertTrue(initial(longHeader(0x51303530))) // Google "Q050"
        assertTrue(initial(longHeader(0x54303531))) // Google "T051"
    }

    @Test
    fun leavesOtherUdpAlone() {
        assertFalse(initial(longHeader(0x00000001, size = 600))) // not padded: not a client Initial
        assertFalse(initial(longHeader(0x00000001, first = 0x43))) // short header
        assertFalse(initial(longHeader(0x00000001, first = 0xE3))) // v1 Handshake, not Initial
        assertFalse(initial(longHeader(0x6b3343cf, first = 0xC3))) // v2 type 0 is 0-RTT
        assertFalse(initial(longHeader(0x12345678))) // unknown version
        assertFalse(initial(longHeader(0x00000000))) // version negotiation (sent by servers)
        assertFalse(initial(longHeader(0x00000001, dcidLen = 21)))
        // RTP (version 2, padding bit), STUN and DTLS, at QUIC's size.
        assertFalse(initial(ByteArray(1252).also { it[0] = 0xE0.toByte(); it[1] = 0x60 }))
        assertFalse(initial(ByteArray(1252).also { it[0] = 0x00; it[1] = 0x01 }))
        assertFalse(initial(ByteArray(1252).also { it[0] = 0x16; it[1] = 0xfe.toByte(); it[2] = 0xfd.toByte() }))
    }

    @Test
    fun readsThePayloadInsideAnIpPacket() {
        val payload = longHeader(0x00000001)
        val p = TestPackets.udpFromApp(40000, "142.250.200.4", 8443, payload)
        assertTrue(Quic.isInitial(p, 28, p.size - 28))
        assertFalse(Quic.isInitial(p, 28, 900)) // a length that runs short
        assertFalse(Quic.isInitial(p, 28, p.size)) // a length past the buffer
    }
}
