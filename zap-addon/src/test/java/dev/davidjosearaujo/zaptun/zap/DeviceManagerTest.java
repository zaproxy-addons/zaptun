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
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class DeviceManagerTest {

    /**
     * A pretend "Google APIs" emulator: debuggable, not root until "adb root", zaptun not
     * installed.
     */
    private static class FakeEmulator implements Adb.Runner {
        final List<String> calls = new ArrayList<>();
        boolean root;
        boolean installed;
        boolean running;
        boolean debuggable = true;
        boolean userCaFails;
        boolean grantFails;
        boolean su;

        @Override
        public Adb.Result run(String serial, Duration timeout, List<String> args) {
            String cmd = String.join(" ", args);
            calls.add(cmd);
            if (cmd.equals("devices -l")) {
                return ok(
                        "List of devices attached\nemulator-5554 device model:Pixel_8 transport_id:1\n");
            }
            if (cmd.equals("root")) {
                root = true;
                return ok("restarting adbd as root\n");
            }
            if (cmd.startsWith("install")) {
                installed = true;
                return ok("Performing Streamed Install\nSuccess\n");
            }
            if (cmd.startsWith("push") || cmd.startsWith("reverse")) {
                return ok("");
            }
            String shell = cmd.substring("shell ".length());
            if (shell.equals(DeviceManager.PROBE)) {
                return ok(
                        "uid="
                                + (root ? "0" : "2000")
                                + "\ndebuggable="
                                + (debuggable ? "1" : "0")
                                + "\nrelease=15\nsdk=35\nsu="
                                + (su ? "/system/bin/su" : "")
                                + "\npkg="
                                + (installed ? "package:/data/app/x/base.apk" : "")
                                + "\n");
            }
            if (shell.equals("id -u")) return ok(root ? "0\n" : "2000\n");
            if (shell.equals("su -c 'id -u'"))
                return su ? ok("0\n") : new Adb.Result(1, "", "denied");
            if (su && shell.startsWith("su -c '/data/local/tmp/")) return ok("done\n");
            if (shell.startsWith("pm path")) {
                return installed ? ok("package:/data/app/x/base.apk\n") : new Adb.Result(1, "", "");
            }
            if (shell.startsWith("sha256sum")) return ok("0000  /data/app/x/base.apk\n");
            if (shell.startsWith("pm grant ")) {
                return grantFails
                        ? new Adb.Result(1, "", "Security exception: not a changeable permission")
                        : ok("");
            }
            if (shell.startsWith("/data/local/tmp/install_user_ca.sh")) {
                return userCaFails
                        ? new Adb.Result(1, "", "restorecon: not found")
                        : ok("Installed certificate 6187b673.0 as a user CA.\n");
            }
            if (shell.startsWith("/data/local/tmp/inject_ca.sh")) {
                return root
                        ? ok(
                                "Successfully injected certificate 6187b673.0 into 12 Conscrypt APEX mount namespaces.\n")
                        : new Adb.Result(1, "Error: must run as root\n", "");
            }
            if (shell.contains("--ez start true")) running = true;
            if (shell.contains("--ez stop true")) running = false;
            if (shell.startsWith("dumpsys activity services")) {
                return ok(
                        running
                                ? "  * ServiceRecord{1 u0 dev.davidjosearaujo.zaptun/.ZapVpnService}\n"
                                : "  (nothing)\n");
            }
            return ok("");
        }

        boolean called(String prefix) {
            return calls.stream().anyMatch(c -> c.startsWith(prefix));
        }

        String find(String prefix) {
            return calls.stream().filter(c -> c.startsWith(prefix)).findFirst().orElse("");
        }

        private static Adb.Result ok(String out) {
            return new Adb.Result(0, out, "");
        }
    }

    private static final DeviceManager.Zap ZAP =
            new DeviceManager.Zap() {
                @Override
                public void writeRootCaPem(Path file) throws IOException {
                    try (var in = getClass().getResourceAsStream("/isrg-root-x1.pem")) {
                        Files.write(file, in.readAllBytes());
                    }
                }

                @Override
                public int proxyPort() {
                    return 9090;
                }

                @Override
                public String proxyAddress() {
                    return "localhost";
                }
            };

    @Test
    public void interceptSetsUpEverything() throws IOException {
        FakeEmulator emu = new FakeEmulator();
        DeviceManager dm = new DeviceManager(emu, ZAP);
        List<String> log = new ArrayList<>();

        dm.intercept("emulator-5554", List.of("com.example.app"), log::add);

        assertTrue(emu.called("root"));
        assertTrue(emu.called("install -r -t "));
        assertTrue(emu.called("shell appops set dev.davidjosearaujo.zaptun ACTIVATE_VPN allow"));
        assertTrue(
                emu.called(
                        "shell pm grant dev.davidjosearaujo.zaptun android.permission.WRITE_SECURE_SETTINGS"));
        assertTrue(
                emu.called(
                        "shell pm grant dev.davidjosearaujo.zaptun android.permission.POST_NOTIFICATIONS"));
        assertTrue(emu.find("push ").contains("6187b673.0"));
        assertTrue(emu.called("shell /data/local/tmp/inject_ca.sh 6187b673"));
        assertTrue(emu.called("shell /data/local/tmp/install_user_ca.sh 6187b673"));
        assertTrue(emu.find("push ").contains("install_user_ca.sh"));
        assertTrue(emu.called("reverse tcp:9090 tcp:9090"));
        String start = emu.find("shell am start");
        // ca_sha256 is ISRG Root X1's SHA-256: openssl x509 -in isrg-root-x1.pem -noout
        // -fingerprint -sha256
        assertTrue(
                start,
                start.contains(
                        "--es host 127.0.0.1 --ei port 9090 --es apps 'com.example.app'"
                                + " --es ca_sha256 96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6"
                                + " --ez start true"));
        // Root and the CA come before the VPN starts.
        assertTrue(
                emu.calls.indexOf(emu.find("shell /data/local/tmp/inject_ca.sh"))
                        < emu.calls.indexOf(start));
        assertTrue(log.get(log.size() - 1), log.get(log.size() - 1).startsWith("Done"));

        DeviceManager.Device d = dm.devices().get(0);
        assertEquals("Pixel 8", d.model());
        assertEquals(35, d.sdk());
        assertTrue(d.intercepting());

        dm.stop("emulator-5554", log::add);
        assertTrue(emu.called("reverse --remove tcp:9090"));
        assertFalse(dm.devices().get(0).intercepting());
    }

    @Test
    public void alwaysOnVpnIsOnWhileInterceptingAndOffBeforeStopping() throws IOException {
        FakeEmulator emu = new FakeEmulator();
        DeviceManager dm = new DeviceManager(emu, ZAP);

        dm.intercept("emulator-5554", List.of(), s -> {});
        String on = "shell " + DeviceManager.ALWAYS_ON + "on";
        assertTrue(emu.called(on));
        // After the VPN is up: lockdown before that would leave the device with no network
        // meanwhile.
        assertTrue(emu.calls.indexOf(emu.find("shell am start")) < emu.calls.indexOf(on));

        dm.stop("emulator-5554", s -> {});
        String off = "shell " + DeviceManager.ALWAYS_ON + "off";
        assertTrue(emu.called(off));
        assertTrue(
                emu.calls.lastIndexOf(off)
                        < emu.calls.indexOf(
                                emu.find(
                                        "shell am start -n "
                                                + DeviceManager.ACTIVITY
                                                + " --ez stop true")));
    }

    @Test
    public void alwaysOnRunsThroughSuOnRootedPhones() throws IOException {
        FakeEmulator phone = new FakeEmulator();
        phone.debuggable = false;
        phone.su = true;

        new DeviceManager(phone, ZAP).intercept("emulator-5554", List.of(), s -> {});

        assertTrue(phone.called("shell su -c '" + DeviceManager.ALWAYS_ON + "on'"));
    }

    @Test
    public void userCaComesAfterTheSystemCaAndIsNotRequired() throws IOException {
        FakeEmulator emu = new FakeEmulator();
        emu.userCaFails = true;
        List<String> log = new ArrayList<>();

        new DeviceManager(emu, ZAP).intercept("emulator-5554", List.of(), log::add);

        assertTrue(
                emu.calls.indexOf(emu.find("shell /data/local/tmp/inject_ca.sh"))
                        < emu.calls.indexOf(emu.find("shell /data/local/tmp/install_user_ca.sh")));
        assertTrue(
                log.stream()
                        .anyMatch(l -> l.startsWith("Could not also install the CA as a user CA")));
        // The VPN still started.
        assertTrue(emu.called("shell am start"));
        assertTrue(log.get(log.size() - 1).startsWith("Done"));
    }

    @Test
    public void notBeingAbleToGrantTheSettingsPermissionIsNotFatal() throws IOException {
        FakeEmulator emu = new FakeEmulator();
        emu.grantFails = true;
        List<String> log = new ArrayList<>();

        new DeviceManager(emu, ZAP).intercept("emulator-5554", List.of(), log::add);

        // One message, for the permission that matters: not one per permission.
        assertEquals(
                1,
                log.stream()
                        .filter(
                                l ->
                                        l.startsWith(
                                                "Could not let the app manage Android's global proxy"))
                        .count());
        assertFalse(log.stream().anyMatch(l -> l.contains("POST_NOTIFICATIONS")));
        assertTrue(emu.called("shell am start"));
        assertTrue(log.get(log.size() - 1).startsWith("Done"));
    }

    @Test
    public void deviceListNeedsOneProbePerDevice() throws IOException {
        FakeEmulator emu = new FakeEmulator();
        new DeviceManager(emu, ZAP).devices();

        // zaptun isn't installed, so there's nothing else to ask: no dumpsys, no per-property
        // calls.
        assertEquals(List.of("devices -l", "shell " + DeviceManager.PROBE), emu.calls);
    }

    @Test
    public void parsesProbeOutput() {
        // "pm path" output can contain '=' (base64-style directory names), which must not confuse
        // parsing.
        DeviceManager.Probe p =
                DeviceManager.parseProbe(
                        "uid=2000\ndebuggable=0\nrelease=14\nsdk=34\nsu=/system/xbin/su\n"
                                + "pkg=package:/data/app/~~abc==/dev.x-xyz==/base.apk\n");
        assertFalse(p.isRoot());
        assertEquals("14", p.release());
        assertEquals(34, p.sdk());
        assertTrue(p.installed());
        assertEquals(DeviceManager.Root.SU, p.root());

        DeviceManager.Probe bare =
                DeviceManager.parseProbe("uid=2000\ndebuggable=0\nrelease=\nsdk=\nsu=\npkg=\n");
        assertEquals(DeviceManager.Root.NONE, bare.root());
        assertFalse(bare.installed());
        assertEquals(0, bare.sdk());

        assertEquals(DeviceManager.Root.ADBD, DeviceManager.parseProbe("uid=0\n").root());
        assertEquals(
                DeviceManager.Root.ADBD,
                DeviceManager.parseProbe("uid=2000\ndebuggable=1\n").root());
    }

    @Test
    public void refusesDevicesWithoutRoot() throws IOException {
        FakeEmulator phone = new FakeEmulator();
        phone.debuggable = false;
        try {
            new DeviceManager(phone, ZAP).intercept("emulator-5554", List.of(), s -> {});
            fail("expected an error");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("No root"));
        }
        assertFalse(phone.called("install"));
    }

    @Test
    public void rejectsBadPackageNames() {
        try {
            new DeviceManager(new FakeEmulator(), ZAP)
                    .intercept("emulator-5554", List.of("x'; reboot; '"), s -> {});
            fail("expected an error");
        } catch (IOException e) {
            assertTrue(e.getMessage().startsWith("Not a package name"));
        }
    }
}
