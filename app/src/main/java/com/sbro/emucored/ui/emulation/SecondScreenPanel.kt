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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.ScreenshotMonitor
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
private val TAB_WIDTH = 42.dp
private val TAB_HEIGHT = 168.dp
private val TAB_EDGE_MARGIN = 8.dp

/**
 * Free-floating bottom screen.
 *
 * The main emulation view shows only the top 3DS screen; the bottom screen
 * lives in this movable panel. The tab works as a drag handle and can be
 * parked anywhere on the screen. The panel opens beside the tab, choosing the
 * side with enough room, and fades away after a few seconds while closed; a
 * double tap anywhere on the screen brings it back.
 */
@Composable
internal fun SecondScreenPanel(
    widthPercent: Int,
    position: Pair<Float, Float>,
    open: Boolean,
    revealSignal: Int,
    onOpenChange: (Boolean) -> Unit,
    onWidthPercentChange: (Int) -> Unit,
    onSwapScreens: () -> Unit,
    onPositionChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

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
        targetValue = if (tabActive || open) 1f else 0.45f,
        animationSpec = tween(durationMillis = 450),
        label = "secondScreenTabAlpha"
    )

    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val tabWidth = TAB_WIDTH.coerceAtMost(maxWidth.coerceAtLeast(1.dp))
        val tabHeight = TAB_HEIGHT.coerceAtMost(maxHeight.coerceAtLeast(1.dp))
        val tabWidthPx = with(density) { tabWidth.toPx() }
        val tabHeightPx = with(density) { tabHeight.toPx() }
        val tabMarginPx = with(density) { TAB_EDGE_MARGIN.toPx() }
        val availableX = (maxWidthPx - tabWidthPx - tabMarginPx * 2f).coerceAtLeast(1f)
        val availableY = (maxHeightPx - tabHeightPx - tabMarginPx * 2f).coerceAtLeast(1f)

        val minExtentPx = minOf(maxWidthPx, maxHeightPx)
        var currentWidthPercent by remember { mutableFloatStateOf(widthPercent.toFloat()) }
        LaunchedEffect(widthPercent) {
            if (abs(currentWidthPercent - widthPercent) > 0.01f) {
                currentWidthPercent = widthPercent.toFloat()
            }
        }
        val panelWidthPx = (minExtentPx * currentWidthPercent / 100f)
            .coerceIn(
                minExtentPx * AppPreferences.SECOND_SCREEN_WIDTH_MIN / 100f,
                minExtentPx * AppPreferences.SECOND_SCREEN_WIDTH_MAX / 100f
            )
            .coerceAtLeast(1f)
        val panelHeightPx = panelWidthPx * 3f / 4f
        val panelWidth = with(density) { panelWidthPx.toDp() }
        val panelHeight = with(density) { panelHeightPx.toDp() }

        var tabX by remember { mutableFloatStateOf(position.first.coerceIn(0f, 1f)) }
        var tabY by remember { mutableFloatStateOf(position.second.coerceIn(0f, 1f)) }
        LaunchedEffect(position) {
            if (abs(tabX - position.first) > 0.01f) tabX = position.first.coerceIn(0f, 1f)
            if (abs(tabY - position.second) > 0.01f) tabY = position.second.coerceIn(0f, 1f)
        }

        val tabLeftPx = tabMarginPx + tabX * availableX
        val tabTopPx = tabMarginPx + tabY * availableY
        val tabRightPx = tabLeftPx + tabWidthPx

        // Open the panel towards the side with more room. This follows the tab
        // while it is dragged, so the panel never ends up on the wrong side of
        // the tab near a screen edge.
        val spaceRightPx = maxWidthPx - tabRightPx
        val spaceLeftPx = tabLeftPx
        val opensRight = spaceRightPx >= spaceLeftPx
        val panelLeftPx = (if (opensRight) tabRightPx else tabLeftPx - panelWidthPx)
            .coerceIn(0f, (maxWidthPx - panelWidthPx).coerceAtLeast(0f))
        val panelTopPx = (tabTopPx + tabHeightPx / 2f - panelHeightPx / 2f)
            .coerceIn(0f, (maxHeightPx - panelHeightPx).coerceAtLeast(0f))

        val progress = remember { Animatable(if (open) 1f else 0f) }
        LaunchedEffect(open) {
            progress.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
            )
        }

        // Panel body: the emulated bottom screen only.
        if (progress.value > 0.01f) {
            val slideDirection = if (opensRight) -1f else 1f
            Box(
                modifier = Modifier
                    .offset {
                        val slide = slideDirection *
                            (1f - progress.value) * panelWidthPx * 0.35f
                        IntOffset(
                            x = (panelLeftPx + slide).roundToInt(),
                            y = panelTopPx.roundToInt()
                        )
                    }
                    .size(panelWidth, panelHeight)
                    .alpha(progress.value)
            ) {
                SecondScreenBody(
                    modifier = Modifier.fillMaxSize(),
                    interactive = open
                )
                if (open) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(32.dp)
                            .pointerInput(minExtentPx) {
                                fun commitWidth() {
                                    val next = currentWidthPercent.roundToInt()
                                        .let { it - (it % 2) }
                                        .coerceIn(
                                            AppPreferences.SECOND_SCREEN_WIDTH_MIN,
                                            AppPreferences.SECOND_SCREEN_WIDTH_MAX
                                        )
                                    currentWidthPercent = next.toFloat()
                                    onWidthPercentChange(next)
                                }
                                detectDragGestures(
                                    onDragEnd = { commitWidth() },
                                    onDragCancel = { commitWidth() }
                                ) { change, dragAmount ->
                                    change.consume()
                                    val deltaPercent =
                                        (dragAmount.x + dragAmount.y) * 0.5f /
                                            minExtentPx * 100f
                                    currentWidthPercent =
                                        (currentWidthPercent + deltaPercent).coerceIn(
                                            AppPreferences.SECOND_SCREEN_WIDTH_MIN.toFloat(),
                                            AppPreferences.SECOND_SCREEN_WIDTH_MAX.toFloat()
                                        )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DragIndicator,
                            contentDescription = stringResource(
                                R.string.settings_second_screen_width
                            ),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Floating toolbar tab. The whole tab is a drag handle; taps still
        // reach the buttons because the parent handler only consumes drags.
        Box(
            modifier = Modifier
                .offset { IntOffset(tabLeftPx.roundToInt(), tabTopPx.roundToInt()) }
                .alpha(tabAlpha)
                .pointerInput(availableX, availableY) {
                    detectDragGestures(
                        onDragEnd = { onPositionChange(tabX, tabY) },
                        onDragCancel = { onPositionChange(tabX, tabY) }
                    ) { change, dragAmount ->
                        change.consume()
                        interactionTick++
                        tabActive = true
                        tabX = (tabX + dragAmount.x / availableX).coerceIn(0f, 1f)
                        tabY = (tabY + dragAmount.y / availableY).coerceIn(0f, 1f)
                    }
                }
        ) {
            SecondScreenToolbarTab(
                open = open,
                opensRight = opensRight,
                width = tabWidth,
                height = tabHeight,
                onToggle = {
                    interactionTick++
                    tabActive = true
                    onOpenChange(!open)
                },
                onSwapScreens = {
                    interactionTick++
                    tabActive = true
                    onSwapScreens()
                }
            )
        }
    }
}

/**
 * Bottom screen as a free-floating window. Drag its handle to park it in any
 * corner, resize it from the corner grip, dock it back into the edge panel or
 * close it entirely.
 */
@Composable
internal fun FloatingSecondScreen(
    position: Pair<Float, Float>,
    sizePercent: Int,
    onPositionChange: (Float, Float) -> Unit,
    onSizePercentChange: (Int) -> Unit,
    onSwapScreens: () -> Unit,
    onDock: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        val maxWidthPx = with(density) { maxWidth.toPx() }
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val referenceExtentPx = minOf(maxWidthPx, maxHeightPx)
        var currentSizePercent by remember { mutableFloatStateOf(sizePercent.toFloat()) }
        LaunchedEffect(sizePercent) {
            if (abs(currentSizePercent - sizePercent) > 0.01f) {
                currentSizePercent = sizePercent.toFloat()
            }
        }
        val maxWindowWidthPx = (maxWidthPx * 0.8f).coerceAtLeast(1f)
        val minWindowWidthPx = 220f.coerceAtMost(maxWindowWidthPx)
        val windowWidthPx = (referenceExtentPx * currentSizePercent / 100f)
            .coerceIn(minWindowWidthPx, maxWindowWidthPx)
        val windowWidth = with(density) { windowWidthPx.toDp() }
        val handleHeight = 30.dp
        val screenHeight = windowWidth * 3f / 4f

        var currentX by remember { mutableFloatStateOf(position.first.coerceIn(0f, 1f)) }
        var currentY by remember { mutableFloatStateOf(position.second.coerceIn(0f, 1f)) }
        LaunchedEffect(position) {
            if (abs(currentX - position.first) > 0.01f) currentX = position.first.coerceIn(0f, 1f)
            if (abs(currentY - position.second) > 0.01f) currentY = position.second.coerceIn(0f, 1f)
        }

        val windowWidthPxDp = windowWidthPx
        val windowHeightPx = with(density) { (screenHeight + handleHeight).toPx() }
        val availableX = (maxWidthPx - windowWidthPxDp).coerceAtLeast(1f)
        val availableY = (maxHeightPx - windowHeightPx).coerceAtLeast(1f)

        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (currentX * availableX).roundToInt(),
                        (currentY * availableY).roundToInt()
                    )
                }
                .width(windowWidth)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.88f),
                    RoundedCornerShape(14.dp)
                )
                .border(
                    BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)),
                    RoundedCornerShape(14.dp)
                )
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(handleHeight)
                        .pointerInput(availableX, availableY) {
                            detectDragGestures(
                                onDragEnd = { onPositionChange(currentX, currentY) },
                                onDragCancel = { onPositionChange(currentX, currentY) }
                            ) { change, dragAmount ->
                                change.consume()
                                currentX = (currentX + dragAmount.x / availableX).coerceIn(0f, 1f)
                                currentY = (currentY + dragAmount.y / availableY).coerceIn(0f, 1f)
                            }
                        },
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DragIndicator,
                        contentDescription = stringResource(R.string.emulation_second_screen_move),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(18.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    FloatingWindowButton(
                        icon = Icons.Rounded.SwapVert,
                        description = R.string.emulation_second_screen_swap,
                        onClick = onSwapScreens
                    )
                    FloatingWindowButton(
                        icon = Icons.Rounded.ScreenshotMonitor,
                        description = R.string.settings_second_screen_panel,
                        onClick = onDock
                    )
                    FloatingWindowButton(
                        icon = Icons.Rounded.Close,
                        description = R.string.emulation_second_screen_hide,
                        onClick = onClose
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(screenHeight)
                ) {
                    SecondaryScreenSurface(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(32.dp)
                            .pointerInput(referenceExtentPx) {
                                fun commitSize() {
                                    val size = currentSizePercent.roundToInt().coerceIn(
                                        AppPreferences.SECOND_SCREEN_FLOAT_SIZE_MIN,
                                        AppPreferences.SECOND_SCREEN_FLOAT_SIZE_MAX
                                    )
                                    currentSizePercent = size.toFloat()
                                    onSizePercentChange(size)
                                }
                                detectDragGestures(
                                    onDragEnd = { commitSize() },
                                    onDragCancel = { commitSize() }
                                ) { change, dragAmount ->
                                    change.consume()
                                    val deltaPercent =
                                        (dragAmount.x + dragAmount.y) * 0.5f /
                                            referenceExtentPx * 100f
                                    currentSizePercent = (currentSizePercent + deltaPercent)
                                        .coerceIn(
                                            AppPreferences.SECOND_SCREEN_FLOAT_SIZE_MIN.toFloat(),
                                            AppPreferences.SECOND_SCREEN_FLOAT_SIZE_MAX.toFloat()
                                        )
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DragIndicator,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FloatingWindowButton(
    icon: ImageVector,
    description: Int,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(description),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun SecondScreenToolbarTab(
    open: Boolean,
    opensRight: Boolean,
    width: Dp,
    height: Dp,
    onToggle: () -> Unit,
    onSwapScreens: () -> Unit
) {
    val chevron = if (open == opensRight) {
        Icons.Rounded.ChevronLeft
    } else {
        Icons.Rounded.ChevronRight
    }
    val tabShape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(tabShape)
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                tabShape
            )
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                tabShape
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            TabActionIcon(
                icon = Icons.Rounded.SwapVert,
                description = R.string.emulation_second_screen_swap,
                onClick = onSwapScreens
            )
            Spacer(Modifier.height(4.dp))
            Icon(
                imageVector = Icons.Rounded.DragIndicator,
                contentDescription = stringResource(R.string.emulation_second_screen_move),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 46.dp)
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
    modifier: Modifier = Modifier,
    interactive: Boolean
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        shape = shape,
        color = Color.Black,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)),
        modifier = modifier
    ) {
        SecondaryScreenSurface(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape),
            interactive = interactive
        )
    }
}

/**
 * The core presents its secondary window into this view. A TextureView is
 * used instead of a SurfaceView so the panel animates and clips with the rest
 * of the Compose UI.
 */
@Composable
private fun SecondaryScreenSurface(
    modifier: Modifier = Modifier,
    interactive: Boolean = true
) {
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
        modifier = if (interactive) {
            modifier.pointerInteropFilter { event -> forwardSecondaryTouch(event) }
        } else {
            modifier
        }
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
