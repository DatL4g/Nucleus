@file:Suppress("MagicNumber")

package dev.nucleusframework.window.tao.event

/**
 * Maps X11 keysyms (`XK_*` values, forwarded as vkCode by
 * `nucleus_tao_linux_popup.c`) to the AWT `KeyEvent.VK_*` codes + key
 * location that Compose's desktop `Key` constants are declared with.
 *
 * The Linux twin of [macNativeKeyToAwt], needed by scenes fed straight from
 * the standalone panel's X event thread (the window host path gets this
 * translation from the Rust side instead). The native side already scans
 * XKB groups for a Latin keysym so shortcuts land on the right key under
 * non-Latin layouts (Ctrl+C while typing Hebrew); layout-aware letter and
 * digit resolution from the produced character stays the fast path, same
 * as on macOS.
 *
 * Latin-1 keysyms are numerically equal to their ASCII/Latin-1 character
 * (XK_a = 0x61), so letters, digits and most US punctuation map almost
 * 1:1 onto the AWT codes; the exceptions (quote, grave) and the whole
 * 0xFFxx function-key block are handled explicitly.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // flat lookup table, not logic
internal fun linuxNativeKeyToAwt(
    vkCode: Int,
    codePoint: Int,
): Pair<Int, Int> {
    // Layout-aware fast path: the character the key produced.
    if (codePoint in LINUX_LOWER_A..LINUX_LOWER_Z) return (codePoint - LINUX_CASE_OFFSET) to LINUX_LOC_STANDARD
    if (codePoint in LINUX_UPPER_A..LINUX_UPPER_Z) return codePoint to LINUX_LOC_STANDARD
    if (codePoint in LINUX_DIGIT_0..LINUX_DIGIT_9 && !isLinuxKeypadKeysym(vkCode)) {
        return codePoint to LINUX_LOC_STANDARD
    }

    // Latin keysyms from the native group scan (a-z / A-Z / 0-9).
    if (vkCode in LINUX_LOWER_A..LINUX_LOWER_Z) return (vkCode - LINUX_CASE_OFFSET) to LINUX_LOC_STANDARD
    if (vkCode in LINUX_UPPER_A..LINUX_UPPER_Z) return vkCode to LINUX_LOC_STANDARD
    if (vkCode in LINUX_DIGIT_0..LINUX_DIGIT_9) return vkCode to LINUX_LOC_STANDARD

    return when (vkCode) {
        // ── Editing / whitespace / escape ──
        0xFF0D -> 10 to LINUX_LOC_STANDARD // XK_Return → VK_ENTER
        0xFF09 -> 9 to LINUX_LOC_STANDARD // XK_Tab
        0xFE20 -> 9 to LINUX_LOC_STANDARD // XK_ISO_Left_Tab (Shift+Tab)
        0x0020 -> 32 to LINUX_LOC_STANDARD // XK_space
        0xFF08 -> 8 to LINUX_LOC_STANDARD // XK_BackSpace
        0xFF1B -> 27 to LINUX_LOC_STANDARD // XK_Escape
        0xFFFF -> 127 to LINUX_LOC_STANDARD // XK_Delete
        0xFF63 -> 155 to LINUX_LOC_STANDARD // XK_Insert

        // ── Modifiers ──
        0xFFE1 -> 16 to LINUX_LOC_LEFT // XK_Shift_L
        0xFFE2 -> 16 to LINUX_LOC_RIGHT // XK_Shift_R
        0xFFE3 -> 17 to LINUX_LOC_LEFT // XK_Control_L
        0xFFE4 -> 17 to LINUX_LOC_RIGHT // XK_Control_R
        0xFFE9 -> 18 to LINUX_LOC_LEFT // XK_Alt_L
        0xFFEA -> 18 to LINUX_LOC_RIGHT // XK_Alt_R
        0xFE03 -> 18 to LINUX_LOC_RIGHT // XK_ISO_Level3_Shift (AltGr)
        0xFFEB -> 157 to LINUX_LOC_LEFT // XK_Super_L → VK_META
        0xFFEC -> 157 to LINUX_LOC_RIGHT // XK_Super_R
        0xFFE5 -> 20 to LINUX_LOC_STANDARD // XK_Caps_Lock
        0xFF67 -> 525 to LINUX_LOC_STANDARD // XK_Menu → VK_CONTEXT_MENU

        // ── Navigation ──
        0xFF50 -> 36 to LINUX_LOC_STANDARD // XK_Home
        0xFF57 -> 35 to LINUX_LOC_STANDARD // XK_End
        0xFF55 -> 33 to LINUX_LOC_STANDARD // XK_Page_Up
        0xFF56 -> 34 to LINUX_LOC_STANDARD // XK_Page_Down
        0xFF51 -> 37 to LINUX_LOC_STANDARD // XK_Left
        0xFF52 -> 38 to LINUX_LOC_STANDARD // XK_Up
        0xFF53 -> 39 to LINUX_LOC_STANDARD // XK_Right
        0xFF54 -> 40 to LINUX_LOC_STANDARD // XK_Down

        // ── Function keys ──
        0xFFBE -> 112 to LINUX_LOC_STANDARD // XK_F1
        0xFFBF -> 113 to LINUX_LOC_STANDARD
        0xFFC0 -> 114 to LINUX_LOC_STANDARD
        0xFFC1 -> 115 to LINUX_LOC_STANDARD
        0xFFC2 -> 116 to LINUX_LOC_STANDARD
        0xFFC3 -> 117 to LINUX_LOC_STANDARD
        0xFFC4 -> 118 to LINUX_LOC_STANDARD
        0xFFC5 -> 119 to LINUX_LOC_STANDARD
        0xFFC6 -> 120 to LINUX_LOC_STANDARD
        0xFFC7 -> 121 to LINUX_LOC_STANDARD
        0xFFC8 -> 122 to LINUX_LOC_STANDARD
        0xFFC9 -> 123 to LINUX_LOC_STANDARD // XK_F12

        // ── Keypad (NumLock on) ──
        0xFFB0 -> 96 to LINUX_LOC_NUMPAD // XK_KP_0
        0xFFB1 -> 97 to LINUX_LOC_NUMPAD
        0xFFB2 -> 98 to LINUX_LOC_NUMPAD
        0xFFB3 -> 99 to LINUX_LOC_NUMPAD
        0xFFB4 -> 100 to LINUX_LOC_NUMPAD
        0xFFB5 -> 101 to LINUX_LOC_NUMPAD
        0xFFB6 -> 102 to LINUX_LOC_NUMPAD
        0xFFB7 -> 103 to LINUX_LOC_NUMPAD
        0xFFB8 -> 104 to LINUX_LOC_NUMPAD
        0xFFB9 -> 105 to LINUX_LOC_NUMPAD // XK_KP_9
        0xFF8D -> 10 to LINUX_LOC_NUMPAD // XK_KP_Enter
        0xFFAA -> 106 to LINUX_LOC_NUMPAD // XK_KP_Multiply
        0xFFAB -> 107 to LINUX_LOC_NUMPAD // XK_KP_Add
        0xFFAC -> 108 to LINUX_LOC_NUMPAD // XK_KP_Separator
        0xFFAD -> 109 to LINUX_LOC_NUMPAD // XK_KP_Subtract
        0xFFAE -> 110 to LINUX_LOC_NUMPAD // XK_KP_Decimal
        0xFFAF -> 111 to LINUX_LOC_NUMPAD // XK_KP_Divide
        0xFFBD -> 61 to LINUX_LOC_NUMPAD // XK_KP_Equal

        // ── Keypad (NumLock off) ──
        0xFF95 -> 36 to LINUX_LOC_NUMPAD // XK_KP_Home
        0xFF9C -> 35 to LINUX_LOC_NUMPAD // XK_KP_End
        0xFF9A -> 33 to LINUX_LOC_NUMPAD // XK_KP_Page_Up
        0xFF9B -> 34 to LINUX_LOC_NUMPAD // XK_KP_Page_Down
        0xFF96 -> 37 to LINUX_LOC_NUMPAD // XK_KP_Left
        0xFF97 -> 38 to LINUX_LOC_NUMPAD // XK_KP_Up
        0xFF98 -> 39 to LINUX_LOC_NUMPAD // XK_KP_Right
        0xFF99 -> 40 to LINUX_LOC_NUMPAD // XK_KP_Down
        0xFF9E -> 155 to LINUX_LOC_NUMPAD // XK_KP_Insert
        0xFF9F -> 127 to LINUX_LOC_NUMPAD // XK_KP_Delete
        0xFF9D -> 12 to LINUX_LOC_NUMPAD // XK_KP_Begin → VK_CLEAR

        // ── US punctuation (Latin-1 keysym == ASCII; AWT diverges on two) ──
        0x0027 -> 222 to LINUX_LOC_STANDARD // XK_apostrophe → VK_QUOTE
        0x0060 -> 192 to LINUX_LOC_STANDARD // XK_grave → VK_BACK_QUOTE
        0x002D -> 45 to LINUX_LOC_STANDARD // XK_minus
        0x003D -> 61 to LINUX_LOC_STANDARD // XK_equal
        0x005B -> 91 to LINUX_LOC_STANDARD // XK_bracketleft
        0x005D -> 93 to LINUX_LOC_STANDARD // XK_bracketright
        0x003B -> 59 to LINUX_LOC_STANDARD // XK_semicolon
        0x005C -> 92 to LINUX_LOC_STANDARD // XK_backslash
        0x002C -> 44 to LINUX_LOC_STANDARD // XK_comma
        0x002E -> 46 to LINUX_LOC_STANDARD // XK_period
        0x002F -> 47 to LINUX_LOC_STANDARD // XK_slash

        else -> 0 to LINUX_LOC_STANDARD // Key.Unknown
    }
}

private fun isLinuxKeypadKeysym(vkCode: Int): Boolean = vkCode in 0xFF80..0xFFBD

private const val LINUX_LOC_STANDARD = java.awt.event.KeyEvent.KEY_LOCATION_STANDARD
private const val LINUX_LOC_LEFT = java.awt.event.KeyEvent.KEY_LOCATION_LEFT
private const val LINUX_LOC_RIGHT = java.awt.event.KeyEvent.KEY_LOCATION_RIGHT
private const val LINUX_LOC_NUMPAD = java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD

private const val LINUX_LOWER_A = 'a'.code
private const val LINUX_LOWER_Z = 'z'.code
private const val LINUX_UPPER_A = 'A'.code
private const val LINUX_UPPER_Z = 'Z'.code
private const val LINUX_DIGIT_0 = '0'.code
private const val LINUX_DIGIT_9 = '9'.code
private const val LINUX_CASE_OFFSET = 32
