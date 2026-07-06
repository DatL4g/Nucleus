import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    kotlin("jvm")
    alias(libs.plugins.vanniktechMavenPublish)
}

val publishVersion =
    providers
        .environmentVariable("GITHUB_REF")
        .orNull
        ?.removePrefix("refs/tags/v")
        ?: "1.0.0"

dependencies {
    implementation(project(":core-runtime"))
    api(libs.coroutines.core)
    testImplementation(kotlin("test"))
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

val nativeResourceDir = layout.projectDirectory.dir("src/main/resources/nucleus/native")
val nativeOutputDir = nativeResourceDir.asFile

fun hostArchDir(prefix: String): String {
    val arch = System.getProperty("os.arch").lowercase()
    val suffix = if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x64"
    return "$prefix-$suffix"
}

val buildNativeMacOsExec by tasks.registering(Exec::class) {
    description = "Compiles the Rust JNI bridge into macOS dylibs for darwin-aarch64 and darwin-x64"
    group = "build"
    onlyIf { Os.isFamily(Os.FAMILY_MAC) }
    outputs.files(
        File(nativeOutputDir, "darwin-aarch64/libnucleus_fs_watcher.dylib"),
        File(nativeOutputDir, "darwin-x64/libnucleus_fs_watcher.dylib"),
    )
    workingDir(layout.projectDirectory.dir("src/main/native/macos"))
    commandLine("bash", "build.sh")
}

val buildNativeMacOs by tasks.registering {
    description = buildNativeMacOsExec.get().description
    group = buildNativeMacOsExec.get().group
    dependsOn(buildNativeMacOsExec)
}

val buildNativeLinuxExec by tasks.registering(Exec::class) {
    description = "Compiles the Rust JNI bridge into the current host Linux shared library"
    group = "build"
    val outputFile = File(nativeOutputDir, "${hostArchDir("linux")}/libnucleus_fs_watcher.so")
    onlyIf { Os.isFamily(Os.FAMILY_UNIX) && !Os.isFamily(Os.FAMILY_MAC) }
    outputs.file(outputFile)
    workingDir(layout.projectDirectory.dir("src/main/native/linux"))
    commandLine("bash", "build.sh")
}

val buildNativeLinux by tasks.registering {
    description = buildNativeLinuxExec.get().description
    group = buildNativeLinuxExec.get().group
    dependsOn(buildNativeLinuxExec)
}

val buildNativeWindowsExec by tasks.registering(Exec::class) {
    description = "Compiles the Rust JNI bridge into a win32-x64 DLL and an optional win32-aarch64 DLL"
    group = "build"
    val windowsNativeDir = layout.projectDirectory.dir("src/main/native/windows")
    val buildScript = windowsNativeDir.file("build.bat").asFile.absolutePath
    onlyIf { Os.isFamily(Os.FAMILY_WINDOWS) }
    outputs.file(File(nativeOutputDir, "win32-x64/nucleus_fs_watcher.dll"))
    workingDir(windowsNativeDir)
    commandLine(
        "cmd",
        "/c",
        buildScript,
    )
}

val buildNativeWindows by tasks.registering {
    description = buildNativeWindowsExec.get().description
    group = buildNativeWindowsExec.get().group
    dependsOn(buildNativeWindowsExec)
}

val verifyNativeResourcePresence by tasks.registering {
    description = "Verifies the current host native artifact expected from the local build script exists in resources"
    group = "verification"
    dependsOn(buildNativeMacOs, buildNativeLinux, buildNativeWindows)
    val expectedArtifactPath =
        when {
            Os.isFamily(Os.FAMILY_MAC) ->
                File(nativeOutputDir, "${hostArchDir("darwin")}/libnucleus_fs_watcher.dylib").absolutePath
            Os.isFamily(Os.FAMILY_WINDOWS) ->
                File(nativeOutputDir, "${hostArchDir("win32")}/nucleus_fs_watcher.dll").absolutePath
            else ->
                File(nativeOutputDir, "${hostArchDir("linux")}/libnucleus_fs_watcher.so").absolutePath
        }

    doLast {
        val expectedArtifact = File(expectedArtifactPath)
        if (!expectedArtifact.exists()) {
            throw GradleException("Expected native artifact is missing: $expectedArtifact")
        }
    }
}

tasks.processResources {
    dependsOn(verifyNativeResourcePresence)
}

tasks.configureEach {
    if (name == "sourcesJar") {
        dependsOn(verifyNativeResourcePresence)
    }
}

mavenPublishing {
    coordinates("dev.nucleusframework", "nucleus.fs-watcher", publishVersion)

    pom {
        name.set("Nucleus FS Watcher")
        description.set("Filesystem watching API for JVM desktop applications, backed by a native watcher bridge.")
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
