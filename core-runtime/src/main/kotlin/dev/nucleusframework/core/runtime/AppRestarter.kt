package dev.nucleusframework.core.runtime

import java.io.File
import kotlin.system.exitProcess

/**
 * Restarts the current application.
 *
 * Resolves the running executable via [ProcessHandle], spawns a fresh process from it
 * and terminates the current one. Used for scenarios such as applying configuration
 * changes or relaunching after an update.
 */
public object AppRestarter {
    /**
     * Absolute path of the executable that launched the current process.
     *
     * @throws IllegalStateException when the executable path cannot be resolved.
     */
    @JvmStatic
    public val applicationExecutablePath: String by lazy {
        val command =
            ProcessHandle
                .current()
                .info()
                .command()
                .orElse(null)
                ?: error("Failed to get application executable path")
        File(command).absolutePath
    }

    /**
     * Restarts the application by launching a new instance from [applicationExecutablePath]
     * and terminating the current process via [exitProcess].
     */
    @JvmStatic
    @Suppress("TooGenericExceptionCaught", "PrintStackTrace")
    public fun restartApplication() {
        try {
            ProcessBuilder(applicationExecutablePath).start()
            exitProcess(0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
