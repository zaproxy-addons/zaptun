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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Runs the {@code adb} binary. */
public class Adb {

    /** Outcome of one adb invocation. */
    public record Result(int exitCode, String out, String err) {
        public boolean ok() {
            return exitCode == 0;
        }

        /** stdout and stderr together, trimmed, for messages. */
        public String text() {
            return (out + "\n" + err).trim();
        }
    }

    /** Runs adb with the given arguments, optionally against one device. */
    @FunctionalInterface
    public interface Runner {
        Result run(String serial, Duration timeout, List<String> args) throws IOException;
    }

    /** A line of {@code adb devices -l}. */
    public record DeviceEntry(String serial, String state, Map<String, String> props) {}

    /** The user's settings: an explicit adb binary and/or an SDK folder; either may be blank. */
    public record Config(String adbPath, String sdkPath) {}

    private final Supplier<Config> settings;
    private final AdbLocator locator;

    public Adb(Supplier<Config> settings, AdbLocator locator) {
        this.settings = settings;
        this.locator = locator;
    }

    /**
     * The adb binary to run, in order: the configured adb path, adb in the configured SDK folder,
     * then whatever {@link AdbLocator} finds, and finally plain "adb".
     */
    public String executable() {
        String adb = settings.get().adbPath();
        if (adb != null && !adb.isBlank()) {
            return adb.trim();
        }
        String sdk = settings.get().sdkPath();
        if (sdk != null && !sdk.isBlank()) {
            Path inSdk = locator.adbIn(Paths.get(sdk.trim()));
            if (Files.isExecutable(inSdk)) {
                return inSdk.toString();
            }
        }
        return locator.adb().map(f -> f.path().toString()).orElse("adb");
    }

    public Runner runner() {
        return this::run;
    }

    public Result run(String serial, Duration timeout, List<String> args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable());
        if (serial != null) {
            cmd.add("-s");
            cmd.add(serial);
        }
        cmd.addAll(args);

        Process p;
        try {
            p = new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            throw new IOException(
                    "Could not run adb ("
                            + cmd.get(0)
                            + "). Install the Android SDK platform-tools or set the adb path in"
                            + " Tools > Options > Android (zaptun).",
                    e);
        }
        p.getOutputStream().close();
        CompletableFuture<String> out =
                CompletableFuture.supplyAsync(() -> read(p.getInputStream()));
        CompletableFuture<String> err =
                CompletableFuture.supplyAsync(() -> read(p.getErrorStream()));
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IOException("adb " + String.join(" ", args) + " timed out");
            }
            return new Result(p.exitValue(), out.get(), err.get());
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IOException(e.getCause());
        }
    }

    private static String read(InputStream in) {
        try (in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            return buf.toString(StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            return "";
        }
    }

    /** Parses the output of {@code adb devices -l}. */
    public static List<DeviceEntry> parseDevices(String out) {
        List<DeviceEntry> devices = new ArrayList<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of devices") || line.startsWith("*")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            Map<String, String> props = new LinkedHashMap<>();
            for (int i = 2; i < parts.length; i++) {
                int colon = parts[i].indexOf(':');
                if (colon > 0) {
                    props.put(parts[i].substring(0, colon), parts[i].substring(colon + 1));
                }
            }
            devices.add(new DeviceEntry(parts[0], parts[1], props));
        }
        return devices;
    }
}
