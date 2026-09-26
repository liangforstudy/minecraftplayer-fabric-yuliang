// The brain â€” pure Java. If anything here ever needs a net.minecraft import, that's the bug
// (FOUNDATION.md decision 1). Minecraft isn't on this classpath, so it can't compile anyway.
plugins {
    `java-library`
}

group = "dev.yuliang"
version = "0.1.32"   // keep in step with mod.version in stonecutter.properties.toml

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // Both ship with Minecraft, so the mod gets them at runtime for free.
    compileOnly("com.google.code.gson:gson:2.10.1")
    compileOnly("org.slf4j:slf4j-api:2.0.9")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.slf4j:slf4j-api:2.0.9")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.9")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
