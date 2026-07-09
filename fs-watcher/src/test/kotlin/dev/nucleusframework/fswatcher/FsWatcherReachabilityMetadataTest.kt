package dev.nucleusframework.fswatcher

import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FsWatcherReachabilityMetadataTest {
    @Test
    fun nativeBridgeJniCallbacksMatchReachabilityMetadata() {
        val metadata = parseReachabilityMetadata()
        val bridgeMetadata =
            metadata.single { it.type == "dev.nucleusframework.fswatcher.NativeFsWatcherBridge" }

        assertTrue(bridgeMetadata.jniAccessible)
        assertEquals(
            expected = methodSignature(NativeFsWatcherBridge::class.java, "onNativeEvent"),
            actual = bridgeMetadata.methodParameterTypes("onNativeEvent"),
            message = "reachability metadata for onNativeEvent drifted from NativeFsWatcherBridge",
        )
        assertEquals(
            expected = methodSignature(NativeFsWatcherBridge::class.java, "onNativeError"),
            actual = bridgeMetadata.methodParameterTypes("onNativeError"),
            message = "reachability metadata for onNativeError drifted from NativeFsWatcherBridge",
        )
    }

    private fun parseReachabilityMetadata(): List<ReflectedType> {
        val json = Files.readString(Path.of(METADATA_PATH))
        return REFLECTION_ENTRY_REGEX
            .findAll(json)
            .map { match ->
                val body = match.groups["body"]!!.value
                ReflectedType(
                    type = BRIDGE_TYPE,
                    jniAccessible = JNI_ACCESSIBLE_REGEX.find(body)!!.groupValues[1].toBoolean(),
                    methods =
                        METHOD_REGEX
                            .findAll(body)
                            .map { methodMatch ->
                                ReflectedMethod(
                                    name = methodMatch.groups["name"]!!.value,
                                    parameterTypes =
                                        STRING_LITERAL_REGEX
                                            .findAll(methodMatch.groups["parameterTypes"]!!.value)
                                            .map { it.groupValues[1] }
                                            .toList(),
                                )
                            }.toList(),
                )
            }.toList()
    }

    private fun methodSignature(
        owner: Class<*>,
        methodName: String,
    ): List<String> =
        owner.declaredMethods
            .single { method: Method -> method.name == methodName }
            .parameterTypes
            .map { it.toJniMetadataTypeName() }

    private fun Class<*>.toJniMetadataTypeName(): String =
        when (this) {
            java.lang.Long.TYPE -> "long"
            Integer.TYPE -> "int"
            java.lang.Boolean.TYPE -> "boolean"
            else -> name
        }

    private data class ReflectedType(
        val type: String,
        val jniAccessible: Boolean,
        val methods: List<ReflectedMethod>,
    ) {
        fun methodParameterTypes(methodName: String): List<String> =
            methods.single { it.name == methodName }.parameterTypes
    }

    private data class ReflectedMethod(
        val name: String,
        val parameterTypes: List<String>,
    )

    private companion object {
        const val METADATA_PATH =
            "src/main/resources/META-INF/native-image/dev.nucleusframework/nucleus.fs-watcher/reachability-metadata.json"

        val REFLECTION_ENTRY_REGEX =
            Regex(
                """\{\s*"type"\s*:\s*"dev\.nucleusframework\.fswatcher\.NativeFsWatcherBridge",(?<body>.*?)\}\s*]""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            )

        const val BRIDGE_TYPE = "dev.nucleusframework.fswatcher.NativeFsWatcherBridge"
        val JNI_ACCESSIBLE_REGEX = Regex(""""jniAccessible"\s*:\s*(true|false)""")
        val METHOD_REGEX =
            Regex(
                """"name"\s*:\s*"(?<name>[^"]+)"\s*,\s*"parameterTypes"\s*:\s*\[(?<parameterTypes>.*?)]""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            )
        val STRING_LITERAL_REGEX = Regex(""""([^"]+)"""")
    }
}
