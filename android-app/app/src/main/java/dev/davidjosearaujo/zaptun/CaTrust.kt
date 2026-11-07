package dev.davidjosearaujo.zaptun

import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Whether ZAP's root CA is in the device's system and user CA stores. */
object CaTrust {
    data class Status(val system: Boolean, val user: Boolean, val name: String?)

    /**
     * Looks for the CA with [sha256] (hex, from the ZAP add-on) in the "AndroidCAStore" key store,
     * whose aliases are "system:<hash>.0" and "user:<hash>.0". Without a fingerprint, any CA
     * that looks like ZAP's counts.
     */
    fun check(sha256: String?): Status {
        var system = false
        var user = false
        var name: String? = null
        val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        for (alias in ks.aliases()) {
            val cert = ks.getCertificate(alias) as? X509Certificate ?: continue
            if (!matches(cert, sha256)) continue
            name = cert.subjectX500Principal.name.split(',')
                .firstOrNull { it.startsWith("CN=") }?.removePrefix("CN=") ?: name
            if (alias.startsWith("system:")) system = true
            if (alias.startsWith("user:")) user = true
        }
        return Status(system, user, name)
    }

    private fun matches(cert: X509Certificate, sha256: String?): Boolean {
        if (sha256 != null) {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            return digest.joinToString("") { "%02x".format(it) }.equals(sha256, ignoreCase = true)
        }
        val subject = cert.subjectX500Principal.name
        return "Zed Attack Proxy" in subject || "O=ZAP Root CA" in subject
    }
}
