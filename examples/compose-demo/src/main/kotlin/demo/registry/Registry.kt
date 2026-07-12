package demo.registry

import androidx.compose.runtime.Composable
import screens.*

// ==================
// MARK: Screen / category registry
// ==================

// One showcase screen: a display name and its content composable.
data class DemoScreen(
    val name: String,
    val content: @Composable () -> Unit,
)

// A sidebar group. [id] is stable; platform-only groups use a fresh id.
data class DemoCategory(
    val id: String,
    val label: String,
    val screens: List<DemoScreen>,
)

// Shared, platform-neutral registries — Core + Material 3 + Navigation 3.
val CoreScreens: List<DemoScreen> =
    listOf(
        // Text primitives first (BasicText → BasicTextField), then layout, graphics,
        // lists, gestures/interaction, state/runtime, animation.
        DemoScreen("BasicText") { BasicTextScreen() },
        DemoScreen("BasicTextField") { BasicTextFieldScreen() },
        DemoScreen("Layout") { LayoutScreen() },
        DemoScreen("CustomLayout") { CustomLayoutScreen() },
        DemoScreen("FlowLayout") { FlowLayoutScreen() },
        DemoScreen("Modifiers") { ModifiersScreen() },
        DemoScreen("ModShortcuts") { ModifierShortcutsScreen() },
        DemoScreen("Shapes") { ShapesScreen() },
        DemoScreen("Path") { PathScreen() },
        DemoScreen("Canvas") { CanvasScreen() },
        DemoScreen("Brushes") { BrushScreen() },
        DemoScreen("GraphicsLayer") { GraphicsLayerScreen() },
        DemoScreen("Shadows") { ShadowScreen() },
        DemoScreen("Colors") { ColorsScreen() },
        DemoScreen("Images") { ImagesScreen() },
        DemoScreen("Scroll") { ScrollScreen() },
        DemoScreen("LazyColumn") { LazyColumnScreen() },
        DemoScreen("LazyGrid") { LazyGridScreen() },
        DemoScreen("LazyExtra") { LazyExtraScreen() },
        DemoScreen("GridsExtra") { GridsExtraScreen() },
        DemoScreen("Pager") { PagerScreen() },
        DemoScreen("Gestures") { GestureScreen() },
        DemoScreen("PointerInput") { PointerInputScreen() },
        DemoScreen("Clipboard") { ClipboardScreen() },
        DemoScreen("DragAndDrop") { DragAndDropScreen() },
        DemoScreen("Interaction") { InteractionScreen() },
        DemoScreen("InteractionSource") { InteractionSourceScreen() },
        DemoScreen("FocusRequester") { FocusRequesterScreen() },
        DemoScreen("AnnotatedString") { AnnotatedStringScreen() },
        DemoScreen("Remember") { StateScreen() },
        DemoScreen("Counter") { CounterScreen() },
        DemoScreen("Recomposition") { RecompositionScreen() },
        DemoScreen("Animation") { AnimationScreen() },
    )
val Material3Screens: List<DemoScreen> =
    listOf(
        // Ordered by family: typography, buttons, inputs, containers, navigation, overlays.
        DemoScreen("Text") { TextScreen() },
        DemoScreen("Buttons") { ButtonsScreen() },
        DemoScreen("Fab") { FabScreen() },
        DemoScreen("TextField") { TextFieldScreen() },
        DemoScreen("Widgets") { WidgetsScreen() },
        DemoScreen("Cards") { CardsScreen() },
        DemoScreen("Chips") { ChipsScreen() },
        DemoScreen("Lists") { ListItemsScreen() },
        DemoScreen("Dialogs") { DialogsScreen() },
        DemoScreen("NavRails") { M3RailsScreen() },
        DemoScreen("Navigation") { NavigationScreen() },
        DemoScreen("Tabs") { M3TabsScreen() },
        DemoScreen("Pickers") { M3PickersScreen() },
        // NOTE: ButtonsExtra, FabExtra, Sheets, Drawers, AppBars, Search, Carousel,
        // and M3Misc were dropped — they rely on Material 3 Expressive APIs only
        // available in the Compose 1.12 line (this demo stays on the repo's 1.11.x).
    )

val Navigation3Screens: List<DemoScreen> =
    listOf(
        DemoScreen("Navigation3") { Navigation3Screen() },
    )

val commonCategories: List<DemoCategory> =
    listOf(
        DemoCategory("core", "Core", CoreScreens),
        DemoCategory("material3", "Material3", Material3Screens),
        DemoCategory("navigation3", "Navigation3", Navigation3Screens),
    )

/* Desktop (JVM) extras: native file dialogs via FileKit. Folded into the
   sidebar dropdown as its own "Desktop" category. */
fun getPlatformCategories(): List<DemoCategory> =
    listOf(
        DemoCategory("desktop", "Desktop", DesktopScreens),
    )

val DesktopScreens: List<DemoScreen> =
    listOf(
        DemoScreen("FileDialogs") { FileDialogsScreen() },
    )

/* commonCategories merged with getPlatformCategories(), by id: matching ids
   concatenate their screens (common first); brand-new platform ids append at the
   end. This is what the sidebar dropdown iterates. */
fun allCategories(): List<DemoCategory> {
    val platform = getPlatformCategories()
    val out = ArrayList<DemoCategory>(commonCategories.size + platform.size)
    val seen = HashSet<String>()
    for (category in commonCategories) {
        val extra = platform.filter { it.id == category.id }.flatMap { it.screens }
        out += DemoCategory(category.id, category.label, category.screens + extra)
        seen += category.id
    }
    for (platformCategory in platform) if (seen.add(platformCategory.id)) out += platformCategory
    return out
}
