package dev.nucleusframework.desktop.application.internal

import org.gradle.api.logging.Logging
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeMachOFileMutationTest {
    @get:Rule
    val tmpDir = TemporaryFolder()

    private val logger = Logging.getLogger(SafeMachOFileMutationTest::class.java)

    @Test
    fun `mutateMachOFileSafely replaces original file when mutation and validation succeed`() {
        val binary = tmpDir.newFile("libjava.dylib")
        binary.writeText("original")

        val result =
            mutateMachOFileSafely(
                binary = binary,
                operation = "test mutation",
                logger = logger,
                commandRunner = fixedRunner(),
                mutate = { copy, _ ->
                    copy.writeText("patched")
                    MachOCommandResult(0, "ok")
                },
            )

        assertTrue(result)
        assertEquals("patched", binary.readText())
    }

    @Test
    fun `mutateMachOFileSafely keeps original file when mutation fails`() {
        val binary = tmpDir.newFile("libawt.dylib")
        binary.writeText("original")

        val result =
            mutateMachOFileSafely(
                binary = binary,
                operation = "test mutation",
                logger = logger,
                commandRunner = fixedRunner(),
                mutate = { copy, _ ->
                    copy.writeText("patched")
                    MachOCommandResult(1, "mutation failed")
                },
            )

        assertFalse(result)
        assertEquals("original", binary.readText())
    }

    @Test
    fun `mutateMachOFileSafely keeps original file when validation fails`() {
        val binary = tmpDir.newFile("libfontmanager.dylib")
        binary.writeText("original")

        val runner =
            MachOCommandRunner { command ->
                when (command.first().substringAfterLast('/')) {
                    "codesign" -> MachOCommandResult(0, "")
                    "otool" -> MachOCommandResult(1, "malformed")
                    else -> MachOCommandResult(0, "")
                }
            }

        val result =
            mutateMachOFileSafely(
                binary = binary,
                operation = "test mutation",
                logger = logger,
                commandRunner = runner,
                mutate = { copy, _ ->
                    copy.writeText("patched")
                    MachOCommandResult(0, "ok")
                },
            )

        assertFalse(result)
        assertEquals("original", binary.readText())
    }

    @Test
    fun `mutateMachOFileSafely continues when remove-signature fails`() {
        val binary = tmpDir.newFile("libjvm.dylib")
        binary.writeText("original")

        val runner =
            MachOCommandRunner { command ->
                when (command.first().substringAfterLast('/')) {
                    "codesign" -> MachOCommandResult(1, "no signature")
                    "otool" -> MachOCommandResult(0, "")
                    else -> MachOCommandResult(0, "")
                }
            }

        val result =
            mutateMachOFileSafely(
                binary = binary,
                operation = "test mutation",
                logger = logger,
                commandRunner = runner,
                mutate = { copy, _ ->
                    copy.writeText("patched")
                    MachOCommandResult(0, "ok")
                },
            )

        assertTrue(result)
        assertEquals("patched", binary.readText())
    }

    @Test
    fun `mutateMachOFileSafely keeps original file when extra validation fails`() {
        val binary = tmpDir.newFile("libmlib_image.dylib")
        binary.writeText("original")

        val result =
            mutateMachOFileSafely(
                binary = binary,
                operation = "test mutation",
                logger = logger,
                commandRunner = fixedRunner(),
                mutate = { copy, _ ->
                    copy.writeText("patched")
                    MachOCommandResult(0, "ok")
                },
                validateExtra = { _, _ -> "extra validation failed" },
            )

        assertFalse(result)
        assertEquals("original", binary.readText())
    }

    private fun fixedRunner() =
        MachOCommandRunner { command ->
            when (command.first().substringAfterLast('/')) {
                "codesign" -> MachOCommandResult(0, "")
                "otool" -> MachOCommandResult(0, "")
                else -> MachOCommandResult(0, "")
            }
        }
}

