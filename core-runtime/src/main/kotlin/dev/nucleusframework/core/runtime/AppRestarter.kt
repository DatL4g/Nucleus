package dev.nucleusframework.core.runtime

import java.io.File
import kotlin.system.exitProcess

/**
 * Relaunches the current application.
 *
 * Resolves the OS-specific launch command for a jpackage/AppImage-bundled app and,
 * on [restartApp], spawns a detached helper that waits for this process to exit and
 * then relaunches it.
 *
 * The launcher-resolution helpers are also reused by the updater to relaunch the app
 * after an install.
 */
public object AppRestarter {
    /**
     * Resolves the command that relaunches the current application, or `null` when it
     * cannot be determined (e.g. running from an IDE / dev mode).
     */
    @JvmStatic
    public fun relaunchCommand(): List<String>? =
        when (Platform.Current) {
            Platform.Windows -> windowsLauncher()?.let { listOf(it.absolutePath) }
            Platform.MacOS -> macAppBundle()?.let { listOf("open", it.absolutePath) }
            Platform.Linux -> linuxRelaunchCommand()
            Platform.Unknown -> null
        }

    /**
     * Restarts the application: spawns a detached helper that waits for this process to
     * exit and then relaunches it, then terminates this process via [exitProcess].
     *
     * Returns `false` (and leaves the caller running) when the relaunch command cannot
     * be resolved; otherwise it never returns.
     */
    @JvmStatic
    public fun restartApp(): Boolean {
        val command = relaunchCommand() ?: return false
        spawnRestartHelper(command)
        exitProcess(0)
    }

    /**
     * Resolves the jpackage launcher on Windows.
     * Structure: `<install-dir>\<App>.exe` with `java.home = <install-dir>\runtime`.
     */
    @JvmStatic
    public fun windowsLauncher(): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        val appRoot = File(javaHome).parentFile ?: return null
        if (!appRoot.isDirectory) return null
        return appRoot.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".exe") }
    }

    /**
     * Resolves the jpackage launcher on Linux.
     * Structure: `/opt/<app>/bin/<Launcher>` with `java.home = /opt/<app>/lib/runtime`.
     */
    @JvmStatic
    public fun linuxLauncher(): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        // java.home = /opt/<app>/lib/runtime → parent = lib → parent = /opt/<app>
        val appRoot = File(javaHome).parentFile?.parentFile ?: return null
        val binDir = File(appRoot, "bin")
        if (!binDir.isDirectory) return null
        return binDir.listFiles()?.firstOrNull { it.canExecute() }
    }

    /**
     * Resolves the enclosing macOS `.app` bundle by walking up from `java.home`.
     */
    @JvmStatic
    public fun macAppBundle(): File? {
        val javaHome = System.getProperty("java.home") ?: return null
        var dir: File? = File(javaHome)
        while (dir?.parentFile != null) {
            if (dir.name.endsWith(".app")) return dir
            dir = dir.parentFile
        }
        return null
    }

    private fun linuxRelaunchCommand(): List<String>? {
        // An AppImage relaunches from its original mount point, exposed via $APPIMAGE.
        System.getenv("APPIMAGE")?.let { return listOf(it) }
        return linuxLauncher()?.let { listOf(it.absolutePath) }
    }

    private fun spawnRestartHelper(command: List<String>) {
        val pid = ProcessHandle.current().pid()
        if (Platform.Current == Platform.Windows) {
            spawnWindowsHelper(pid, command)
        } else {
            spawnUnixHelper(pid, command)
        }
    }

    private fun spawnUnixHelper(
        pid: Long,
        command: List<String>,
    ) {
        val launch = command.joinToString(" ") { "\"$it\"" }
        val script = File(System.getProperty("java.io.tmpdir"), "nucleus-restart.sh")
        script.writeText(
            """
            |#!/usr/bin/env bash
            |# Ignore SIGHUP to survive parent process exit
            |trap '' HUP
            |
            |# Wait for the app process to fully exit
            |while kill -0 $pid 2>/dev/null; do
            |    sleep 0.2
            |done
            |
            |# Relaunch fully detached
            |nohup $launch > /dev/null 2>&1 &
            |
            |# Clean up this script
            |rm -f "${'$'}{0}"
            """.trimMargin(),
        )
        script.setExecutable(true)

        // setsid detaches from the process tree on Linux; macOS relaunches via `open`.
        val builder =
            if (Platform.Current == Platform.Linux) {
                ProcessBuilder("setsid", "bash", script.absolutePath)
            } else {
                ProcessBuilder("bash", script.absolutePath)
            }
        builder
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private fun spawnWindowsHelper(
        pid: Long,
        command: List<String>,
    ) {
        val exe = command.first()
        val arguments = command.drop(1)
        val argList =
            if (arguments.isEmpty()) {
                ""
            } else {
                " -ArgumentList " + arguments.joinToString(", ") { "'$it'" }
            }
        val script = File(System.getProperty("java.io.tmpdir"), "nucleus-restart.ps1")
        script.writeText(
            """
            |# Wait for the app process to fully exit
            |while (Get-Process -Id $pid -ErrorAction SilentlyContinue) {
            |    Start-Sleep -Milliseconds 200
            |}
            |
            |# Relaunch the application
            |Start-Process '$exe'$argList
            |
            |# Clean up this script
            |Remove-Item '${script.absolutePath}' -Force -ErrorAction SilentlyContinue
            """.trimMargin(),
        )

        ProcessBuilder(
            "powershell",
            "-ExecutionPolicy",
            "Bypass",
            "-WindowStyle",
            "Hidden",
            "-File",
            script.absolutePath,
        ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}
