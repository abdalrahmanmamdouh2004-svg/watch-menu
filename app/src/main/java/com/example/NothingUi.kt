package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt

object NColors {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF161618)
    val CardHi = Color(0xFF262629)
    val Line = Color(0xFF303033)
    val Text = Color(0xFFFFFFFF)
    val Dim = Color(0xFF8E8E93)
    val Red = Color(0xFFD71921)
    val Green = Color(0xFF6FD08C)
}

// Font roles live here: change one line to swap a font everywhere.
val NDot = FontFamily(Font(R.font.ndot55))               // dotted accents: item names, values
val NType = FontFamily(Font(R.font.ntype82_mono))        // titles and small labels
val NHeadline = FontFamily(Font(R.font.ntype82_headline))
val NBody = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium)
)

@Composable
fun NothingTheme(content: @Composable () -> Unit) {
    val base = Typography()
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontFamily = NType),
        headlineMedium = base.headlineMedium.copy(fontFamily = NType),
        headlineSmall = base.headlineSmall.copy(fontFamily = NType),
        titleLarge = base.titleLarge.copy(fontFamily = NType),
        titleMedium = base.titleMedium.copy(fontFamily = NType),
        titleSmall = base.titleSmall.copy(fontFamily = NType),
        bodyLarge = base.bodyLarge.copy(fontFamily = NType),
        bodyMedium = base.bodyMedium.copy(fontFamily = NType),
        bodySmall = base.bodySmall.copy(fontFamily = NType),
        labelLarge = base.labelLarge.copy(fontFamily = NType),
        labelMedium = base.labelMedium.copy(fontFamily = NType),
        labelSmall = base.labelSmall.copy(fontFamily = NType)
    )
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = NColors.Red,
            onPrimary = Color.White,
            background = NColors.Bg,
            onBackground = Color.White,
            surface = NColors.Card,
            onSurface = Color.White,
            surfaceVariant = NColors.CardHi,
            onSurfaceVariant = NColors.Dim,
            outline = NColors.Line
        ),
        typography = typography,
        content = { androidx.compose.material3.ProvideTextStyle(typography.bodyMedium, content) }
    )
}

@Composable
fun NTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(96.dp), contentAlignment = Alignment.CenterStart) {
            if (onBack != null) {
                DottedBack(onBack)
            } else {
                leading?.invoke()
            }
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            color = NColors.Text,
            fontFamily = NType,
            fontSize = 22.sp
        )
        Row(Modifier.width(96.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
            trailing?.invoke(this)
        }
    }
}

@Composable
fun NButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) NColors.Red else NColors.CardHi,
            contentColor = Color.White,
            disabledContainerColor = NColors.Card,
            disabledContentColor = NColors.Dim
        )
    ) { Text(text, fontFamily = NType, fontSize = 14.sp) }
}

@Composable
fun NField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    enabled: Boolean = true
) {
    OutlinedTextField(
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontFamily = NType) },
        singleLine = singleLine,
        modifier = modifier.fillMaxWidth(),
        trailingIcon = trailing,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = NColors.Red,
            unfocusedBorderColor = NColors.Line,
            focusedLabelColor = NColors.Red,
            unfocusedLabelColor = NColors.Dim,
            cursorColor = NColors.Red,
            disabledTextColor = NColors.Dim,
            disabledBorderColor = NColors.Line,
            disabledLabelColor = NColors.Dim
        )
    )
}

@Composable
fun NCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NColors.Card, RoundedCornerShape(18.dp))
            .padding(14.dp),
        content = content
    )
}

@Composable
fun nSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = NColors.Red,
    uncheckedThumbColor = NColors.Dim,
    uncheckedTrackColor = NColors.CardHi,
    uncheckedBorderColor = NColors.Line
)

@Composable
fun nCheckboxColors() = CheckboxDefaults.colors(
    checkedColor = NColors.Red,
    uncheckedColor = NColors.Dim,
    checkmarkColor = Color.White
)

/**
 * A column of fixed-height rows that can be reordered by dragging the handle modifier that is
 * passed to each row. The dragged row follows the finger; the order changes when it is dropped.
 */
@Composable
fun <T> ReorderableList(
    items: List<T>,
    idOf: (T) -> String,
    rowHeight: Dp,
    onMove: (from: Int, to: Int) -> Unit,
    row: @Composable (item: T, handle: Modifier) -> Unit
) {
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }

    Column {
        items.forEachIndexed { index, item ->
            val id = idOf(item)
            key(id) {
                val dragging = dragId == id
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(rowHeight)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f }
                        .alpha(if (dragging) 0.85f else 1f)
                ) {
                    row(
                        item,
                        Modifier.pointerInput(id, index, items.size) {
                            detectDragGestures(
                                onDragStart = {
                                    dragId = id
                                    dragOffset = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragOffset += amount.y
                                },
                                onDragEnd = {
                                    val to = (index + (dragOffset / rowPx).roundToInt())
                                        .coerceIn(0, items.lastIndex)
                                    dragId = null
                                    dragOffset = 0f
                                    if (to != index) onMove(index, to)
                                },
                                onDragCancel = {
                                    dragId = null
                                    dragOffset = 0f
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

fun smallLabel(color: Color = NColors.Dim) = TextStyle(fontFamily = NType, fontSize = 11.sp, color = color)
