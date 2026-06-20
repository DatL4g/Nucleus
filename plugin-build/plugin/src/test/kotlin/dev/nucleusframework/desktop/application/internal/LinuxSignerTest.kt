package dev.nucleusframework.desktop.application.internal

import dev.nucleusframework.desktop.application.dsl.DebSignMethod
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end test of [LinuxSigner] against the real `gpg` / `rpm` / `rpmbuild` tools.
 *
 * Generates a throwaway key, exports the private key as an `.asc` (the [keyFile] a user/CI
 * supplies), builds a minimal `.rpm`, signs it through the production code path, then verifies
 * the signature in a clean rpm database using only the exported public key.
 *
 * Skipped (not failed) on machines without the required tools, e.g. CI runners without rpm.
 */
class LinuxSignerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val passphrase = "nucleus-test-pass"

    @Test
    fun `signs rpm and the signature verifies with only the exported public key`() {
        Assume.assumeTrue("requires gpg, rpm and rpmbuild", toolsAvailable("gpg", "rpm", "rpmbuild"))

        val keyHome = tmp.newFolder("keygen").apply { restrict() }
        val keyId = generateKey(keyHome)
        val privateKey = exportPrivateKey(keyHome, keyId)
        val rpm = buildMinimalRpm()

        // --- code under test ---
        LinuxSigner(runner(), project.logger).sign(
            packages = listOf(rpm),
            keyId = keyId,
            keyFile = privateKey,
            passphrase = passphrase,
            debMethod = DebSignMethod.DpkgSig,
        )

        val publicKey = File(rpm.parentFile, "${rpm.name}.pub.asc")
        assertTrue("public key not exported next to the package", publicKey.isFile && publicKey.length() > 0)

        assertTrue("rpm signature did not verify", rpmSignatureVerifies(rpm, publicKey))
    }

    @Test
    fun `signs deb with a detached signature that verifies against the exported public key`() {
        Assume.assumeTrue("requires gpg", toolsAvailable("gpg"))

        val keyHome = tmp.newFolder("keygen-deb").apply { restrict() }
        val keyId = generateKey(keyHome)
        val privateKey = exportPrivateKey(keyHome, keyId)
        val deb = tmp.newFile("app_1.0.0_amd64.deb").apply { writeBytes(ByteArray(4096) { it.toByte() }) }

        // --- code under test ---
        LinuxSigner(runner(), project.logger).sign(
            packages = listOf(deb),
            keyId = keyId,
            keyFile = privateKey,
            passphrase = passphrase,
            debMethod = DebSignMethod.Detached,
        )

        val detachedSig = File(deb.parentFile, "${deb.name}.asc")
        val publicKey = File(deb.parentFile, "${deb.name}.pub.asc")
        assertTrue("detached signature not written", detachedSig.isFile && detachedSig.length() > 0)
        assertTrue("public key not exported", publicKey.isFile && publicKey.length() > 0)

        assertTrue("detached signature did not verify", detachedSignatureVerifies(deb, detachedSig, publicKey))
    }

    private fun detachedSignatureVerifies(
        deb: File,
        sig: File,
        publicKey: File,
    ): Boolean {
        val verifyHome = tmp.newFolder("deb-verify").apply { restrict() }
        run("gpg", "--homedir", verifyHome.absolutePath, "--batch", "--import", publicKey.absolutePath)
        val output =
            capture(
                "gpg", "--homedir", verifyHome.absolutePath, "--verify", sig.absolutePath, deb.absolutePath,
                env = mapOf("LC_ALL" to "C"),
            )
        return output.contains("Good signature")
    }

    // ---- Gradle service wiring ----

    private val project by lazy { ProjectBuilder.builder().withProjectDir(tmp.root).build() }

    private fun runner(): ExternalToolRunner {
        val execOps = (project as ProjectInternal).services.get(ExecOperations::class.java)
        val verbose = project.objects.property(Boolean::class.java).convention(false)
        val logsDir = project.objects.directoryProperty().convention(project.layout.buildDirectory.dir("logs"))
        return ExternalToolRunner(verbose, logsDir, execOps)
    }

    // ---- test scaffolding (not under test) ----

    private fun generateKey(home: File): String {
        val params = File(home, "params")
        params.writeText(
            buildString {
                appendLine("Key-Type: RSA")
                appendLine("Key-Length: 2048")
                appendLine("Name-Real: Nucleus Test")
                appendLine("Name-Email: test@nucleus.dev")
                appendLine("Expire-Date: 0")
                appendLine("Passphrase: $passphrase")
                appendLine("%commit")
            },
        )
        run("gpg", "--batch", "--homedir", home.absolutePath, "--gen-key", params.absolutePath)
        val colons =
            capture("gpg", "--homedir", home.absolutePath, "--list-keys", "--with-colons", "test@nucleus.dev")
        return colons.lineSequence()
            .first { it.startsWith("pub:") }
            .split(":")[4]
    }

    private fun exportPrivateKey(
        home: File,
        keyId: String,
    ): File {
        val out = File(home, "private.asc")
        val text =
            capture(
                "gpg", "--homedir", home.absolutePath, "--batch", "--yes",
                "--pinentry-mode", "loopback", "--passphrase", passphrase,
                "--armor", "--export-secret-keys", keyId,
            )
        out.writeText(text)
        return out
    }

    private fun buildMinimalRpm(): File {
        val top = tmp.newFolder("rpmbuild")
        listOf("SPECS", "BUILD", "RPMS", "SOURCES").forEach { File(top, it).mkdirs() }
        val spec = File(top, "SPECS/foo.spec")
        spec.writeText(
            buildString {
                appendLine("Name: foo")
                appendLine("Version: 1.0.0")
                appendLine("Release: 1")
                appendLine("Summary: test")
                appendLine("License: MIT")
                appendLine("%description")
                appendLine("test")
                appendLine("%files")
                appendLine("%changelog")
            },
        )
        run("rpmbuild", "--define", "_topdir ${top.absolutePath}", "-bb", spec.absolutePath)
        return File(top, "RPMS").walkTopDown().first { it.extension == "rpm" }
    }

    private fun rpmSignatureVerifies(
        rpm: File,
        publicKey: File,
    ): Boolean {
        val db = tmp.newFolder("rpmdb")
        run("rpm", "--dbpath", db.absolutePath, "--initdb")
        run("rpm", "--dbpath", db.absolutePath, "--import", publicKey.absolutePath)
        val output = capture("rpm", "--dbpath", db.absolutePath, "-K", rpm.absolutePath, env = mapOf("LC_ALL" to "C"))
        return output.contains("signatures OK")
    }

    // ---- process helpers ----

    private fun toolsAvailable(vararg tools: String): Boolean {
        val path = System.getenv("PATH") ?: return false
        return tools.all { tool ->
            path.split(":").any { File(it, tool).let { f -> f.exists() && f.canExecute() } }
        }
    }

    private fun run(vararg command: String) {
        val exit = process(command.toList(), emptyMap()).waitFor()
        check(exit == 0) { "command failed ($exit): ${command.joinToString(" ")}" }
    }

    private fun capture(
        vararg command: String,
        env: Map<String, String> = emptyMap(),
    ): String {
        val proc = process(command.toList(), env)
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return output
    }

    private fun process(
        command: List<String>,
        env: Map<String, String>,
    ): Process =
        ProcessBuilder(command)
            .redirectErrorStream(true)
            .also { it.environment().putAll(env) }
            .start()

    private fun File.restrict() {
        setReadable(false, false)
        setReadable(true, true)
        setExecutable(true, true)
    }
}
