pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
        maven("https://maven.kikugie.dev/snapshots") { name = "KikuGie Snapshots" }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.8"
    id("dev.kikugie.loom-back-compat") version "0.4.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The Fabric adapter (this root's src/) is built once per Minecraft version.
// 1.21.8 — our earlier test server — is added here when it's needed again.
stonecutter {
    create(rootProject) {
        versions("1.21.1")
        vcsVersion = "1.21.1"
    }
}

// The brain. Plain Java: no Minecraft, no Fabric on its classpath — FOUNDATION.md decision 1.
include("core")

rootProject.name = "zymbot"
