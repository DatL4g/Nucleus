import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    id("dev.nucleusframework")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    implementation(project(":core-runtime"))
    implementation(project(":nucleus-application"))
    implementation(project(":service-management-macos"))
    implementation(project(":notification-common"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

nucleus.application {
    mainClass = "servicemanagementdemo.MainKt"
    jvmArgs +=
        listOf(
            "--add-opens",
            "java.desktop/sun.awt=ALL-UNNAMED",
            "--add-opens",
            "java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens",
            "java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
        )

    nativeDistributions {
        targetFormats(TargetFormat.Dmg)
        packageName = "ServiceManagementDemo"
        packageVersion = "1.0.0"

        macOS {
            bundleID = "dev.nucleusframework.servicemanagement.demo"
            appCategory = "public.app-category.utilities"
            dockName = "SMAppService Demo"

            launchAgents {
                agent("dev.nucleusframework.servicemanagement.demo.notifier") {
                    arguments("--notify")
                    startInterval(900)
                }
            }
        }
    }
}
