package com.example

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Nothing X look: dotted arrows, grouped rounded rows, plain icons, no helper text. */
private val RowBg = Color(0xFF1C1C1E)

@Composable
fun DottedBack(onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 40.dp).clickable(onClick = onClick).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
        Text("back", color = NColors.Text, fontFamily = NDot, fontSize = 15.sp)
    }
}

@Composable
fun DottedChevron() {
    Text(">", color = Color(0xFFBDBDBD), fontFamily = NDot, fontSize = 18.sp)
}

@Composable
fun DottedDivider(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(2.dp)) {
        val y = size.height / 2f
        drawLine(
            color = NColors.Line,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 4.dp.toPx()), 0f)
        )
    }
}

/** Big left-aligned page title under a dotted back arrow. */
@Composable
fun NPageHeader(title: String, onBack: (() -> Unit)?) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 12.dp)) {
        if (onBack != null) DottedBack(onBack)
        Spacer(Modifier.height(10.dp))
        Text(title, color = NColors.Text, fontFamily = NHeadline, fontSize = 30.sp, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun SSection(text: String) {
    Text(text, color = NColors.Text, fontFamily = NType, fontSize = 17.sp, modifier = Modifier.padding(start = 8.dp, top = 22.dp, bottom = 10.dp))
}

/** Rows stacked with small gaps; first and last get the big rounded corners. */
@Composable
fun SGroup(vararg rows: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        rows.forEachIndexed { i, row ->
            val big = 26.dp
            val small = 6.dp
            val shape = RoundedCornerShape(
                topStart = if (i == 0) big else small, topEnd = if (i == 0) big else small,
                bottomStart = if (i == rows.lastIndex) big else small, bottomEnd = if (i == rows.lastIndex) big else small
            )
            Box(Modifier.fillMaxWidth().background(RowBg, shape)) { row() }
        }
    }
}

@Composable
fun SRow(
    label: String,
    icon: ImageVector? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = onClick != null && value == null,
    labelFont: androidx.compose.ui.text.font.FontFamily = NType,
    labelSize: androidx.compose.ui.unit.TextUnit = 15.sp,
    help: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    var showHelp by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(60.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(label, color = NColors.Text, fontFamily = labelFont, fontSize = labelSize, lineHeight = 22.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (value != null) Text(value, color = NColors.Dim, fontFamily = NType, fontSize = 15.sp)
        if (help != null) {
            Text("?", color = NColors.Dim, fontFamily = NDot, fontSize = 20.sp,
                modifier = Modifier.clickable { showHelp = true }.padding(horizontal = 10.dp))
        }
        trailing?.invoke()
        if (chevron) DottedChevron()
    }
    if (showHelp && help != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showHelp = false },
            containerColor = NColors.Card,
            text = { Text(help, color = NColors.Text, fontFamily = NType, fontSize = 15.sp) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { showHelp = false }) { Text("OK", color = NColors.Text, fontFamily = NType) } }
        )
    }
}

@Composable
fun SSwitchRow(label: String, checked: Boolean, icon: ImageVector? = null, help: String? = null, onChange: (Boolean) -> Unit) {
    SRow(label, icon, onClick = { onChange(!checked) }, chevron = false, help = help) {
        Switch(checked = checked, onCheckedChange = onChange, colors = nSwitchColors())
    }
}
