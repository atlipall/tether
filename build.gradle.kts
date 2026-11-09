import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "local.tether"
version = providers.gradleProperty("pluginVersion").getOrElse("${file("version.txt").readText().trim()}.0")

/**
 * The WebStorm build to compile and test against. Without `-PideVersion`, the installed
 * `/Applications/WebStorm.app` is used; CI passes a version so Gradle downloads that release instead.
 */
val ideVersion: String? = providers.gradleProperty("ideVersion").orNull

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (ideVersion != null) webstorm(ideVersion) else local("/Applications/WebStorm.app")
        bundledPlugin("com.intellij.modules.jcef")
        bundledPlugin("JavaScript")
        bundledPlugin("intellij.testRunner.plugin")
        bundledModule("intellij.platform.vcs.impl")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }
    buildSearchableOptions = false
    instrumentCode = false
}
