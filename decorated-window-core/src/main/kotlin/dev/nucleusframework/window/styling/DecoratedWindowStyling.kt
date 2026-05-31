package dev.nucleusframework.window.styling

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.nucleusframework.window.DecoratedWindowState

data class DecoratedWindowStyle(
    val colors: DecoratedWindowColors,
    val metrics: DecoratedWindowMetrics,
)

data class DecoratedWindowColors(
    val border: Color,
    val borderInactive: Color,
    val background: Color = Color.White,
) {
    @Composable
    fun borderFor(state: DecoratedWindowState): State<Color> =
        rememberUpdatedState(
            when {
                !state.isActive -> borderInactive
                else -> border
            },
        )
}

data class DecoratedWindowMetrics(
    val borderWidth: Dp = 1.dp,
)

val LocalDecoratedWindowStyle =
    staticCompositionLocalOf<DecoratedWindowStyle> {
        dev.nucleusframework.window.DecoratedWindowDefaults
            .darkWindowStyle()
    }
