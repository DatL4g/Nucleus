package dev.nucleusframework.graalvm.locale

import dev.nucleusframework.core.runtime.NativeLibraryLoader

/**
 * JNI bridge to CoreFoundation for the macOS preferred UI language.
 *
 * Replicates HotSpot/JBR's java_props_macosx.c locale resolution, which
 * SubstrateVM never runs — see [dev.nucleusframework.graalvm.GraalVmInitializer].
 */
internal object NativeLocaleBridge {
    private const val LIBRARY_NAME = "nucleus_locale"

    private val loaded = NativeLibraryLoader.load(LIBRARY_NAME, NativeLocaleBridge::class.java)

    val isLoaded: Boolean get() = loaded

    /** Most-preferred UI language as a BCP-47 tag (e.g. "fr-FR"), or null. */
    @JvmStatic
    external fun nativePreferredLanguageTag(): String?
}
