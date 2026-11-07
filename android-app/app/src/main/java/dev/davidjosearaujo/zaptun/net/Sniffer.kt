package dev.davidjosearaujo.zaptun.net

/** Recognises the first bytes of a TCP stream and pulls out the hostname the client asked for. */
object Sniffer {
    /** PORT_SKIPPED: the port isn't intercepted, so the stream wasn't looked at. */
    enum class Kind { TLS, HTTP, OTHER, SILENT, PORT_SKIPPED }

    private val HTTP_METHODS = listOf("GET ", "POST ", "HEAD ", "PUT ", "DELETE ", "OPTIONS ", "PATCH ", "TRACE ", "CONNECT ")

    fun kind(b: ByteArray, len: Int): Kind = when {
        len == 0 -> Kind.SILENT
        isTls(b, len) -> Kind.TLS
        isHttp(b, len) -> Kind.HTTP
        else -> Kind.OTHER
    }

    /** A TLS handshake record (ClientHello) starts with 0x16 0x03 0x0X. */
    fun isTls(b: ByteArray, len: Int) =
        len >= 1 && b[0].toInt() == 0x16 && (len < 2 || b[1].toInt() == 0x03)

    fun isHttp(b: ByteArray, len: Int): Boolean {
        val head = String(b, 0, minOf(len, 8), Charsets.ISO_8859_1)
        return HTTP_METHODS.any { it.startsWith(head) || head.startsWith(it) }
    }

    /** How many bytes are needed to hold the whole first TLS record, or -1 if unknown yet. */
    fun tlsRecordEnd(b: ByteArray, len: Int): Int = if (len < 5) -1 else 5 + Ip4.u16(b, 3)

    /** True once the HTTP request headers are complete. */
    fun httpHeadersDone(b: ByteArray, len: Int): Boolean {
        for (i in 3 until len) {
            if (b[i - 3].toInt() == '\r'.code && b[i - 2].toInt() == '\n'.code &&
                b[i - 1].toInt() == '\r'.code && b[i].toInt() == '\n'.code
            ) return true
        }
        return false
    }

    /** Server Name Indication from a ClientHello, or null if absent or truncated. */
    fun tlsSni(b: ByteArray, len: Int): String? {
        val (start, end) = tlsExtension(b, len, EXT_SERVER_NAME) ?: return null
        // server_name_list length (2), then entries of type (1), length (2), name
        var j = start + 2
        while (j + 3 <= end) {
            val nameType = Ip4.u8(b, j)
            val nameLen = Ip4.u16(b, j + 1)
            if (nameType == 0 && j + 3 + nameLen <= end) return String(b, j + 3, nameLen, Charsets.US_ASCII)
            j += 3 + nameLen
        }
        return null
    }

    /** The protocols a ClientHello offers with ALPN (e.g. "h2", "http/1.1"); empty if it offers none. */
    fun tlsAlpn(b: ByteArray, len: Int): List<String> {
        val (start, end) = tlsExtension(b, len, EXT_ALPN) ?: return emptyList()
        // protocol_name_list length (2), then entries of length (1), name
        val protocols = mutableListOf<String>()
        var j = start + 2
        while (j + 1 <= end) {
            val n = Ip4.u8(b, j)
            if (n == 0 || j + 1 + n > end) break
            protocols += String(b, j + 1, n, Charsets.US_ASCII)
            j += 1 + n
        }
        return protocols
    }

    /** True if the ClientHello says it will speak HTTP inside TLS, which is what ZAP can read. */
    fun tlsOffersHttp(b: ByteArray, len: Int) = tlsAlpn(b, len).any { it in HTTP_ALPN }

    private val HTTP_ALPN = setOf("http/1.1", "http/1.0", "h2")
    private const val EXT_SERVER_NAME = 0x0000
    private const val EXT_ALPN = 0x0010

    /** Where a ClientHello extension's data lies, as (start, end exclusive), or null if absent or truncated. */
    private fun tlsExtension(b: ByteArray, len: Int, wanted: Int): Pair<Int, Int>? {
        try {
            val end = minOf(len, tlsRecordEnd(b, len))
            var i = 5
            if (Ip4.u8(b, i) != 0x01) return null // ClientHello
            i += 4 // handshake type + length
            i += 2 + 32 // client version + random
            i += 1 + Ip4.u8(b, i) // session id
            i += 2 + Ip4.u16(b, i) // cipher suites
            i += 1 + Ip4.u8(b, i) // compression methods
            val extEnd = minOf(end, i + 2 + Ip4.u16(b, i))
            i += 2
            while (i + 4 <= extEnd) {
                val type = Ip4.u16(b, i)
                val extLen = Ip4.u16(b, i + 2)
                i += 4
                if (type == wanted) return i to minOf(i + extLen, end)
                i += extLen
            }
        } catch (_: IndexOutOfBoundsException) {
        }
        return null
    }

    /** True for requests meant for a proxy (CONNECT or an absolute URI), from apps using the system proxy. */
    fun isProxyRequest(b: ByteArray, len: Int): Boolean {
        val line = String(b, 0, minOf(len, 16), Charsets.ISO_8859_1)
        return line.startsWith("CONNECT ") || line.substringAfter(' ').startsWith("http://", ignoreCase = true)
    }

    /** Host header from an HTTP/1.x request, without the port. */
    fun httpHost(b: ByteArray, len: Int): String? {
        val text = String(b, 0, len, Charsets.ISO_8859_1)
        val line = text.split("\r\n").drop(1).takeWhile { it.isNotEmpty() }
            .firstOrNull { it.startsWith("host:", ignoreCase = true) } ?: return null
        val host = line.substring(5).trim()
        if (host.isEmpty()) return null
        if (host.startsWith("[")) return host.substringBefore(']') + "]"
        return host.substringBefore(':')
    }
}
