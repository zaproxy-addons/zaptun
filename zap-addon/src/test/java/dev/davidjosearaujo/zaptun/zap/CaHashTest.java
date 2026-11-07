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

import static org.junit.Assert.assertEquals;

import java.nio.file.Path;
import org.junit.Test;

public class CaHashTest {

    @Test
    public void matchesOpensslSubjectHashOld() throws Exception {
        // openssl x509 -in isrg-root-x1.pem -noout -subject_hash_old  ->  6187b673
        Path pem = Path.of(getClass().getResource("/isrg-root-x1.pem").toURI());
        assertEquals("6187b673", CaHash.subjectHashOld(CaHash.readPem(pem)));
    }
}
