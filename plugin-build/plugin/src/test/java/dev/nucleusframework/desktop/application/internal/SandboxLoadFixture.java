/*
 * Copyright 2020-2026 JetBrains s.r.o. and respective authors and developers.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE.txt file.
 */

package dev.nucleusframework.desktop.application.internal;

/**
 * Fixture compiled to {@code .class} bytes used by {@code SandboxBytecodeRewriterTest} to verify
 * that {@code System.load(String)} and {@code Runtime.load(String)} call sites are rewritten to
 * the sandbox shim while {@code System.loadLibrary(String)} is left untouched.
 *
 * The methods never need to execute — only their bytecode is inspected.
 */
public class SandboxLoadFixture {
    void callSystemLoad(String path) {
        System.load(path);
    }

    void callRuntimeLoad(String path) {
        Runtime.getRuntime().load(path);
    }

    void callLoadLibrary(String name) {
        System.loadLibrary(name);
    }

    void callUnrelated(String s) {
        // No native-load call here; must remain untouched.
        if (s.isEmpty()) throw new IllegalArgumentException();
    }
}