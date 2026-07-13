import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Swing sample driven by the raw Tao event loop — no Compose plugin,
// no Compose UI. It only needs the Tao backend (for `TaoApplication`) and
// core-runtime (for `Platform`). The Nucleus plugin provides the `run` task.
plugins {
    kotlin("jvm")
    id("dev.nucleusframework")
}

dependencies {
    implementation(project(":decorated-window-tao"))
    implementation(project(":core-runtime"))
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
    mainClass = "dev.nucleusframework.swingtao.MainKt"

    nativeDistributions {
        targetFormats(TargetFormat.Deb, TargetFormat.Dmg, TargetFormat.Nsis)
        appName = "Sample Swing Tao"
        packageName = "SampleSwingTao"
        packageVersion = "1.0.0"
    }
}
