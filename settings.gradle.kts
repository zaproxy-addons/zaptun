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

// The Android VPN app the add-on installs on devices. It's a separate build with its own plugins.
includeBuild("android-app")
