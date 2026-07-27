package dev.nucleusframework.window

/**
 * Appearance the OS applies to this window's native surfaces.
 */
public enum class WindowAppearanceMode {
    /** Follow the OS setting (default). */
    System,

    /** Force the light appearance regardless of the OS setting. */
    Light,

    /** Force the dark appearance regardless of the OS setting. */
    Dark,
}
