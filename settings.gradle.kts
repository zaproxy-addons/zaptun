pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

// ZAP's conventions for add-ons, as in ZAP's add-on template (https://github.com/zaproxy/addon-java).
plugins {
    id("org.zaproxy.common.settings") version "0.5.0"
    id("com.diffplug.spotless") version "6.25.0" apply false
}

rootProject.name = "zaptun"

// The ZAP add-on (the main project).
include("zap-addon")

// The Android VPN app the add-on installs on devices. It's a separate build with its own plugins,
// and building it needs an Android SDK. Without one (e.g. ZAP's add-on builders), it's left out and
// the add-on bundles the prebuilt APK in zap-addon/prebuilt/ instead. Force it with
// -Pzaptun.buildApk=true (or off with =false).
val hasAndroidSdk =
    listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").any { !System.getenv(it).isNullOrBlank() } ||
        file("android-app/local.properties").let { it.isFile && it.readText().contains("sdk.dir") }
val buildApk = providers.gradleProperty("zaptun.buildApk").map { it.toBoolean() }.getOrElse(hasAndroidSdk)

if (buildApk) {
    includeBuild("android-app")
}
