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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Finds the Android SDK and adb without any configuration: {@code ANDROID_HOME}, {@code
 * ANDROID_SDK_ROOT}, the default SDK folders of Android Studio, then {@code PATH}.
 */
public class AdbLocator {

    /** A path that was found, and where it came from (shown in the options page). */
    public record Found(Path path, String source) {}

    private final UnaryOperator<String> env;
    private final String home;
    private final boolean windows;

    public AdbLocator() {
        this(System::getenv, System.getProperty("user.home"), File.separatorChar == '\\');
    }

    AdbLocator(UnaryOperator<String> env, String home, boolean windows) {
        this.env = env;
        this.home = home;
        this.windows = windows;
    }

    /** The first SDK folder that contains platform-tools/adb. */
    public Optional<Found> sdk() {
        List<Found> candidates = new ArrayList<>();
        for (String var : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            String v = env.apply(var);
            if (v != null && !v.isBlank()) {
                candidates.add(new Found(Paths.get(v.trim()), var));
            }
        }
        String defaultSource = "default SDK folder";
        candidates.add(new Found(Paths.get(home, "Android", "Sdk"), defaultSource)); // Linux
        candidates.add(
                new Found(Paths.get(home, "Library", "Android", "sdk"), defaultSource)); // macOS
        String localAppData = env.apply("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            candidates.add(
                    new Found(Paths.get(localAppData, "Android", "Sdk"), defaultSource)); // Windows
        }
        return candidates.stream().filter(f -> Files.isExecutable(adbIn(f.path()))).findFirst();
    }

    /** adb from the SDK, or else from PATH. */
    public Optional<Found> adb() {
        Optional<Found> sdk = sdk();
        if (sdk.isPresent()) {
            return Optional.of(new Found(adbIn(sdk.get().path()), sdk.get().source()));
        }
        String path = env.apply("PATH");
        if (path == null) {
            return Optional.empty();
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            Path adb = Paths.get(dir.trim(), executableName());
            if (Files.isExecutable(adb)) {
                return Optional.of(new Found(adb, "PATH"));
            }
        }
        return Optional.empty();
    }

    /** Where adb lives inside an SDK folder. */
    public Path adbIn(Path sdk) {
        return sdk.resolve("platform-tools").resolve(executableName());
    }

    private String executableName() {
        return windows ? "adb.exe" : "adb";
    }
}
