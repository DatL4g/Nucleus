import org.apache.tools.ant.taskdefs.condition.Os
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlinComposePlugin)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.vanniktechMavenPublish)
    // Freezes the public ABI: `apiCheck` fails on any change to public FQNs or
    // signatures vs api/decorated-window-tao.api (see REFACTOR_VERIFICATION.md).
    alias(libs.plugins.binaryCompatibilityValidator)
}

val publishVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

dependencies {
    api(project(":decorated-window-core"))
    implementation(project(":core-runtime"))
    implementation(libs.compose.desktop.common)
    // Compose Hot Reload interop (TaoHotReloadBridge). compileOnly: these
    // artifacts are only referenced when running under the hot-reload agent,
    // which puts them on the runtime classpath itself (the plugin adds
    // runtime-jvm — which brings devtools-api — and the agent jar brings the
    // `agent`/`core`/`orchestration` classes). Used only by `trackWindow`
    // (WindowsState / orchestration publishing), not by any wrapping.
    compileOnly(libs.hot.reload.agent)
    compileOnly(libs.hot.reload.core)
    compileOnly(libs.hot.reload.orchestration)
    compileOnly(libs.hot.reload.devtools.api)
    testImplementation(kotlin("test"))
    // Skiko native runtime for the opt-in real-window smoke test
    testImplementation(compose.desktop.currentOs)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // JetBrains convention (kotlin-desktop-toolkit): every declaration must
    // state its visibility explicitly, so the public API surface can only
    // change deliberately (enforced together with the BCV apiCheck task).
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ── Native build ────────────────────────────────────────────────────────────
// Tao + jni crate + per-platform helpers (Metal on macOS, WGL + WndProc deco
// on Windows). Native binaries ship in src/main/resources/nucleus/native/.

val buildNativeMacOs by tasks.registering(Exec::class) {
    description = "Compiles the Rust JNI bridge into a macOS dylib (arm64 + x86_64)"
    group = "build"
    val outputDir = file("src/main/resources/nucleus/native")
    val checkFile = File(outputDir, "darwin-aarch64/libnucleus_tao.dylib")
    onlyIf { Os.isFamily(Os.FAMILY_MAC) && !checkFile.exists() }
    inputs.dir(file("src/main/native/src"))
    inputs.file(file("src/main/native/Cargo.toml"))
    outputs.dir(outputDir)
    workingDir(file("src/main/native/macos"))
    commandLine("bash", "build.sh")
}

val buildNativeWindows by tasks.registering(Exec::class) {
    description = "Compiles the Rust JNI bridge + WGL/Deco helpers into Windows DLLs"
    group = "build"
    val outputDir = file("src/main/resources/nucleus/native")
    val checkFile = File(outputDir, "win32-x64/nucleus_tao.dll")
    onlyIf { Os.isFamily(Os.FAMILY_WINDOWS) && !checkFile.exists() }
    inputs.dir(file("src/main/native/src"))
    inputs.file(file("src/main/native/Cargo.toml"))
    inputs.file(file("src/main/native/windows/nucleus_tao_windows_deco.c"))
    inputs.file(file("src/main/native/windows/nucleus_tao_gl.c"))
    outputs.dir(outputDir)
    workingDir(file("src/main/native/windows"))
    commandLine("cmd", "/c", ".\\build.bat")
}

val buildNativeLinux by tasks.registering(Exec::class) {
    description = "Compiles the Rust JNI bridge + EGL helper into Linux .so libraries"
    group = "build"
    val outputDir = file("src/main/resources/nucleus/native")
    val arch = System.getProperty("os.arch").lowercase()
    val archDir = if (arch.contains("aarch64") || arch.contains("arm64")) "linux-aarch64" else "linux-x64"
    val checkFile = File(outputDir, "$archDir/libnucleus_tao.so")
    onlyIf { Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) && !checkFile.exists() }
    inputs.dir(file("src/main/native/src"))
    inputs.file(file("src/main/native/Cargo.toml"))
    inputs.file(file("src/main/native/linux/nucleus_tao_egl.c"))
    outputs.dir(outputDir)
    workingDir(file("src/main/native/linux"))
    commandLine("bash", "build.sh")
}

tasks.processResources {
    dependsOn(buildNativeMacOs)
    dependsOn(buildNativeWindows)
    dependsOn(buildNativeLinux)
}

tasks.configureEach {
    if (name == "sourcesJar") {
        dependsOn(buildNativeMacOs)
        dependsOn(buildNativeWindows)
        dependsOn(buildNativeLinux)
    }
}

// ── macOS standalone-popup smoke check ──────────────────────────────────────
// AppKit requires the NSPanel to be created on the macOS main thread. Gradle's
// test worker runs tests off the main thread, so the macOS smoke check runs as
// a main() via JavaExec (process main thread = macOS main thread). Windows uses
// the in-process JUnit test in StandalonePanelNativeSmokeTest.

// ── Stage-2 headful window test suite ───────────────────────────────────────
// Real Tao windows, one process, one event loop (see
// src/test/.../headful/TaoWindowTestHarness.kt). JavaExec instead of a Test
// task because the Tao loop runs once per process and, on macOS, AppKit only
// accepts window creation from thread 0. Not part of `check`: needs a display
// (real session on macOS/Windows CI runners, Xvfb+WM on Linux).

val taoHeadfulTest by tasks.registering(JavaExec::class) {
    description = "Runs the stage-2 real-window Tao test suite (requires a display)"
    group = "verification"
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nucleusframework.window.tao.headful.TaoHeadfulTestSuiteMain")
    // Forward the watchdog override into the forked JVM.
    System.getProperty("nucleus.tao.headful.watchdogMillis")?.let {
        systemProperty("nucleus.tao.headful.watchdogMillis", it)
    }
    // NO -XstartOnFirstThread here: taoApplication marshals to the AppKit main
    // thread itself (main_thread_dispatch.m), exactly like a normal `java`
    // launch — and the flag would deadlock the AWT classes the Compose host
    // touches. smokeStandalonePanelMac needs it only because it creates an
    // NSPanel directly, without the Tao loop machinery.
}

val smokeStandalonePanelMac by tasks.registering(JavaExec::class) {
    description = "Smoke-checks the macOS standalone-popup native chain (ownerless NSPanel + Metal)"
    group = "verification"
    onlyIf { Os.isFamily(Os.FAMILY_MAC) }
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("dev.nucleusframework.window.tao.StandalonePanelMacSmokeMain")
    // Run main() on thread 0 (the macOS main thread). The JVM normally runs
    // main() on a spawned pthread, but AppKit only permits NSWindow/NSPanel
    // creation on the true main thread. -XstartOnFirstThread is the same flag
    // LWJGL/GLFW use on macOS.
    jvmArgs("-XstartOnFirstThread")
}

// ── Maven publication ──────────────────────────────────────────────────────

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.decorated-window-tao", publishVersion)

    pom {
        name.set("Nucleus Decorated Window Tao")
        description.set(
            "Experimental no-AWT decorated window backend for Compose Desktop, " +
                "powered by Tao via direct JNI for macOS, Windows, and Linux.",
        )
        url.set("https://github.com/NucleusFramework/Nucleus")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("nucleusframework")
                name.set("NucleusFramework")
                url.set("https://github.com/NucleusFramework")
            }
        }

        scm {
            url.set("https://github.com/NucleusFramework/Nucleus")
            connection.set("scm:git:git://github.com/NucleusFramework/Nucleus.git")
            developerConnection.set("scm:git:ssh://git@github.com/NucleusFramework/Nucleus.git")
        }
    }

    publishToMavenCentral()
    if (project.hasProperty("signingInMemoryKey")) {
        signAllPublications()
    }
}
