/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

class SandboxBytecodeRewriterTest {
    /** Per-method list of (opcode, owner, name, descriptor) method-insn calls in declaration order. */
    private fun methodCalls(bytes: ByteArray): Map<String, List<Triple<Int, String, String>>> {
        val reader = ClassReader(bytes)
        val result = LinkedHashMap<String, MutableList<Triple<Int, String, String>>>()
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val calls = mutableListOf<Triple<Int, String, String>>()
                    result[name!!] = calls
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            descriptor: String?,
                            isInterface: Boolean,
                        ) {
                            calls.add(Triple(opcode, owner ?: "", name ?: ""))
                        }
                    }
                }
            },
            0,
        )
        return result
    }

    private fun fixtureBytes(): ByteArray {
        val res = SandboxLoadFixture::class.java.getResourceAsStream("SandboxLoadFixture.class")
            ?: error("SandboxLoadFixture.class not found on test classpath")
        return res.use { it.readBytes() }
    }

    @Test
    fun `hasLoadCallSite detects the fixture`() {
        assertTrue(SandboxBytecodeRewriter.hasLoadCallSite(fixtureBytes()))
    }

    @Test
    fun `System load and Runtime load are rewritten, loadLibrary is not`() {
        val original = fixtureBytes()
        val rewritten = SandboxBytecodeRewriter.rewriteSystemLoadCalls(original)
        assertFalse("rewriter must return new bytes when call sites exist", rewritten === original)

        val calls = methodCalls(rewritten)

        // System.load(String) -> INVOKESTATIC shim.load
        val sysCalls = calls["callSystemLoad"] ?: error("callSystemLoad missing")
        assertEquals(1, sysCalls.size)
        val (sysOp, sysOwner, sysName) = sysCalls[0]
        assertEquals(Opcodes.INVOKESTATIC, sysOp)
        assertEquals(SandboxMarkers.SHIM_OWNER, sysOwner)
        assertEquals("load", sysName)

        // Runtime.getRuntime().load(String) -> INVOKESTATIC shim.load (Runtime receiver dropped).
        // Two method calls remain: getRuntime() + shim.load().
        val rtCalls = calls["callRuntimeLoad"] ?: error("callRuntimeLoad missing")
        assertEquals(2, rtCalls.size)
        assertEquals("java/lang/Runtime", rtCalls[0].second)
        assertEquals("getRuntime", rtCalls[0].third)
        val (shimOp, shimOwner, shimName) = rtCalls[1]
        assertEquals(Opcodes.INVOKESTATIC, shimOp)
        assertEquals(SandboxMarkers.SHIM_OWNER, shimOwner)
        assertEquals("load", shimName)

        // System.loadLibrary(String) is untouched.
        val libCalls = calls["callLoadLibrary"] ?: error("callLoadLibrary missing")
        assertEquals(1, libCalls.size)
        val (libOp, libOwner, libName) = libCalls[0]
        assertEquals(Opcodes.INVOKESTATIC, libOp)
        assertEquals("java/lang/System", libOwner)
        assertEquals("loadLibrary", libName)

        // Unrelated method keeps its own calls (isEmpty + IllegalArgumentException ctor) but
        // contains no native-load call site.
        val unrelated = calls["callUnrelated"] ?: error("callUnrelated missing")
        assertTrue(unrelated.none { (_, owner, name) -> name == "load" && (owner == "java/lang/System" || owner == "java/lang/Runtime") })
    }

    @Test
    fun `class with no load call site is returned unchanged`() {
        // Build a minimal class with no methods calling load: use the unrelated-only fixture method
        // by crafting a tiny class via ASM is overkill — instead assert that re-running the
        // rewriter on already-rewritten bytes with no further original-load calls returns the same
        // instance (idempotent passthrough).
        val rewritten = SandboxBytecodeRewriter.rewriteSystemLoadCalls(fixtureBytes())
        val twice = SandboxBytecodeRewriter.rewriteSystemLoadCalls(rewritten)
        // Second pass: no java/lang/System.load or Runtime.load left, so no further changes.
        assertSame(rewritten, twice)
    }
}