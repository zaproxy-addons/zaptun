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
import static org.junit.Assert.assertFalse;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AdbLocatorTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path fakeAdb(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path adb = dir.resolve("adb");
        Files.writeString(adb, "#!/bin/sh\n");
        adb.toFile().setExecutable(true);
        return adb;
    }

    private AdbLocator locator(Map<String, String> env) {
        return new AdbLocator(env::get, tmp.getRoot().toString(), false);
    }

    @Test
    public void prefersAndroidHome() throws IOException {
        Path sdk = tmp.getRoot().toPath().resolve("sdk");
        Path adb = fakeAdb(sdk.resolve("platform-tools"));
        fakeAdb(tmp.getRoot().toPath().resolve("Android/Sdk/platform-tools")); // default folder too

        AdbLocator l = locator(Map.of("ANDROID_HOME", sdk.toString()));
        assertEquals(sdk, l.sdk().get().path());
        assertEquals("ANDROID_HOME", l.sdk().get().source());
        assertEquals(adb, l.adb().get().path());
    }

    @Test
    public void skipsEnvVarsWithoutAdb() throws IOException {
        Path empty = tmp.newFolder("empty").toPath();
        Path sdk = tmp.getRoot().toPath().resolve("Android/Sdk");
        fakeAdb(sdk.resolve("platform-tools"));

        AdbLocator l = locator(Map.of("ANDROID_HOME", empty.toString()));
        assertEquals(sdk, l.sdk().get().path());
        assertEquals("default SDK folder", l.sdk().get().source());
    }

    @Test
    public void fallsBackToPath() throws IOException {
        Path bin = tmp.newFolder("bin").toPath();
        Path adb = fakeAdb(bin);

        AdbLocator l = locator(Map.of("PATH", "/nonexistent" + File.pathSeparator + bin));
        assertFalse(l.sdk().isPresent());
        assertEquals(adb, l.adb().get().path());
        assertEquals("PATH", l.adb().get().source());
    }

    @Test
    public void nothingFound() {
        AdbLocator l = locator(Map.of());
        assertFalse(l.sdk().isPresent());
        assertFalse(l.adb().isPresent());
    }

    @Test
    public void adbUsesSettingsFirst() throws IOException {
        Path sdk = tmp.getRoot().toPath().resolve("custom-sdk");
        Path sdkAdb = fakeAdb(sdk.resolve("platform-tools"));
        AdbLocator l = locator(Map.of());

        assertEquals(
                "/opt/adb",
                new Adb(() -> new Adb.Config("/opt/adb", sdk.toString()), l).executable());
        assertEquals(
                sdkAdb.toString(),
                new Adb(() -> new Adb.Config("", sdk.toString()), l).executable());
        assertEquals("adb", new Adb(() -> new Adb.Config("", ""), l).executable());
    }
}
