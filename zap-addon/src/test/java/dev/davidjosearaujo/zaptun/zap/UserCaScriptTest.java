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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Runs install_user_ca.sh in a sandbox: the Android-only tools (chown, restorecon) are shims that
 * log.
 */
public class UserCaScriptTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private Path script;
    private Path bin;
    private Path source;
    private Path userDir;

    @Before
    public void setUp() throws IOException {
        Assume.assumeTrue("needs a POSIX shell", new File("/bin/sh").canExecute());
        Path root = tmp.getRoot().toPath();
        script = root.resolve("install_user_ca.sh");
        Files.write(script, DeviceManager.resource("install_user_ca.sh"));
        source = Files.createDirectories(root.resolve("data-local-tmp"));
        Files.writeString(
                source.resolve("6187b673.0"),
                "-----BEGIN CERTIFICATE-----\nfake\n-----END CERTIFICATE-----\n");
        userDir = root.resolve("user/0/cacerts-added");
        bin = Files.createDirectories(root.resolve("bin"));
        shim("id", "echo 0");
        shim("chown", "echo chown \"$@\" >> \"" + root.resolve("calls") + "\"");
        shim("restorecon", "echo restorecon \"$@\" >> \"" + root.resolve("calls") + "\"");
    }

    private void shim(String name, String body) throws IOException {
        Path f = bin.resolve(name);
        Files.writeString(f, "#!/bin/sh\n" + body + "\n");
        f.toFile().setExecutable(true);
    }

    private String run(String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("/bin/sh", script.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("PATH", bin + File.pathSeparator + System.getenv("PATH"));
        pb.environment().put("ZAPTUN_TMP_DIR", source.toString());
        pb.environment().put("ZAPTUN_USER_CA_DIR", userDir.toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        lastExit = p.waitFor();
        return out.trim();
    }

    private int lastExit;

    private String calls() throws IOException {
        Path f = tmp.getRoot().toPath().resolve("calls");
        return Files.exists(f) ? Files.readString(f) : "";
    }

    @Test
    public void installsTheCertificateWorldReadable() throws Exception {
        assertEquals("Installed certificate 6187b673.0 as a user CA.", run("6187b673"));
        assertEquals(0, lastExit);

        Path installed = userDir.resolve("6187b673.0");
        assertEquals(Files.readString(source.resolve("6187b673.0")), Files.readString(installed));
        // Apps read this directory themselves, so both must be world-readable.
        assertEquals(
                "rw-r--r--",
                PosixFilePermissions.toString(Files.getPosixFilePermissions(installed)));
        assertEquals(
                "rwxr-xr-x", PosixFilePermissions.toString(Files.getPosixFilePermissions(userDir)));
        // Owned by system and labelled like Android's own installer would.
        assertTrue(calls(), calls().contains("chown system:system " + userDir));
        assertTrue(calls(), calls().contains("restorecon -R " + userDir));
    }

    @Test
    public void acceptsTheHashWithItsSuffix() throws Exception {
        run("6187b673.0");
        assertTrue(Files.exists(userDir.resolve("6187b673.0")));
    }

    @Test
    public void skipsWhenAlreadyInstalled() throws Exception {
        run("6187b673");
        Files.delete(tmp.getRoot().toPath().resolve("calls"));

        assertEquals(
                "Certificate 6187b673.0 is already installed as a user CA, skipping.",
                run("6187b673"));
        assertEquals(0, lastExit);
        assertEquals("", calls());
    }

    @Test
    public void replacesADifferentCertificateWithTheSameName() throws Exception {
        run("6187b673");
        Files.writeString(source.resolve("6187b673.0"), "newer certificate");

        run("6187b673");
        assertEquals("newer certificate", Files.readString(userDir.resolve("6187b673.0")));
    }

    @Test
    public void failsWithoutRoot() throws Exception {
        shim("id", "echo 2000");
        assertEquals("Error: must run as root (run 'adb root' first).", run("6187b673"));
        assertEquals(1, lastExit);
        assertFalse(Files.exists(userDir));
    }

    @Test
    public void failsWhenTheCertificateWasNotPushed() throws Exception {
        assertTrue(run("deadbeef").startsWith("Error: "));
        assertEquals(1, lastExit);
        assertFalse(Files.exists(userDir));
    }
}
