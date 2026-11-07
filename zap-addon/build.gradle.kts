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
val deviceResources = layout.buildDirectory.dir("generated/device-resources")
val bundleDeviceFiles =
    tasks.register<Copy>("bundleDeviceFiles") {
        dependsOn(gradle.includedBuild("android-app").task(":app:assembleDebug"))
        from(rootProject.file("android-app/app/build/outputs/apk/debug/app-debug.apk")) {
            rename { "zaptun.apk" }
        }
        from(rootProject.file("scripts")) {
            include("inject_ca.sh", "install_user_ca.sh")
        }
        into(deviceResources.map { it.dir("dev/davidjosearaujo/zaptun/zap/resources") })
    }
sourceSets.main {
    resources.srcDir(files(deviceResources).builtBy(bundleDeviceFiles))
}
