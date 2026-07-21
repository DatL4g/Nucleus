# Obfuscation-safety rules — injected by Nucleus ONLY when proguard { obfuscate = true }.
#
# Compose and the Kotlin(x) runtimes are referenced by their original names at runtime (the
# Compose compiler plugin, reflection, ServiceLoader, kotlinx.serialization, coroutines
# internals). Renaming them breaks the app — e.g. `ClassNotFoundException:
# androidx.compose.runtime.Composer` on the first recomposition. These frameworks are
# open-source and carry no IP to protect, so their *names* are preserved while everything the
# application declares (its own packages) is still obfuscated.
#
# `allowshrinking,allowoptimization` keeps ProGuard's shrinking and optimization passes fully
# active on these classes (dead Compose code is still stripped, methods still optimized) — only
# the renaming pass is held back.
-keep,allowshrinking,allowoptimization class androidx.** { *; }
-keep,allowshrinking,allowoptimization class kotlinx.** { *; }
