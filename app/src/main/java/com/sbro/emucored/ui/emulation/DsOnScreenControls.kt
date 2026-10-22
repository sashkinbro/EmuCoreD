package com.sbro.emucored.ui.emulation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val DS_KEY_A = 96
private const val DS_KEY_B = 97
private const val DS_KEY_X = 99
private const val DS_KEY_Y = 100
private const val DS_KEY_L = 102
private const val DS_KEY_R = 103
private const val DS_KEY_START = 108
private const val DS_KEY_SELECT = 109
private const val DS_KEY_UP = 19
private const val DS_KEY_DOWN = 20
private const val DS_KEY_LEFT = 21
private const val DS_KEY_RIGHT = 22

@Composable
fun DsOnScreenControls(
    modifier: Modifier = Modifier,
    scaleFactor: Float = 1f,
    alpha: Float = 1f,
    safeInsets: PaddingValues = PaddingValues(0.dp),
    onPadInput: (keyCode: Int, range: Int, pressed: Boolean) -> Unit
) {
    val controlColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f * alpha)
    val fillColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f * alpha)
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f * alpha)
    val shoulderWidth = 92.dp * scaleFactor
    val shoulderHeight = 34.dp * scaleFactor
    val padSize = 156.dp * scaleFactor
    val padButton = 50.dp * scaleFactor
    val actionButton = 58.dp * scaleFactor
    val actionGap = 8.dp * scaleFactor

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(safeInsets)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DsHoldButton(
                    label = "L",
                    keyCode = DS_KEY_L,
                    onPadInput = onPadInput,
                    modifier = Modifier
                        .width(shoulderWidth)
                        .height(shoulderHeight),
                    shape = RoundedCornerShape(12.dp),
                    borderColor = controlColor,
                    fillColor = fillColor,
                    textColor = textColor
                )
                DsHoldButton(
                    label = "R",
                    keyCode = DS_KEY_R,
                    onPadInput = onPadInput,
                    modifier = Modifier
                        .width(shoulderWidth)
                        .height(shoulderHeight),
                    shape = RoundedCornerShape(12.dp),
                    borderColor = controlColor,
                    fillColor = fillColor,
                    textColor = textColor
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(padSize)) {
                    DsHoldButton(
                        label = "▲",
                        keyCode = DS_KEY_UP,
                        onPadInput = onPadInput,
                        modifier = Modifier
                            .size(padButton)
                            .align(Alignment.TopCenter),
                        shape = RoundedCornerShape(12.dp),
                        borderColor = controlColor,
                        fillColor = fillColor,
                        textColor = textColor
                    )
                    DsHoldButton(
                        label = "▼",
                        keyCode = DS_KEY_DOWN,
                        onPadInput = onPadInput,
                        modifier = Modifier
                            .size(padButton)
                            .align(Alignment.BottomCenter),
                        shape = RoundedCornerShape(12.dp),
                        borderColor = controlColor,
                        fillColor = fillColor,
                        textColor = textColor
                    )
                    DsHoldButton(
                        label = "◀",
                        keyCode = DS_KEY_LEFT,
                        onPadInput = onPadInput,
                        modifier = Modifier
                            .size(padButton)
                            .align(Alignment.CenterStart),
                        shape = RoundedCornerShape(12.dp),
                        borderColor = controlColor,
                        fillColor = fillColor,
                        textColor = textColor
                    )
                    DsHoldButton(
                        label = "▶",
                        keyCode = DS_KEY_RIGHT,
                        onPadInput = onPadInput,
                        modifier = Modifier
                            .size(padButton)
                            .align(Alignment.CenterEnd),
                        shape = RoundedCornerShape(12.dp),
                        borderColor = controlColor,
                        fillColor = fillColor,
                        textColor = textColor
                    )
                }

                Box(modifier = Modifier.size(padSize)) {
                    DsHoldButton(
                        label = "X",
                        keyCode = DS_KEY_X,
                        onPadInput = onPadInput,
                        modifier = Modifier
                            .size(actionButton)
                            .align(Alignment.TopCenter)
                            .offset(y = 4.dp * scaleFactor),
                        shape = CircleShape,
                        borderColor = controlColor,
                        fillColor = fillColor,
                        textColor = textColor
                    )
                    DsHoldButton(
                        label = "Y",
                        keyCode = DS_KEY_Y,
                        onPadInput = onPadInput,
                        modifier = Modifier
                            .size(actionButton)
                            .align(Alignment.CenterStart)
                            .offset(x = actionGap),
                        shape = CircleShape,
                        borderColor = controlColor,
                        fillColor = fillColor,
                        textColor = textColor
                    )
                    DsHoldButton(
                        label = "A",
                        keyCode = DS_KEY_A,
                        onPadInput = onPadInput,
                        modifier = Modifier
                            .size(actionButton)
                            .align(Alignment.CenterEnd)
                            .offset(x = -actionGap),
                        shape = CircleShape,
                        borderColor = controlColor,
                        fillColor = fillColor,
                        textColor = textColor
                    )
                    DsHoldButton(
                        label = "B",
                        keyCode = DS_KEY_B,
                        onPadInput = onPadInput,
                        modifier = Modifier
                            .size(actionButton)
                            .align(Alignment.BottomCenter)
                            .offset(y = -4.dp * scaleFactor),
                        shape = CircleShape,
                        borderColor = controlColor,
                        fillColor = fillColor,
                        textColor = textColor
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DsHoldButton(
                    label = "SELECT",
                    keyCode = DS_KEY_SELECT,
                    onPadInput = onPadInput,
                    modifier = Modifier
                        .width(92.dp * scaleFactor)
                        .height(30.dp * scaleFactor),
                    shape = RoundedCornerShape(10.dp),
                    borderColor = controlColor,
                    fillColor = fillColor,
                    textColor = textColor
                )
                Spacer(modifier = Modifier.width(18.dp))
                DsHoldButton(
                    label = "START",
                    keyCode = DS_KEY_START,
                    onPadInput = onPadInput,
                    modifier = Modifier
                        .width(92.dp * scaleFactor)
                        .height(30.dp * scaleFactor),
                    shape = RoundedCornerShape(10.dp),
                    borderColor = controlColor,
                    fillColor = fillColor,
                    textColor = textColor
                )
            }
        }
    }
}

@Composable
private fun DsHoldButton(
    label: String,
    keyCode: Int,
    onPadInput: (keyCode: Int, range: Int, pressed: Boolean) -> Unit,
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    borderColor: Color,
    fillColor: Color,
    textColor: Color
) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (pressed) borderColor.copy(alpha = 0.34f) else fillColor)
            .border(1.dp, borderColor, shape)
            .pointerInput(keyCode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    pressed = true
                    onPadInput(keyCode, 0, true)
                    waitForUpOrCancellation()
                    pressed = false
                    onPadInput(keyCode, 0, false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontWeight = FontWeight.SemiBold,
            fontSize = if (label.length > 1) 11.sp else 15.sp
        )
    }
}

@Composable
fun DsTouchscreenLayer(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onTouch: (x: Int, y: Int, pressed: Boolean) -> Unit
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(enabled, size) {
                if (!enabled || size.width <= 0 || size.height <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val point = mapDsTouch(down.position, size)
                    if (point == null) return@awaitEachGesture
                    down.consume()
                    onTouch(point.x.toInt(), point.y.toInt(), true)
                    waitForUpOrCancellation()
                    onTouch(0, 0, false)
                }
            }
    )
}

private fun mapDsTouch(position: Offset, size: IntSize): Offset? {
    val width = size.width.toFloat()
    val height = size.height.toFloat()
    if (width <= 0f || height <= 0f) return null
    val contentAspect = 256f / 386f
    var contentWidth = width
    var contentHeight = width / contentAspect
    if (contentHeight > height) {
        contentHeight = height
        contentWidth = height * contentAspect
    }
    val left = (width - contentWidth) / 2f
    val top = (height - contentHeight) / 2f
    val bottomTop = top
    val bottomHeight = contentHeight * (192f / 386f)
    if (position.x !in left..(left + contentWidth) ||
        position.y !in bottomTop..(bottomTop + bottomHeight)
    ) {
        return null
    }
    val x = ((position.x - left) / contentWidth * 256f).roundToInt().coerceIn(0, 255)
    val y = ((position.y - bottomTop) / bottomHeight * 192f).roundToInt().coerceIn(0, 191)
    return Offset(x.toFloat(), y.toFloat())
}
