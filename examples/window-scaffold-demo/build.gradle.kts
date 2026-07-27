import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Minimal demo for the WindowScaffold API (issue #129): full-window content
// layouts with an overlay title bar on the Tao backend.

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(project(":decorated-window-tao"))
    implementation(project(":core-runtime"))
    implementation(project(":graalvm-runtime"))
    implementation(compose.desktop.currentOs)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

nucleus.application {
    mainClass = "dev.nucleusframework.scaffolddemo.MainKt"

    nativeDistributions {
        packageName = "window-scaffold-demo"
        packageVersion = "1.0.0"
    }

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        imageName = "window-scaffold-demo"
    }
}
