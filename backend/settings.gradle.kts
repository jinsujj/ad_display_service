plugins {
    // Allows Gradle to auto-download a JDK matching the toolchain spec
    // (the project pins JDK 17 in build.gradle.kts) when one is not present
    // on the local machine.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "adsignage"
