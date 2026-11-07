package dev.davidjosearaujo.zaptun.net

/**
 * Recognises the first packet of a QUIC connection (HTTP/3) on any UDP port, so it can be refused
 * and the client falls back to TCP, where ZAP sees it.
 *
 * A client's first packet is an Initial: a long header (the top two bits of the first byte set),
 * a known version, a connection ID of at most 20 bytes, and the datagram padded to at least
 * [MIN_INITIAL] bytes (RFC 9000, section 14.1). Requiring all of these keeps other UDP (games,
 * calls, WebRTC, which pass through) from being taken for QUIC.
 */
object Quic {
    private const val MIN_INITIAL = 1200

    private const val V1 = 0x00000001
    private const val V2 = 0x6b3343cf

    /** True if the UDP payload at [off], [len] bytes long, is a QUIC client's Initial packet. */
    fun isInitial(b: ByteArray, off: Int, len: Int): Boolean {
        if (len < MIN_INITIAL || off < 0 || off + len > b.size) return false
        val first = Ip4.u8(b, off)
        if (first and 0xC0 != 0xC0) return false // long header, fixed bit
        val version = Ip4.u32(b, off + 1)
        if (!knownVersion(version)) return false
        // The packet type: Initial is 0, except in QUIC v2, which renumbered the types.
        val type = (first ushr 4) and 0x03
        if (type != (if (version == V2) 1 else 0)) return false
        return Ip4.u8(b, off + 5) <= 20 // destination connection ID length
    }

    private fun knownVersion(v: Int) =
        v == V1 || v == V2 ||
            (v ushr 8) == 0xff0000 || // IETF drafts (h3-29 and friends)
            (v ushr 16) == 0x5130 || // Google QUIC, "Q0xx"
            (v ushr 16) == 0x5430 // Google QUIC with TLS, "T0xx"
}
