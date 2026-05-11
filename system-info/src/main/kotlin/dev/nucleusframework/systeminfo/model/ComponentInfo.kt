package dev.nucleusframework.systeminfo.model

data class ComponentInfo(
    val label: String,
    val temperature: Float?,
    val max: Float?,
    val critical: Float?,
)
