import org.zaproxy.gradle.addon.AddOnStatus
import org.zaproxy.gradle.addon.misc.ConvertMarkdownToHtml

plugins {
    `java-library`
    id("org.zaproxy.add-on") version "0.13.1"
    id("com.diffplug.spotless")
    id("org.zaproxy.common")
}

description = "Capture all traffic of Android devices and emulators in ZAP, with one click."

val repoUrl = "https://codeberg.org/davidjosearaujo/zaptun"

zapAddOn {
    addOnId.set("zaptun")
    addOnName.set("zaptun: Android Interception")
    zapVersion.set("2.16.0")
    addOnStatus.set(AddOnStatus.ALPHA)

    manifest {
        author.set("David Araújo")
        url.set(repoUrl)
        repo.set(repoUrl)
        changesFile.set(tasks.named<ConvertMarkdownToHtml>("generateManifestChanges").flatMap { it.html })

        dependencies {
            addOns {
                register("network") {
                    version.set(">= 0.25.0 & < 1.0.0")
                }
            }
        }
    }
}

java {
    val javaVersion = JavaVersion.VERSION_17
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

spotless {
    java {
        // The org.zaproxy.common plugin sets a "The ZAP Development Team" license header, which
        // Spotless can't replace in place: clear its steps and set them again with our header.
        clearSteps()
        licenseHeader(
            """
            /*
             * zaptun: capture all traffic of Android devices in ZAP.
             *
             * Copyright ${'$'}YEAR David Araújo
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
            """.trimIndent(),
        )
        googleJavaFormat("1.25.2").aosp()
    }
    kotlinGradle {
        ktlint()
    }
}

dependencies {
    compileOnly("org.zaproxy.addon:network:0.25.0")

    testImplementation("org.zaproxy.addon:network:0.25.0")
    testImplementation("junit:junit:4.13.2")
}

// Bundle what the add-on puts on devices into its resources: the Android app's APK and the CA
// scripts, which live in scripts/ so that scripts/zaptun.sh uses the very same ones.
//
// The APK is built from android-app/ when an Android SDK is available (see settings.gradle.kts).
// Otherwise the prebuilt one in zap-addon/prebuilt/ is bundled; refresh it with
// ./gradlew :zap-addon:updatePrebuiltApk after changing the app.
val builtApk = rootProject.file("android-app/app/build/outputs/apk/debug/app-debug.apk")
val prebuiltApk = layout.projectDirectory.file("prebuilt/zaptun.apk")
val appBuild = gradle.includedBuilds.firstOrNull { it.name == "android-app" }

val updatePrebuiltApk =
    tasks.register<Copy>("updatePrebuiltApk") {
        check(appBuild != null) { "The Android app isn't part of this build: no Android SDK found" }
        dependsOn(appBuild.task(":app:assembleDebug"))
        from(builtApk) { rename { "zaptun.apk" } }
        into(layout.projectDirectory.dir("prebuilt"))
    }

val deviceResources = layout.buildDirectory.dir("generated/device-resources")
val bundleDeviceFiles =
    tasks.register<Copy>("bundleDeviceFiles") {
        if (appBuild != null) {
            dependsOn(appBuild.task(":app:assembleDebug"))
            from(builtApk) { rename { "zaptun.apk" } }
        } else {
            from(prebuiltApk)
        }
        from(rootProject.file("scripts")) {
            include("inject_ca.sh", "install_user_ca.sh")
        }
        into(deviceResources.map { it.dir("dev/davidjosearaujo/zaptun/zap/resources") })
    }
sourceSets.main {
    resources.srcDir(files(deviceResources).builtBy(bundleDeviceFiles))
}
