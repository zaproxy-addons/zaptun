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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Everything zaptun does to a device: install the VPN app, inject ZAP's root CA into the system
 * store, tunnel the device's {@code 127.0.0.1:<port>} to ZAP with {@code adb reverse}, and start or
 * stop the VPN.
 */
public class DeviceManager {

    public static final String PACKAGE = "dev.davidjosearaujo.zaptun";
    static final String ACTIVITY = PACKAGE + "/.MainActivity";
    static final String SERVICE = "ZapVpnService";
    static final String REMOTE_DIR = "/data/local/tmp";
    static final String RESOURCES = "/dev/davidjosearaujo/zaptun/zap/resources/";

    /**
     * Runs the app's AlwaysOnVpn tool (as root, from zaptun's own APK), followed by "on" or "off".
     * Only system code may set the always-on VPN, so the app can't do it itself.
     */
    static final String ALWAYS_ON =
            "CLASSPATH=$(pm path "
                    + PACKAGE
                    + " | head -n 1 | cut -d: -f2) app_process /system/bin "
                    + PACKAGE
                    + ".AlwaysOnVpn ";

    private static final Duration SHORT = Duration.ofSeconds(20);
    private static final Duration LONG = Duration.ofMinutes(3);
    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");

    /** How root commands are run on a device. */
    public enum Root {
        /** adbd already runs as root, or {@code adb root} works (emulator "Google APIs" images). */
        ADBD,
        /** A su binary (e.g. Magisk) is available to the shell user. */
        SU,
        NONE
    }

    /** What the UI and API show for a connected device. */
    public record Device(
            String serial,
            String state,
            String model,
            String androidVersion,
            int sdk,
            Root root,
            boolean installed,
            boolean intercepting) {

        public boolean ready() {
            return "device".equals(state);
        }
    }

    /**
     * Everything the device list needs, from one adb round trip (each adb call costs a process
     * start and a USB or TCP exchange, so asking one thing at a time made refreshing slow).
     */
    record Probe(
            boolean isRoot,
            boolean debuggable,
            String release,
            int sdk,
            boolean hasSu,
            boolean installed) {
        /** How to get root: already root or "adb root" works on debuggable builds, else su. */
        Root root() {
            if (isRoot || debuggable) {
                return Root.ADBD;
            }
            return hasSu ? Root.SU : Root.NONE;
        }
    }

    /** The shell script behind {@link Probe}: one key=value line per fact. */
    static final String PROBE =
            "echo uid=$(id -u); echo debuggable=$(getprop ro.debuggable);"
                    + " echo release=$(getprop ro.build.version.release);"
                    + " echo sdk=$(getprop ro.build.version.sdk); echo su=$(command -v su);"
                    + " echo pkg=$(pm path "
                    + PACKAGE
                    + " | head -n 1)";

    static Probe parseProbe(String out) {
        Map<String, String> values = new HashMap<>();
        for (String line : out.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        return new Probe(
                "0".equals(values.get("uid")),
                "1".equals(values.get("debuggable")),
                values.getOrDefault("release", ""),
                parseInt(values.getOrDefault("sdk", "")),
                !values.getOrDefault("su", "").isEmpty(),
                !values.getOrDefault("pkg", "").isEmpty());
    }

    private Probe probe(String serial) throws IOException {
        return parseProbe(shell(serial, PROBE));
    }

    /** Supplies ZAP's side: the root CA and the port its proxy listens on. */
    public interface Zap {
        /** Writes ZAP's root CA certificate, PEM encoded, to the given file. */
        void writeRootCaPem(Path file) throws IOException;

        int proxyPort();

        /** Address the main proxy is bound to, to warn when adb reverse can't reach it. */
        String proxyAddress();
    }

    private final Adb.Runner adb;
    private final Zap zap;

    public DeviceManager(Adb.Runner adb, Zap zap) {
        this.adb = adb;
        this.zap = zap;
    }

    public List<Device> devices() throws IOException {
        Adb.Result r = adb.run(null, SHORT, List.of("devices", "-l"));
        if (!r.ok()) {
            throw new IOException("adb devices failed: " + r.text());
        }
        List<Device> devices = new ArrayList<>();
        for (Adb.DeviceEntry e : Adb.parseDevices(r.out())) {
            String model = e.props().getOrDefault("model", "").replace('_', ' ');
            if (!"device".equals(e.state())) {
                devices.add(
                        new Device(e.serial(), e.state(), model, "", 0, Root.NONE, false, false));
                continue;
            }
            Probe p = probe(e.serial());
            devices.add(
                    new Device(
                            e.serial(),
                            e.state(),
                            model,
                            p.release(),
                            p.sdk(),
                            p.root(),
                            p.installed(),
                            p.installed() && isRunning(e.serial())));
        }
        return devices;
    }

    /** The serial to use when the caller didn't pick one: the only ready device. */
    public String defaultSerial() throws IOException {
        List<Device> ready = devices().stream().filter(Device::ready).toList();
        if (ready.size() == 1) {
            return ready.get(0).serial();
        }
        throw new IOException(
                ready.isEmpty()
                        ? "No Android device found. Start an emulator or connect a device with USB debugging."
                        : "More than one device is connected, pick one.");
    }

    public static boolean isPackageName(String s) {
        return PACKAGE_NAME.matcher(s).matches();
    }

    /** Third-party packages installed on the device, sorted. */
    public List<String> userPackages(String serial) throws IOException {
        return Arrays.stream(shell(serial, "pm list packages -3").split("\n"))
                .map(String::trim)
                .filter(l -> l.startsWith("package:"))
                .map(l -> l.substring("package:".length()))
                .filter(p -> !p.equals(PACKAGE))
                .sorted()
                .toList();
    }

    /** Sets up the device and starts sending its traffic to ZAP. */
    public void intercept(String serial, List<String> apps, Consumer<String> log)
            throws IOException {
        for (String app : apps) {
            if (!isPackageName(app)) {
                throw new IOException("Not a package name: " + app);
            }
        }
        int port = zap.proxyPort();
        String address = zap.proxyAddress();
        if (address != null && !isLocal(address)) {
            log.accept(
                    "Warning: ZAP's proxy is bound to "
                            + address
                            + ", but adb reverse connects to localhost. Bind it to localhost or 0.0.0.0 if the device gets no traffic.");
        }

        log.accept("Checking root access...");
        Root root = ensureRoot(serial);
        log.accept("Root via " + (root == Root.ADBD ? "adbd (adb root)" : "su"));

        installApp(serial, log);
        shell(serial, "appops set " + PACKAGE + " ACTIVATE_VPN allow");
        grantPermissions(serial, log);

        String caSha256 = injectCa(serial, root, log);

        log.accept("Tunnelling the device's 127.0.0.1:" + port + " to ZAP (adb reverse)...");
        check(
                adb.run(serial, SHORT, List.of("reverse", "tcp:" + port, "tcp:" + port)),
                "adb reverse");

        log.accept(
                "Starting the VPN"
                        + (apps.isEmpty() ? " for all apps" : " for " + String.join(", ", apps))
                        + "...");
        shell(
                serial,
                "am start -n "
                        + ACTIVITY
                        + " --es host 127.0.0.1 --ei port "
                        + port
                        + " --es apps '"
                        + String.join(",", apps)
                        + "' --es ca_sha256 "
                        + caSha256
                        + " --ez start true");

        for (int i = 0; i < 20; i++) {
            if (isRunning(serial)) {
                alwaysOn(serial, root, "on", log);
                log.accept(
                        "Done: " + serial + " is sending its traffic to ZAP on port " + port + ".");
                return;
            }
            sleep(500);
        }
        throw new IOException(
                "The VPN didn't come up. Open the zaptun app on the device to see why.");
    }

    public void stop(String serial, Consumer<String> log) throws IOException {
        log.accept("Stopping the VPN on " + serial + "...");
        // First: with lockdown still on, the device would have no network once the VPN is off.
        try {
            alwaysOn(serial, ensureRoot(serial), "off", log);
        } catch (IOException e) {
            log.accept("Could not turn Android's always-on VPN off: " + e.getMessage());
        }
        shell(serial, "am start -n " + ACTIVITY + " --ez stop true");
        adb.run(serial, SHORT, List.of("reverse", "--remove", "tcp:" + zap.proxyPort()));
        for (int i = 0; i < 10 && isRunning(serial); i++) {
            sleep(500);
        }
        log.accept("Stopped.");
    }

    /**
     * Makes zaptun Android's always-on VPN, with lockdown ("on"), or undoes it ("off"), so nothing
     * leaves the device around ZAP while zaptun reconnects, restarts or isn't running yet. Not
     * fatal: interception works without it.
     */
    private void alwaysOn(String serial, Root root, String command, Consumer<String> log) {
        try {
            String out = runAsRoot(serial, root, ALWAYS_ON + command).trim();
            if (!out.isEmpty()) {
                log.accept(out);
            }
        } catch (IOException e) {
            log.accept("Could not turn Android's always-on VPN " + command + ": " + e.getMessage());
        }
    }

    private Root ensureRoot(String serial) throws IOException {
        Probe probe = probe(serial);
        Root root = probe.root();
        if (root == Root.NONE) {
            throw new IOException(
                    "No root on "
                            + serial
                            + ". Use an emulator with a \"Google APIs\" image (not \"Google Play\") or a rooted device.");
        }
        if (root == Root.ADBD && !probe.isRoot()) {
            adb.run(serial, SHORT, List.of("root"));
            // adbd restarts as root; wait for the device to come back.
            for (int i = 0; i < 30 && !isRoot(serial, "id -u"); i++) {
                sleep(1000);
            }
            if (!isRoot(serial, "id -u")) {
                throw new IOException("adb root didn't give a root shell on " + serial);
            }
        } else if (root == Root.SU && !isRoot(serial, "su -c 'id -u'")) {
            throw new IOException(
                    "su was denied. Allow root for \"Shell\" on the device and retry.");
        }
        return root;
    }

    private void installApp(String serial, Consumer<String> log) throws IOException {
        byte[] apk = resource("zaptun.apk");
        String wanted = sha256(apk);
        String installedPath =
                shellOrEmpty(serial, "pm path " + PACKAGE).replace("package:", "").trim();
        if (!installedPath.isEmpty()) {
            String current = shellOrEmpty(serial, "sha256sum " + installedPath.split("\n")[0]);
            if (current.startsWith(wanted)) {
                log.accept("zaptun app is up to date.");
                return;
            }
        }
        log.accept(
                installedPath.isEmpty()
                        ? "Installing the zaptun app..."
                        : "Updating the zaptun app...");
        Path tmp = Files.createTempFile("zaptun", ".apk");
        try {
            Files.write(tmp, apk);
            Adb.Result r = adb.run(serial, LONG, List.of("install", "-r", "-t", tmp.toString()));
            if (r.text().contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE")) {
                // Signed with a different key (e.g. built on another machine): reinstall from
                // scratch.
                log.accept("Installed copy has a different signature, reinstalling...");
                adb.run(serial, SHORT, List.of("uninstall", PACKAGE));
                r = adb.run(serial, LONG, List.of("install", "-t", tmp.toString()));
            }
            if (!r.ok() || !r.text().contains("Success")) {
                throw new IOException("Installing the zaptun app failed: " + r.text());
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * Lets the app switch off Android's global proxy while it is connected (and put it back), which
     * apps that honour it would otherwise use instead of the VPN, and show its notification. Not
     * fatal: without the first the app shows a note on screen saying how to grant it.
     */
    private void grantPermissions(String serial, Consumer<String> log) {
        try {
            shell(serial, "pm grant " + PACKAGE + " android.permission.WRITE_SECURE_SETTINGS");
        } catch (IOException e) {
            log.accept("Could not let the app manage Android's global proxy: " + e.getMessage());
        }
        // So the "intercepting" notification can be shown. Android before 13 has no such permission
        // to grant, and without it the app still works, so a failure here isn't worth a message.
        try {
            shell(serial, "pm grant " + PACKAGE + " android.permission.POST_NOTIFICATIONS");
        } catch (IOException e) {
            // ignored on purpose
        }
    }

    /** Injects ZAP's CA and returns its SHA-256 fingerprint, which the app uses to check trust. */
    private String injectCa(String serial, Root root, Consumer<String> log) throws IOException {
        Path dir = Files.createTempDirectory("zaptun");
        try {
            Path pem = dir.resolve("ca.pem");
            zap.writeRootCaPem(pem);
            X509Certificate ca = CaHash.readPem(pem);
            String hash = CaHash.subjectHashOld(ca);
            Path cert = Files.move(pem, dir.resolve(hash + ".0"));
            Path systemScript = dir.resolve("inject_ca.sh");
            Path userScript = dir.resolve("install_user_ca.sh");
            Files.write(systemScript, resource("inject_ca.sh"));
            Files.write(userScript, resource("install_user_ca.sh"));

            log.accept("Injecting ZAP's root CA (" + hash + ".0) into the system store...");
            check(
                    adb.run(
                            serial,
                            SHORT,
                            List.of(
                                    "push",
                                    cert.toString(),
                                    systemScript.toString(),
                                    userScript.toString(),
                                    REMOTE_DIR + "/")),
                    "adb push");
            shell(
                    serial,
                    "chmod 755 "
                            + REMOTE_DIR
                            + "/inject_ca.sh "
                            + REMOTE_DIR
                            + "/install_user_ca.sh");
            log.accept(runAsRoot(serial, root, REMOTE_DIR + "/inject_ca.sh " + hash).trim());

            // Also a user CA: it lives in /data, so processes whose mount
            // namespace missed the system store change still trust it. A bonus, so not fatal.
            try {
                log.accept(
                        runAsRoot(serial, root, REMOTE_DIR + "/install_user_ca.sh " + hash).trim());
            } catch (IOException e) {
                log.accept("Could not also install the CA as a user CA: " + e.getMessage());
            }
            return sha256(ca.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IOException("Unreadable CA certificate", e);
        } finally {
            try (var files = Files.list(dir)) {
                for (Path f : files.toList()) {
                    Files.deleteIfExists(f);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    boolean isRunning(String serial) throws IOException {
        String out = shellOrEmpty(serial, "dumpsys activity services " + PACKAGE);
        return out.contains(SERVICE);
    }

    /** Runs a shell command as root: directly when adbd is root, through su otherwise. */
    private String runAsRoot(String serial, Root root, String cmd) throws IOException {
        return shell(serial, root == Root.SU ? "su -c '" + cmd + "'" : cmd);
    }

    private String shell(String serial, String cmd) throws IOException {
        return check(adb.run(serial, SHORT, List.of("shell", cmd)), cmd).out();
    }

    private String shellOrEmpty(String serial, String cmd) throws IOException {
        Adb.Result r = adb.run(serial, SHORT, List.of("shell", cmd));
        return r.ok() ? r.out() : "";
    }

    /** True if the uid-printing command reports root. Doesn't throw while adbd restarts. */
    private boolean isRoot(String serial, String idCommand) throws IOException {
        return shellOrEmpty(serial, idCommand).trim().equals("0");
    }

    private static Adb.Result check(Adb.Result r, String what) throws IOException {
        if (!r.ok()) {
            throw new IOException(what + " failed: " + r.text());
        }
        return r;
    }

    static byte[] resource(String name) throws IOException {
        try (InputStream in = DeviceManager.class.getResourceAsStream(RESOURCES + name)) {
            if (in == null) {
                throw new IOException("Missing add-on resource: " + name);
            }
            return in.readAllBytes();
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isLocal(String address) {
        String a = address.toLowerCase(Locale.ROOT);
        return a.isEmpty()
                || a.equals("localhost")
                || a.startsWith("127.")
                || a.equals("0.0.0.0")
                || a.equals("::")
                || a.equals("::1")
                || a.equals("[::]");
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }
}
