package com.example.demo

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableRow

@Suppress("FunctionNaming")
@Composable
internal fun DraggableTabs(
    tabs: List<String>,
    selectedTab: String?,
    onSelect: (String) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    ReorderableRow(
        modifier = modifier.height(28.dp),
        list = tabs,
        onSettle = { fromIndex, toIndex -> onReorder(fromIndex, toIndex) },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { _, tabTitle, isDragging ->
        key(tabTitle) {
            ReorderableItem {
                val isSelected = tabTitle == selectedTab
                val hoverInteraction = remember { MutableInteractionSource() }
                val isHovered by hoverInteraction.collectIsHoveredAsState()

                val elevation by animateDpAsState(
                    if (isDragging) 8.dp else 0.dp,
                    spring(stiffness = Spring.StiffnessMediumLow),
                )
                val scale by animateFloatAsState(
                    if (isDragging) 1.05f else 1f,
                    spring(stiffness = Spring.StiffnessMediumLow),
                )

                val bgColor by animateColorAsState(
                    when {
                        isDragging -> MaterialTheme.colorScheme.surfaceContainerHighest
                        isSelected -> MaterialTheme.colorScheme.surfaceContainerHigh
                        isHovered -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f)
                        else -> Color.Transparent
                    },
                    spring(stiffness = Spring.StiffnessMediumLow),
                )

                val textColor by animateColorAsState(
                    when {
                        isSelected || isDragging -> MaterialTheme.colorScheme.onSurface
                        isHovered -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )

                val indicatorAlpha by animateFloatAsState(
                    if (isSelected) 1f else 0f,
                    spring(stiffness = Spring.StiffnessMediumLow),
                )
                val indicatorColor = MaterialTheme.colorScheme.primary

                Box(
                    modifier =
                        Modifier
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                shadowElevation = elevation.toPx()
                                shape = RoundedCornerShape(6.dp)
                                clip = true
                            }.clip(RoundedCornerShape(6.dp))
                            .background(bgColor)
                            .hoverable(hoverInteraction)
                            .draggableHandle(
                                onDragStarted = { onSelect(tabTitle) },
                            ).clickable { onSelect(tabTitle) }
                            .drawBehind {
                                if (indicatorAlpha > 0f) {
                                    val h = 2.dp.toPx()
                                    drawRoundRect(
                                        color = indicatorColor.copy(alpha = indicatorAlpha),
                                        topLeft = Offset(4.dp.toPx(), size.height - h),
                                        size = Size(size.width - 8.dp.toPx(), h),
                                        cornerRadius = CornerRadius(h / 2),
                                    )
                                }
                            }.padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        tabTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
