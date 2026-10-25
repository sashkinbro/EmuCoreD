package com.sbro.emucored.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/**
 * Extends a horizontally scrollable row past the horizontal padding of its
 * parent. The row is measured [inset] wider on both sides and shifted left,
 * while the parent still sees the original width. As a result the scroll
 * viewport ends at the parent card edge instead of at the content inset.
 *
 * The scrollable content itself should keep the same inset as content padding
 * so that the first and last items still line up with the surrounding content.
 */
internal fun Modifier.horizontalViewportBleed(inset: Dp): Modifier {
    if (inset.value <= 0f) return this
    return layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) {
                placeable.placeRelative(0, 0)
            }
        }
        val insetPx = inset.roundToPx()
        val expandedWidth = constraints.maxWidth + insetPx * 2
        val placeable = measurable.measure(
            constraints.copy(minWidth = expandedWidth, maxWidth = expandedWidth)
        )
        layout(constraints.maxWidth, placeable.height) {
            placeable.placeRelative(-insetPx, 0)
        }
    }
}
