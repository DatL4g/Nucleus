package demo.shim

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// A platform-neutral icon token for the shared demo screens. On the JVM target
// (this module) it maps to an androidx.compose.material.icons ImageVector drawn
// with material3's Icon. Shared screens reference icons ONLY through this enum.
enum class DemoIcon {
    Home,
    Search,
    Person,
    Settings,
    Add,
    Edit,
    Favorite,
    Star,
    Close,
    Image,
    Delete,
    Share,
    Check,
    MoreVert,
    ContentCopy,
    KeyboardArrowDown,
    Bookmark,
    Notifications,
    Menu,
    ArrowBack,
    Save,
    Refresh,
    Download,
    Upload,
    ExpandMore,
    ExpandLess,
}

/* Renders [icon] as a material-icons ImageVector. The portable stand-in for
   material3's Icon(imageVector, …). Exposes no variable-font axes (fill /
   weight / grade / opticalSize) — those have no upstream analog. */
@Composable
fun DemoIcon(
    icon: DemoIcon,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 24.dp,
) {
    val vector: ImageVector =
        when (icon) {
            DemoIcon.Home -> Icons.Filled.Home
            DemoIcon.Search -> Icons.Filled.Search
            DemoIcon.Person -> Icons.Filled.Person
            DemoIcon.Settings -> Icons.Filled.Settings
            DemoIcon.Add -> Icons.Filled.Add
            DemoIcon.Edit -> Icons.Filled.Edit
            DemoIcon.Favorite -> Icons.Filled.Favorite
            DemoIcon.Star -> Icons.Filled.Star
            DemoIcon.Close -> Icons.Filled.Close
            DemoIcon.Image -> Icons.Filled.Image
            DemoIcon.Delete -> Icons.Filled.Delete
            DemoIcon.Share -> Icons.Filled.Share
            DemoIcon.Check -> Icons.Filled.Check
            DemoIcon.MoreVert -> Icons.Filled.MoreVert
            DemoIcon.ContentCopy -> Icons.Filled.ContentCopy
            DemoIcon.KeyboardArrowDown -> Icons.Filled.KeyboardArrowDown
            DemoIcon.Bookmark -> Icons.Filled.Bookmark
            DemoIcon.Notifications -> Icons.Filled.Notifications
            DemoIcon.Menu -> Icons.Filled.Menu
            DemoIcon.ArrowBack -> Icons.Filled.ArrowBack
            DemoIcon.Save -> Icons.Filled.Save
            DemoIcon.Refresh -> Icons.Filled.Refresh
            DemoIcon.Download -> Icons.Filled.Download
            DemoIcon.Upload -> Icons.Filled.Upload
            DemoIcon.ExpandMore -> Icons.Filled.ExpandMore
            DemoIcon.ExpandLess -> Icons.Filled.ExpandLess
        }
    Icon(
        imageVector = vector,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = if (tint.isSpecified) tint else LocalContentColor.current,
    )
}
