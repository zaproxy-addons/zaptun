/*
 * zaptun: capture all traffic of Android devices in ZAP.
 *
 * Copyright 2026 David Araújo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.davidjosearaujo.zaptun.zap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

/** The file name Android expects for a CA in its store: {@code <subject_hash_old>.0}. */
public final class CaHash {

    private CaHash() {}

    /**
     * Same value as {@code openssl x509 -subject_hash_old}: MD5 of the DER subject, first 4 bytes,
     * little-endian.
     */
    public static String subjectHashOld(X509Certificate cert) {
        try {
            byte[] d =
                    MessageDigest.getInstance("MD5")
                            .digest(cert.getSubjectX500Principal().getEncoded());
            long h =
                    (d[0] & 0xffL)
                            | (d[1] & 0xffL) << 8
                            | (d[2] & 0xffL) << 16
                            | (d[3] & 0xffL) << 24;
            return String.format("%08x", h);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static X509Certificate readPem(Path pem) throws IOException {
        try (InputStream in = Files.newInputStream(pem)) {
            return (X509Certificate)
                    CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (GeneralSecurityException e) {
            throw new IOException("Not a valid certificate: " + pem, e);
        }
    }
}
