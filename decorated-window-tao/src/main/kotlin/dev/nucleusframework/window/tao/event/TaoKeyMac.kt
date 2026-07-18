@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.event

/**
 * Maps macOS hardware virtual key codes (`NSEvent.keyCode`, the Carbon
 * `kVK_*` values) to the AWT `KeyEvent.VK_*` codes + key location that
 * Compose's desktop `Key` constants are declared with.
 *
 * Needed by every scene fed straight from an AppKit event callback
 * (popup panels, standalone panels, native-view overlays): the window host
 * path gets this translation from the Rust side (`keymap.rs`), but the
 * ObjC popup callbacks forward `NSEvent.keyCode` raw. Without translation
 * every non-character key is unmatchable (Backspace = 51 = AWT '3') and
 * Cmd shortcuts hit the wrong keys (kVK_ANSI_C = 8 = AWT Backspace).
 *
 * Letter and digit keys are resolved from the produced character when
 * available so the mapping follows the active keyboard layout (AZERTY,
 * Dvorak…); the physical-position table is the fallback for combos whose
 * character is a control char (e.g. Ctrl+letter).
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // flat lookup table, not logic
internal fun macNativeKeyToAwt(
    vkCode: Int,
    codePoint: Int,
): Pair<Int, Int> {
    // Layout-aware fast path: the character the key produced.
    if (codePoint in LOWER_A..LOWER_Z) return (codePoint - CASE_OFFSET) to LOC_STANDARD
    if (codePoint in UPPER_A..UPPER_Z) return codePoint to LOC_STANDARD
    if (codePoint in DIGIT_0..DIGIT_9 && !isMacKeypadKey(vkCode)) return codePoint to LOC_STANDARD

    return when (vkCode) {
        // ── Editing / whitespace / escape ──
        36 -> 10 to LOC_STANDARD // Return → VK_ENTER
        48 -> 9 to LOC_STANDARD // Tab
        49 -> 32 to LOC_STANDARD // Space
        51 -> 8 to LOC_STANDARD // Delete → VK_BACK_SPACE
        53 -> 27 to LOC_STANDARD // Escape
        117 -> 127 to LOC_STANDARD // ForwardDelete → VK_DELETE

        // ── Modifiers ──
        55 -> 157 to LOC_LEFT // Command → VK_META
        54 -> 157 to LOC_RIGHT
        56 -> 16 to LOC_LEFT // Shift
        60 -> 16 to LOC_RIGHT
        58 -> 18 to LOC_LEFT // Option → VK_ALT
        61 -> 18 to LOC_RIGHT
        59 -> 17 to LOC_LEFT // Control
        62 -> 17 to LOC_RIGHT
        57 -> 20 to LOC_STANDARD // CapsLock

        // ── Navigation ──
        115 -> 36 to LOC_STANDARD // Home
        119 -> 35 to LOC_STANDARD // End
        116 -> 33 to LOC_STANDARD // PageUp
        121 -> 34 to LOC_STANDARD // PageDown
        123 -> 37 to LOC_STANDARD // Left
        124 -> 39 to LOC_STANDARD // Right
        125 -> 40 to LOC_STANDARD // Down
        126 -> 38 to LOC_STANDARD // Up
        114 -> 156 to LOC_STANDARD // Help

        // ── Function keys ──
        122 -> 112 to LOC_STANDARD // F1
        120 -> 113 to LOC_STANDARD // F2
        99 -> 114 to LOC_STANDARD // F3
        118 -> 115 to LOC_STANDARD // F4
        96 -> 116 to LOC_STANDARD // F5
        97 -> 117 to LOC_STANDARD // F6
        98 -> 118 to LOC_STANDARD // F7
        100 -> 119 to LOC_STANDARD // F8
        101 -> 120 to LOC_STANDARD // F9
        109 -> 121 to LOC_STANDARD // F10
        103 -> 122 to LOC_STANDARD // F11
        111 -> 123 to LOC_STANDARD // F12

        // ── Keypad ──
        82 -> 96 to LOC_NUMPAD // 0
        83 -> 97 to LOC_NUMPAD
        84 -> 98 to LOC_NUMPAD
        85 -> 99 to LOC_NUMPAD
        86 -> 100 to LOC_NUMPAD
        87 -> 101 to LOC_NUMPAD
        88 -> 102 to LOC_NUMPAD
        89 -> 103 to LOC_NUMPAD
        91 -> 104 to LOC_NUMPAD
        92 -> 105 to LOC_NUMPAD // 9
        65 -> 110 to LOC_NUMPAD // Decimal
        67 -> 106 to LOC_NUMPAD // Multiply
        69 -> 107 to LOC_NUMPAD // Plus → VK_ADD
        78 -> 109 to LOC_NUMPAD // Minus → VK_SUBTRACT
        75 -> 111 to LOC_NUMPAD // Divide
        76 -> 10 to LOC_NUMPAD // KeypadEnter
        71 -> 12 to LOC_NUMPAD // Clear
        81 -> 61 to LOC_NUMPAD // Equals

        // ── ANSI punctuation (physical positions) ──
        24 -> 61 to LOC_STANDARD // Equal
        27 -> 45 to LOC_STANDARD // Minus
        30 -> 93 to LOC_STANDARD // RightBracket
        33 -> 91 to LOC_STANDARD // LeftBracket
        39 -> 222 to LOC_STANDARD // Quote
        41 -> 59 to LOC_STANDARD // Semicolon
        42 -> 92 to LOC_STANDARD // Backslash
        43 -> 44 to LOC_STANDARD // Comma
        44 -> 47 to LOC_STANDARD // Slash
        47 -> 46 to LOC_STANDARD // Period
        50 -> 192 to LOC_STANDARD // Grave

        // ── ANSI letters/digits (fallback when the character is a control char) ──
        0 -> 'A'.code to LOC_STANDARD
        1 -> 'S'.code to LOC_STANDARD
        2 -> 'D'.code to LOC_STANDARD
        3 -> 'F'.code to LOC_STANDARD
        4 -> 'H'.code to LOC_STANDARD
        5 -> 'G'.code to LOC_STANDARD
        6 -> 'Z'.code to LOC_STANDARD
        7 -> 'X'.code to LOC_STANDARD
        8 -> 'C'.code to LOC_STANDARD
        9 -> 'V'.code to LOC_STANDARD
        11 -> 'B'.code to LOC_STANDARD
        12 -> 'Q'.code to LOC_STANDARD
        13 -> 'W'.code to LOC_STANDARD
        14 -> 'E'.code to LOC_STANDARD
        15 -> 'R'.code to LOC_STANDARD
        16 -> 'Y'.code to LOC_STANDARD
        17 -> 'T'.code to LOC_STANDARD
        31 -> 'O'.code to LOC_STANDARD
        32 -> 'U'.code to LOC_STANDARD
        34 -> 'I'.code to LOC_STANDARD
        35 -> 'P'.code to LOC_STANDARD
        37 -> 'L'.code to LOC_STANDARD
        38 -> 'J'.code to LOC_STANDARD
        40 -> 'K'.code to LOC_STANDARD
        45 -> 'N'.code to LOC_STANDARD
        46 -> 'M'.code to LOC_STANDARD
        18 -> '1'.code to LOC_STANDARD
        19 -> '2'.code to LOC_STANDARD
        20 -> '3'.code to LOC_STANDARD
        21 -> '4'.code to LOC_STANDARD
        23 -> '5'.code to LOC_STANDARD
        22 -> '6'.code to LOC_STANDARD
        26 -> '7'.code to LOC_STANDARD
        28 -> '8'.code to LOC_STANDARD
        25 -> '9'.code to LOC_STANDARD
        29 -> '0'.code to LOC_STANDARD

        else -> 0 to LOC_STANDARD // Key.Unknown
    }
}

private fun isMacKeypadKey(vkCode: Int): Boolean = vkCode in 82..92 || vkCode in KEYPAD_OPERATORS

private val KEYPAD_OPERATORS = setOf(65, 67, 69, 71, 75, 76, 78, 81)

private const val LOC_STANDARD = java.awt.event.KeyEvent.KEY_LOCATION_STANDARD
private const val LOC_LEFT = java.awt.event.KeyEvent.KEY_LOCATION_LEFT
private const val LOC_RIGHT = java.awt.event.KeyEvent.KEY_LOCATION_RIGHT
private const val LOC_NUMPAD = java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD

private const val LOWER_A = 'a'.code
private const val LOWER_Z = 'z'.code
private const val UPPER_A = 'A'.code
private const val UPPER_Z = 'Z'.code
private const val DIGIT_0 = '0'.code
private const val DIGIT_9 = '9'.code
private const val CASE_OFFSET = 32
