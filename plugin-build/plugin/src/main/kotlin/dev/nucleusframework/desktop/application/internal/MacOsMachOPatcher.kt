package dev.nucleusframework.desktop.application.internal

import org.gradle.api.logging.Logger
import java.io.File

/**
 * Patches the LC_BUILD_VERSION of a Mach-O binary using vtool.
 * Sets both the minimum deployment target (minos) and the SDK version,
 * enabling SDK-gated AppKit features (e.g. Liquid Glass on macOS 26+).
 *
 * @return true if the patch succeeded, false if vtool is missing or failed.
 */
internal fun patchMachOBuildVersion(
    binary: File,
    minVersion: String,
    sdkVersion: String,
    logger: Logger,
): Boolean {
    val vtool = File("/usr/bin/vtool")
    if (!vtool.exists()) {
        logger.warn(
            "vtool not found at /usr/bin/vtool — skipping macOS build version patch. " +
                "Install Xcode Command Line Tools to enable this feature.",
        )
        return false
    }

    logger.lifecycle("Patching ${binary.name} LC_BUILD_VERSION: minos=$minVersion sdk=$sdkVersion")

    return mutateMachOFileSafely(
        binary = binary,
        operation = "vtool patch LC_BUILD_VERSION",
        logger = logger,
        mutate = { copy, runner ->
            runner.run(
                listOf(
                    vtool.absolutePath,
                    "-set-build-version",
                    "macos",
                    minVersion,
                    sdkVersion,
                    "-tool",
                    "ld",
                    "0.0",
                    "-replace",
                    "-output",
                    copy.absolutePath,
                    copy.absolutePath,
                ),
            )
        },
        validateExtra = { copy, runner ->
            val showBuildResult = runner.run(listOf(vtool.absolutePath, "-show-build", copy.absolutePath))
            if (showBuildResult.exitCode != 0) {
                "vtool -show-build failed (exit=${showBuildResult.exitCode})"
            } else if (!hasExpectedBuildVersions(showBuildResult.output, minVersion, sdkVersion)) {
                "vtool -show-build output does not contain expected minos/sdk values " +
                    "(minos=$minVersion, sdk=$sdkVersion)"
            } else {
                null
            }
        },
    )
}

private fun hasExpectedBuildVersions(
    showBuildOutput: String,
    minVersion: String,
    sdkVersion: String,
): Boolean {
    val minPattern = Regex("""\bminos\s+${Regex.escape(minVersion)}\b""", RegexOption.IGNORE_CASE)
    val sdkPattern = Regex("""\bsdk\s+${Regex.escape(sdkVersion)}\b""", RegexOption.IGNORE_CASE)
    return minPattern.containsMatchIn(showBuildOutput) && sdkPattern.containsMatchIn(showBuildOutput)
}
