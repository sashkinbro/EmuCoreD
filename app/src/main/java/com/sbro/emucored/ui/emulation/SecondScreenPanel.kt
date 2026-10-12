// SPDX-FileCopyrightText: 2026 SBRO
// SPDX-License-Identifier: LicenseRef-EmuCoreD-Proprietary
package com.sbro.emucored.ui.emulation

import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.ScreenshotMonitor
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.sbro.emucored.R
import com.sbro.emucored.core.EmuCoreNative
import com.sbro.emucored.core.EmulatorBridge
import com.sbro.emucored.data.AppPreferences
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val TAB_IDLE_HIDE_MS = 3_000L

/**
 * Pull-out bottom screen.
 *
 * The main emulation view shows only the top 3DS screen; the bottom screen
 * lives in this dockable panel, revealed from the edge tab. The panel can dock
 * on any side, the tab carries all controls (show/hide, swap top and bottom,
 * move to the next side) and fades away after a few seconds; a double tap
 * anywhere on the screen brings it back.
 */
@Composable
internal fun SecondScreenPanel(
    side: Int,
    widthPercent: Int,
    open: Boolean,
    tabPosition: Float,
    revealSignal: Int,
    onOpenChange: (Boolean) -> Unit,
    onWidthPercentChange: (Int) -> Unit,
    onSideChange: (Int) -> Unit,
    onSwapScreens: () -> Unit,
    onTabPositionChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val isLeft = side == AppPreferences.SECOND_SCREEN_SIDE_LEFT
    val isRight = side == AppPreferences.SECOND_SCREEN_SIDE_RIGHT
    val isTop = side == AppPreferences.SECOND_SCREEN_SIDE_TOP
    val isBottom = side == AppPreferences.SECOND_SCREEN_SIDE_BOTTOM
    val horizontalDock = isLeft || isRight

    var tabActive by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(revealSignal) {
        if (revealSignal > 0) {
            tabActive = true
            interactionTick++
        }
    }
    LaunchedEffect(tabActive, interactionTick, open) {
        if (open) {
            tabActive = true
            return@LaunchedEffect
        }
        if (!tabActive) return@LaunchedEffect
        delay(TAB_IDLE_HIDE_MS)
        tabActive = false
    }
    val tabAlpha by animateFloatAsState(
        targetValue = if (tabActive || open) 0.85f else 0.10f,
        animationSpec = tween(durationMillis = 450),
        label = "secondScreenTabAlpha"
    )

    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val axisExtentPx = if (horizontalDock) maxHeightPx else maxWidthPx
        val crossExtentPx = if (horizontalDock) maxWidthPx else maxHeightPx
        val panelExtentPx = (axisExtentPx * widthPercent / 100f)
            .coerceIn(axisExtentPx * 0.24f, crossExtentPx * 0.72f)

        val tabExtent = 42.dp
        val tabCrossExtent = 196.dp
        val tabExtentPx = with(density) { tabExtent.toPx() }
        val tabCrossExtentPx = with(density) { tabCrossExtent.toPx() }

        var tabFraction by remember { mutableFloatStateOf(tabPosition.coerceIn(0.04f, 0.96f)) }
        LaunchedEffect(tabPosition) {
            if (abs(tabFraction - tabPosition) > 0.01f) {
                tabFraction = tabPosition.coerceIn(0.04f, 0.96f)
            }
        }

        val progress = remember { Animatable(if (open) 1f else 0f) }
        LaunchedEffect(open) {
            progress.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
            )
        }

        val hiddenOffset = when {
            isRight -> panelExtentPx
            isLeft -> -panelExtentPx
            isBottom -> panelExtentPx
            else -> -panelExtentPx
        }
        val panelOffset = (hiddenOffset * (1f - progress.value)).roundToInt()

        // Inner-edge handle changes the panel size.
        val dragResizeState = rememberDraggableState { delta ->
            val grow = when {
                isRight -> -delta
                isLeft -> delta
                isBottom -> -delta
                else -> delta
            }
            val next = widthPercent + (grow / axisExtentPx * 100f).roundToInt()
            onWidthPercentChange(
                next.coerceIn(
                    AppPreferences.SECOND_SCREEN_WIDTH_MIN,
                    AppPreferences.SECOND_SCREEN_WIDTH_MAX
                )
            )
        }

        // Panel body: the emulated bottom screen only.
        Box(
            modifier = Modifier
                .align(
                    when {
                        isRight -> Alignment.CenterEnd
                        isLeft -> Alignment.CenterStart
                        isBottom -> Alignment.BottomCenter
                        else -> Alignment.TopCenter
                    }
                )
                .then(if (horizontalDock) Modifier.fillMaxHeight() else Modifier.fillMaxWidth())
                .then(
                    if (horizontalDock) {
                        Modifier
                            .width(with(density) { panelExtentPx.toDp() })
                            .offset { IntOffset(panelOffset, 0) }
                    } else {
                        Modifier
                            .height(with(density) { panelExtentPx.toDp() })
                            .offset { IntOffset(0, panelOffset) }
                    }
                )
        ) {
            SecondScreenBody(
                panelExtent = with(density) { panelExtentPx.toDp() },
                horizontalDock = horizontalDock,
                isLeft = isLeft,
                isTop = isTop,
                dragResizeState = dragResizeState
            )
        }

        // Docked toolbar tab.
        Box(
            modifier = Modifier
                .align(
                    when {
                        isRight -> Alignment.CenterEnd
                        isLeft -> Alignment.CenterStart
                        isBottom -> Alignment.BottomCenter
                        else -> Alignment.TopCenter
                    }
                )
                .padding(
                    start = if (isLeft) 6.dp else 0.dp,
                    end = if (isRight) 6.dp else 0.dp,
                    top = if (isTop) 6.dp else 0.dp,
                    bottom = if (isBottom) 6.dp else 0.dp
                )
                .offset {
                    val available = (axisExtentPx - tabCrossExtentPx).coerceAtLeast(0f)
                    val cross = ((tabFraction * available) - available / 2f).roundToInt()
                    if (horizontalDock) IntOffset(0, cross) else IntOffset(cross, 0)
                }
                .alpha(tabAlpha)
        ) {
            SecondScreenToolbarTab(
                open = open,
                side = side,
                horizontalDock = horizontalDock,
                tabExtent = tabExtent,
                tabCrossExtent = tabCrossExtent,
                onToggle = {
                    interactionTick++
                    tabActive = true
                    onOpenChange(!open)
                },
                onSwapScreens = {
                    interactionTick++
                    tabActive = true
                    onSwapScreens()
                },
                onSwitchSide = {
                    interactionTick++
                    tabActive = true
                    onSideChange(nextSecondScreenSide(side))
                },
                onMoveBy = { delta ->
                    interactionTick++
                    tabActive = true
                    val available = (axisExtentPx - tabCrossExtentPx).coerceAtLeast(1f)
                    tabFraction = (tabFraction + delta / available).coerceIn(0.04f, 0.96f)
                },
                onMoveEnd = { onTabPositionChange(tabFraction) }
            )
        }
    }
}

internal fun nextSecondScreenSide(side: Int): Int = when (side) {
    AppPreferences.SECOND_SCREEN_SIDE_RIGHT -> AppPreferences.SECOND_SCREEN_SIDE_TOP
    AppPreferences.SECOND_SCREEN_SIDE_TOP -> AppPreferences.SECOND_SCREEN_SIDE_LEFT
    AppPreferences.SECOND_SCREEN_SIDE_LEFT -> AppPreferences.SECOND_SCREEN_SIDE_BOTTOM
    else -> AppPreferences.SECOND_SCREEN_SIDE_RIGHT
}

@Composable
private fun SecondScreenToolbarTab(
    open: Boolean,
    side: Int,
    horizontalDock: Boolean,
    tabExtent: Dp,
    tabCrossExtent: Dp,
    onToggle: () -> Unit,
    onSwapScreens: () -> Unit,
    onSwitchSide: () -> Unit,
    onMoveBy: (Float) -> Unit,
    onMoveEnd: () -> Unit
) {
    val chevron = when (side) {
        AppPreferences.SECOND_SCREEN_SIDE_RIGHT ->
            if (open) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft
        AppPreferences.SECOND_SCREEN_SIDE_LEFT ->
            if (open) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight
        AppPreferences.SECOND_SCREEN_SIDE_TOP ->
            if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown
        else ->
            if (open) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.KeyboardArrowUp
    }
    val tabModifier = if (horizontalDock) {
        Modifier.width(tabExtent).height(tabCrossExtent)
    } else {
        Modifier.height(tabExtent).width(tabCrossExtent)
    }
    val tabShape = RoundedCornerShape(16.dp)
    Box(
        modifier = tabModifier
            .clip(tabShape)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                tabShape
            )
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
                tabShape
            )
            // The whole tab is a drag handle; taps still reach the buttons
            // because a parent pointer handler only consumes drags.
            .pointerInput(horizontalDock) {
                detectDragGestures(
                    onDragEnd = { onMoveEnd() },
                    onDragCancel = { onMoveEnd() }
                ) { change, dragAmount ->
                    change.consume()
                    onMoveBy(if (horizontalDock) dragAmount.y else dragAmount.x)
                }
            }
    ) {
        val content: @Composable () -> Unit = {
            TabActionIcon(
                icon = Icons.Rounded.SwapVert,
                description = R.string.emulation_second_screen_swap,
                onClick = onSwapScreens
            )
            Spacer(if (horizontalDock) Modifier.height(2.dp) else Modifier.width(2.dp))
            Icon(
                imageVector = Icons.Rounded.DragIndicator,
                contentDescription = stringResource(R.string.emulation_second_screen_move),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(if (horizontalDock) Modifier.height(2.dp) else Modifier.width(2.dp))
            TabActionIcon(
                icon = Icons.Rounded.SwapHoriz,
                description = R.string.emulation_second_screen_side,
                onClick = onSwitchSide
            )
            Spacer(if (horizontalDock) Modifier.height(6.dp) else Modifier.width(6.dp))
            Box(
                modifier = (if (horizontalDock) {
                    Modifier.size(width = 34.dp, height = 46.dp)
                } else {
                    Modifier.size(width = 46.dp, height = 34.dp)
                })
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                        RoundedCornerShape(12.dp)
                    )
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ScreenshotMonitor,
                        contentDescription = stringResource(R.string.emulation_second_screen),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Icon(
                        imageVector = chevron,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        if (horizontalDock) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { content() }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) { content() }
        }
    }
}

@Composable
private fun TabActionIcon(
    icon: ImageVector,
    description: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(description),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun SecondScreenBody(
    panelExtent: Dp,
    horizontalDock: Boolean,
    isLeft: Boolean,
    isTop: Boolean,
    dragResizeState: androidx.compose.foundation.gestures.DraggableState
) {
    val innerShape = when {
        isLeft -> RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp)
        isTop -> RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp)
        horizontalDock -> RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp)
        else -> RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
    }
    val resizeHandle = @Composable {
        Box(
            modifier = Modifier
                .then(
                    if (horizontalDock) {
                        Modifier.width(18.dp).fillMaxHeight()
                    } else {
                        Modifier.height(18.dp).fillMaxWidth()
                    }
                )
                .draggable(
                    orientation = if (horizontalDock) Orientation.Horizontal else Orientation.Vertical,
                    state = dragResizeState
                )
        )
    }

    if (horizontalDock) {
        Row(
            modifier = Modifier
                .width(panelExtent)
                .fillMaxHeight()
        ) {
            if (!isLeft) resizeHandle()
            Surface(
                shape = innerShape,
                color = Color.Black,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                SecondaryScreenSurface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(innerShape)
                )
            }
            if (isLeft) resizeHandle()
        }
    } else {
        Column(
            modifier = Modifier
                .height(panelExtent)
                .fillMaxWidth()
        ) {
            if (isTop) resizeHandle()
            Surface(
                shape = innerShape,
                color = Color.Black,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                SecondaryScreenSurface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(innerShape)
                )
            }
            if (!isTop) resizeHandle()
        }
    }
}

/**
 * The core presents its secondary window into this view. A TextureView is
 * used instead of a SurfaceView so the panel animates and clips with the rest
 * of the Compose UI.
 */
@Composable
private fun SecondaryScreenSurface(modifier: Modifier = Modifier) {
    val holder = remember { mutableStateOf<Surface?>(null) }
    AndroidView(
        factory = { ctx ->
            TextureView(ctx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        texture.setDefaultBufferSize(width, height)
                        val surface = Surface(texture)
                        holder.value = surface
                        EmulatorBridge.onSecondarySurfaceChanged(surface, width, height)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        texture: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) {
                        texture.setDefaultBufferSize(width, height)
                        holder.value?.let { surface ->
                            EmulatorBridge.onSecondarySurfaceChanged(surface, width, height)
                        }
                    }

                    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                        EmulatorBridge.onSecondarySurfaceDestroyed()
                        holder.value?.let { surface ->
                            holder.value = null
                            runCatching { surface.release() }
                        }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                }
            }
        },
        modifier = modifier.pointerInteropFilter { event -> forwardSecondaryTouch(event) }
    )
}

private fun forwardSecondaryTouch(event: MotionEvent): Boolean {
    if (!EmuCoreNative.isLoaded) return true
    return runCatching {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                EmuCoreNative.onSecondaryTouchEvent(event.x, event.y, true)
            MotionEvent.ACTION_MOVE ->
                EmuCoreNative.onSecondaryTouchMoved(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                EmuCoreNative.onSecondaryTouchEvent(event.x, event.y, false)
            else -> Unit
        }
        true
    }.getOrDefault(true)
}
