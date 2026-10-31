package com.seraf.vpnsweep.ui

import com.seraf.vpnsweep.searchui.ScreenInformation

import com.seraf.vpnsweep.VpnPhase
import com.seraf.vpnsweep.VpnState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ScreenBackground = SweepColors.background
private val PanelBackground = SweepColors.surface
private val Accent = SweepColors.accent
private val PrimaryText = SweepColors.text
private val SecondaryText = SweepColors.secondary
private val Outline = SweepColors.outline
private val AccentText = SweepColors.onAccent
private val ControlShape = RoundedCornerShape(12.dp)

@Composable
internal fun VpnSweepScreenContent(
    state: VpnState,
    selected: Boolean,
    importing: Boolean,
    onPower: () -> Unit,
    onSubscription: () -> Unit,
    onServers: () -> Unit,
    permissionMessage: String = "",
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
    sourceEnabled: Boolean = true,
    searchEnabled: Boolean = false,
    searchRunning: Boolean = false,
    searchMessage: String = "Подбор настроек пока недоступен",
    onSearch: () -> Unit = {},
    information: ScreenInformation = ScreenInformation(),
) {
    val powerEnabled = !importing && state.phase != VpnPhase.STOPPING &&
        (selected || state.phase == VpnPhase.CONNECTING || state.phase == VpnPhase.RUNNING)
    val powerAction = when (state.phase) {
        VpnPhase.CONNECTING -> "Отменить подключение VPN"
        VpnPhase.RUNNING -> "Отключить VPN"
        VpnPhase.STOPPING -> "Отключение VPN"
        VpnPhase.DISCONNECTED, VpnPhase.ERROR -> "Подключить VPN"
    }
    val powerHint = when {
        importing -> "Дождитесь окончания чтения файла"
        state.phase == VpnPhase.STOPPING -> "Дождитесь отключения VPN"
        state.phase == VpnPhase.CONNECTING -> "Нажмите, чтобы отменить подключение"
        state.phase == VpnPhase.RUNNING -> "Нажмите, чтобы отключить VPN"
        !selected -> "Конфигурация VPN пока недоступна"
        else -> "Нажмите, чтобы подключить VPN"
    }

    Box(Modifier.fillMaxSize().background(ScreenBackground), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(contentWindowInsets)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "VPN Sweep",
                color = PrimaryText,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PowerControl(
                    size = minOf(maxWidth, 176.dp),
                    enabled = powerEnabled,
                    action = powerAction,
                    status = state.message,
                    onClick = onPower,
                )
            }
            Text(
                text = state.message,
                color = PrimaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = powerHint,
                color = SecondaryText,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            if (permissionMessage.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = permissionMessage,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = SweepColors.error,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(20.dp))
            SweepButton(
                text = "Добавить подписку",
                onClick = onSubscription,
                icon = SweepIcon.Link,
                filled = true,
                enabled = sourceEnabled,
            )
            Spacer(Modifier.height(8.dp))
            ServerButton(onClick = onServers, enabled = sourceEnabled)
            Spacer(Modifier.height(8.dp))
            SweepButton(
                text = if (searchRunning) "Отменить подбор" else "Подобрать настройки",
                onClick = onSearch,
                enabled = searchEnabled,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = searchMessage,
                modifier = Modifier.fillMaxWidth(),
                color = SecondaryText,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            InformationPanel(information = information)
        }
    }
}

@Composable
private fun PowerControl(
    size: Dp,
    enabled: Boolean,
    action: String,
    status: String,
    onClick: () -> Unit,
) {
    val glow = if (enabled) Color(0xFF5366FF) else Color(0xFF34405A)
    val face = if (enabled) Color(0xFF9E9AFF) else Color(0xFF4B5670)
    val iconColor = if (enabled) PrimaryText else Color(0xFFADB5C8)
    Box(
        modifier = Modifier
            .size(size)
            .focusRing(CircleShape)
            .clip(CircleShape)
            .semantics {
                contentDescription = action
                stateDescription = status
            }
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = action, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(glow.copy(alpha = 0.58f), glow.copy(alpha = 0f))),
                CircleShape,
            ),
        )
        Box(
            Modifier.fillMaxSize(0.91f).border(1.dp, glow, CircleShape),
        )
        Box(
            modifier = Modifier
                .fillMaxSize(0.75f)
                .border(2.dp, if (enabled) Color(0xFFD4D3FF) else Outline, CircleShape)
                .background(
                    Brush.radialGradient(listOf(face, face.copy(alpha = 0.72f))),
                    CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            SweepGlyph(SweepIcon.Power, iconColor, Modifier.size(size * 0.34f))
        }
    }
}

@Composable
private fun SweepButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    filled: Boolean = false,
    icon: SweepIcon? = null,
) {
    val foreground = when {
        !enabled -> SecondaryText.copy(alpha = 0.7f)
        filled -> AccentText
        else -> PrimaryText
    }
    val background = when {
        !enabled -> PanelBackground.copy(alpha = 0.5f)
        filled -> Accent
        else -> ScreenBackground
    }
    val borderColor = if (!enabled) Outline.copy(alpha = 0.6f) else Accent
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusRing(ControlShape)
            .clip(ControlShape)
            .border(1.dp, borderColor, ControlShape)
            .background(background, ControlShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            SweepGlyph(icon, foreground)
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = text,
            modifier = Modifier.weight(1f, fill = false),
            color = foreground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ServerButton(onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(ControlShape)
            .clip(ControlShape)
            .border(1.dp, Outline, ControlShape)
            .background(PanelBackground, ControlShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SweepGlyph(SweepIcon.Server, Accent)
        Spacer(Modifier.width(16.dp))
        Text("Серверы: нет данных", Modifier.weight(1f), color = PrimaryText, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        SweepGlyph(SweepIcon.ChevronRight, Accent)
    }
}

@Composable
internal fun InformationPanel(initialTab: Int = 0, information: ScreenInformation = ScreenInformation()) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
    val presentation = presentInformation(information)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Outline, RoundedCornerShape(16.dp))
            .background(PanelBackground.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup()) {
            InformationTab("Журнал", selectedTab == 0, { selectedTab = 0 }, Modifier.weight(1f))
            InformationTab("Параметры", selectedTab == 1, { selectedTab = 1 }, Modifier.weight(1f))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Outline, ControlShape)
                .background(ScreenBackground, ControlShape)
                .padding(16.dp),
        ) {
            if (selectedTab == 0) {
                EventLogPanel(presentation.events)
            } else {
                ParametersPanel(applied = presentation.applied)
            }
        }
    }
}

@Composable
internal fun EventLogPanel(entries: List<EventLogEntry>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SweepGlyph(SweepIcon.Terminal, Accent)
        Spacer(Modifier.width(12.dp))
        Text("Журнал событий", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.height(16.dp))
    Column(
        Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 200.dp).verticalScroll(rememberScrollState()),
    ) {
        if (entries.isEmpty()) {
            Text("Событий пока нет", color = SecondaryText, fontFamily = SweepMono, fontSize = 14.sp)
        } else {
            for (entry in entries) {
                Text("${entry.time}  [${entry.level}] ${entry.message}", color = PrimaryText, fontFamily = SweepMono, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun InformationTab(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .focusRing(ControlShape)
            .clip(ControlShape)
            .background(if (selected) Accent.copy(alpha = 0.16f) else Color.Transparent, ControlShape)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Bottom,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).heightIn(min = 45.dp).padding(horizontal = 8.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, color = if (selected) PrimaryText else SecondaryText, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (selected) Accent else Color.Transparent))
    }
}

@Composable
internal fun ParametersPanel(initialFragmentExpanded: Boolean = false, applied: AppliedParameterValues? = null) {
    Text("Параметры подбора", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (applied == null) {
        Text("Применённые параметры пока недоступны", color = SecondaryText, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
    }
    ParameterGroup("Fragment", SweepIcon.Fragment, listOf("Фрагментация", "Пакеты", "Длина", "Интервал", "Макс. частей"), initialFragmentExpanded, applied?.fragment)
    Spacer(Modifier.height(8.dp))
    ParameterGroup("Mux", SweepIcon.Mux, listOf("Mux", "TCP concurrency", "XUDP concurrency", "UDP 443"), values = applied?.mux)
    Spacer(Modifier.height(8.dp))
    ParameterGroup("TLS fingerprint", SweepIcon.Fingerprint, listOf("Fingerprint"), values = applied?.fingerprint)
    Spacer(Modifier.height(8.dp))
    ParameterGroup("Шум UDP", SweepIcon.Noise, listOf("Шум", "Записи"), values = applied?.udpNoise)
}

@Composable
private fun ParameterGroup(
    title: String,
    icon: SweepIcon,
    fields: List<String>,
    initialExpanded: Boolean = false,
    values: ParameterGroupValues? = null,
) {
    var expanded by rememberSaveable { mutableStateOf(initialExpanded) }
    Column(Modifier.fillMaxWidth().border(1.dp, Outline, ControlShape)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRing(ControlShape)
                .clip(ControlShape)
                .semantics { stateDescription = if (expanded) "Развёрнуто" else "Свёрнуто" }
                .clickable(role = Role.Button, onClick = { expanded = !expanded })
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SweepGlyph(icon, Accent)
            Spacer(Modifier.width(12.dp))
            ParameterPair(title, values?.summary ?: "Нет данных", Modifier.weight(1f), titleSize = 16)
            Spacer(Modifier.width(8.dp))
            SweepGlyph(if (expanded) SweepIcon.ChevronDown else SweepIcon.ChevronRight, Accent)
        }
        if (expanded) {
            val rows = values?.fields ?: fields.map { ParameterValue(it, "Нет данных") }
            for (field in rows) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(Outline))
                val fieldIcon = if (title == "Fragment") {
                    when (field.label) {
                        "Фрагментация" -> SweepIcon.Grid
                        "Пакеты" -> SweepIcon.FileText
                        "Длина" -> SweepIcon.ArrowLeftRight
                        "Интервал" -> SweepIcon.Clock
                        "Макс. частей" -> SweepIcon.Layers
                        else -> null
                    }
                } else null
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (fieldIcon != null) {
                        SweepGlyph(fieldIcon, Accent)
                        Spacer(Modifier.width(12.dp))
                    }
                    ParameterPair(field.label, field.value, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ParameterPair(label: String, value: String, modifier: Modifier = Modifier, titleSize: Int = 14) {
    val measurer = rememberTextMeasurer()
    val labelWidth = measurer.measure(label, style = TextStyle(fontFamily = SweepSans, fontSize = titleSize.sp)).size.width
    val valueWidth = measurer.measure(value, style = TextStyle(fontFamily = SweepSans, fontSize = 14.sp)).size.width
    val density = LocalDensity.current
    val requiredWidth = with(density) { (labelWidth + valueWidth).toDp() } + 24.dp
    BoxWithConstraints(modifier) {
        if (maxWidth >= requiredWidth && density.fontScale < 1.3f) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), color = PrimaryText, fontSize = titleSize.sp)
                Spacer(Modifier.width(12.dp))
                Text(value, color = SecondaryText, fontSize = 14.sp, textAlign = TextAlign.End)
            }
        } else {
            Column {
                Text(label, color = PrimaryText, fontSize = titleSize.sp)
                Text(value, color = SecondaryText, fontSize = 14.sp)
            }
        }
    }
}

private fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, Accent, shape) else Modifier)
}

private enum class SweepIcon {
    Power, Link, Server, Terminal, Fragment, Mux, Fingerprint, Noise, ChevronRight, ChevronDown,
    Grid, FileText, ArrowLeftRight, Clock, Layers,
}

@Composable
private fun SweepGlyph(icon: SweepIcon, color: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
                drawLine(color, Offset(x1, y1), Offset(x2, y2), 2f, cap = StrokeCap.Round)
            }
            when (icon) {
                SweepIcon.Power -> {
                    drawArc(color, -45f, 270f, false, Offset(4f, 4f), Size(16f, 16f), style = Stroke(2.5f, cap = StrokeCap.Round))
                    line(12f, 2f, 12f, 12f)
                }
                SweepIcon.Link -> {
                    val links = Path().apply {
                        moveTo(9.6f, 13.3f)
                        cubicTo(10.9f, 14.6f, 13f, 14.6f, 14.3f, 13.3f)
                        lineTo(17.1f, 10.5f)
                        cubicTo(20.3f, 7.3f, 15.6f, 2.6f, 12.4f, 5.8f)
                        lineTo(11.3f, 6.9f)
                        moveTo(12.4f, 10.5f)
                        cubicTo(11.1f, 9.2f, 9f, 9.2f, 7.7f, 10.5f)
                        lineTo(4.9f, 13.3f)
                        cubicTo(1.7f, 16.5f, 6.4f, 21.2f, 9.6f, 18f)
                        lineTo(10.7f, 16.9f)
                    }
                    drawPath(links, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    line(17f, 14f, 17f, 22f)
                    line(13f, 18f, 21f, 18f)
                }
                SweepIcon.Server -> {
                    for (top in listOf(3f, 9f, 15f)) {
                        drawRoundRect(color, Offset(2f, top), Size(20f, 5f), androidx.compose.ui.geometry.CornerRadius(1.5f))
                        for (x in listOf(5f, 8f, 11f)) {
                            drawCircle(PanelBackground, 0.7f, Offset(x, top + 2.5f))
                        }
                    }
                }
                SweepIcon.Terminal -> {
                    drawRoundRect(color, Offset(3f, 4f), Size(18f, 16f), androidx.compose.ui.geometry.CornerRadius(2f), style = Stroke(2f))
                    line(7f, 8f, 10f, 11f)
                    line(10f, 11f, 7f, 14f)
                    line(12f, 15f, 17f, 15f)
                }
                SweepIcon.Fragment -> {
                    val behind = Path().apply {
                        moveTo(16f, 8f); lineTo(16f, 6f); quadraticTo(16f, 4f, 14f, 4f)
                        lineTo(6f, 4f); quadraticTo(4f, 4f, 4f, 6f); lineTo(4f, 14f)
                        quadraticTo(4f, 16f, 6f, 16f); lineTo(8f, 16f)
                    }
                    drawPath(behind, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    drawRoundRect(color, Offset(8f, 8f), Size(12f, 12f), androidx.compose.ui.geometry.CornerRadius(2f), style = Stroke(2f))
                }
                SweepIcon.Mux -> {
                    drawCircle(color, 2.5f, Offset(7f, 5f), style = Stroke(2f))
                    drawCircle(color, 2.5f, Offset(7f, 19f), style = Stroke(2f))
                    drawCircle(color, 2.5f, Offset(18f, 19f), style = Stroke(2f))
                    line(7f, 7.5f, 7f, 16.5f)
                    val branch = Path().apply {
                        moveTo(7f, 11f); cubicTo(7f, 14f, 18f, 11f, 18f, 16.5f)
                    }
                    drawPath(branch, color, style = Stroke(2f, cap = StrokeCap.Round))
                }
                SweepIcon.Fingerprint -> {
                    val fingerprint = Path().apply {
                        moveTo(4f, 15f); lineTo(4f, 11f)
                        cubicTo(4f, 6.6f, 7.6f, 3f, 12f, 3f)
                        cubicTo(16.4f, 3f, 20f, 6.6f, 20f, 11f); lineTo(20f, 15f)
                        moveTo(6f, 19f); cubicTo(7.3f, 16.8f, 7.5f, 14.7f, 7.5f, 11.5f)
                        cubicTo(7.5f, 5.5f, 16.5f, 5.5f, 16.5f, 11.5f)
                        cubicTo(16.5f, 15.1f, 16.8f, 17.7f, 18.5f, 20f)
                        moveTo(9f, 21f); cubicTo(10.2f, 18.5f, 10.5f, 15.8f, 10.5f, 11.5f)
                        cubicTo(10.5f, 9.5f, 13.5f, 9.5f, 13.5f, 11.5f)
                        cubicTo(13.5f, 15.7f, 13.7f, 18.7f, 15f, 21f)
                    }
                    drawPath(fingerprint, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                SweepIcon.Noise -> {
                    line(3f, 10f, 3f, 14f)
                    line(7f, 7f, 7f, 17f)
                    line(11f, 4f, 11f, 20f)
                    line(15f, 8f, 15f, 16f)
                    line(19f, 10f, 19f, 14f)
                }
                SweepIcon.ChevronRight -> {
                    line(9f, 6f, 15f, 12f)
                    line(15f, 12f, 9f, 18f)
                }
                SweepIcon.ChevronDown -> {
                    line(6f, 9f, 12f, 15f)
                    line(12f, 15f, 18f, 9f)
                }
                SweepIcon.Grid -> {
                    for (x in listOf(5f, 12f, 19f)) {
                        for (y in listOf(5f, 12f, 19f)) drawCircle(color, 1.35f, Offset(x, y))
                    }
                }
                SweepIcon.FileText -> {
                    val file = Path().apply {
                        moveTo(6f, 3f); lineTo(14f, 3f); lineTo(19f, 8f); lineTo(19f, 21f)
                        lineTo(6f, 21f); close()
                        moveTo(14f, 3f); lineTo(14f, 8f); lineTo(19f, 8f)
                    }
                    drawPath(file, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    line(9f, 12f, 16f, 12f)
                    line(9f, 16f, 16f, 16f)
                }
                SweepIcon.ArrowLeftRight -> {
                    line(3f, 12f, 21f, 12f)
                    line(3f, 12f, 7f, 8f); line(3f, 12f, 7f, 16f)
                    line(21f, 12f, 17f, 8f); line(21f, 12f, 17f, 16f)
                }
                SweepIcon.Clock -> {
                    drawCircle(color, 9f, Offset(12f, 12f), style = Stroke(2f))
                    line(12f, 7f, 12f, 12f); line(12f, 12f, 16f, 14f)
                }
                SweepIcon.Layers -> {
                    val layers = Path().apply {
                        moveTo(12f, 2f); lineTo(22f, 7f); lineTo(12f, 12f); lineTo(2f, 7f); close()
                        moveTo(2f, 12f); lineTo(12f, 17f); lineTo(22f, 12f)
                        moveTo(2f, 17f); lineTo(12f, 22f); lineTo(22f, 17f)
                    }
                    drawPath(layers, color, style = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}
