package com.seraf.vpnsweep.ui

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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
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
    message: String,
    testing: Boolean,
    checkResults: List<String>,
    onPower: () -> Unit,
    onImport: () -> Unit,
    onRunChecks: () -> Unit,
    onSubscription: () -> Unit,
    onServers: () -> Unit,
    permissionMessage: String = "",
    contentWindowInsets: WindowInsets = WindowInsets.safeDrawing,
) {
    val busy = state.phase in setOf(VpnPhase.CONNECTING, VpnPhase.RUNNING, VpnPhase.STOPPING)
    val powerEnabled = selected && !importing && state.phase != VpnPhase.STOPPING
    val powerAction = when (state.phase) {
        VpnPhase.CONNECTING -> "Отменить подключение VPN"
        VpnPhase.RUNNING -> "Отключить VPN"
        VpnPhase.STOPPING -> "Отключение VPN"
        VpnPhase.DISCONNECTED, VpnPhase.ERROR -> "Подключить VPN"
    }
    val powerHint = when {
        importing -> "Дождитесь окончания чтения файла"
        !selected -> "Выберите working.json для управления VPN"
        state.phase == VpnPhase.STOPPING -> "Дождитесь отключения VPN"
        state.phase == VpnPhase.CONNECTING -> "Нажмите, чтобы отменить подключение"
        state.phase == VpnPhase.RUNNING -> "Нажмите, чтобы отключить VPN"
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
            )
            Spacer(Modifier.height(8.dp))
            ServerButton(onClick = onServers)
            Spacer(Modifier.height(8.dp))
            SweepButton(text = "Подобрать настройки", onClick = {}, enabled = false)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Подбор настроек пока недоступен",
                modifier = Modifier.fillMaxWidth(),
                color = SecondaryText,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(16.dp))
            InformationPanel()
            Spacer(Modifier.height(16.dp))
            DiagnosticsPanel(
                state = state,
                importing = importing,
                importEnabled = !busy && !importing,
                message = message,
                testing = testing,
                checkResults = checkResults,
                onImport = onImport,
                onRunChecks = onRunChecks,
            )
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
private fun ServerButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusRing(ControlShape)
            .clip(ControlShape)
            .border(1.dp, Outline, ControlShape)
            .background(PanelBackground, ControlShape)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SweepGlyph(SweepIcon.Server, Accent)
        Spacer(Modifier.width(16.dp))
        Text("Серверы: нет данных", Modifier.weight(1f), color = PrimaryText, fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        SweepGlyph(SweepIcon.ChevronRight, SecondaryText)
    }
}

@Composable
internal fun InformationPanel(initialTab: Int = 0) {
    var selectedTab by rememberSaveable { mutableStateOf(initialTab) }
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
                // TODO: connect actual event entries when the runtime exposes them.
                EventLogPanel(emptyList())
            } else {
                ParametersPanel()
            }
        }
    }
}

internal data class EventLogEntry(val time: String, val level: String, val message: String)

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

internal data class ParameterValue(val label: String, val value: String)
internal data class ParameterGroupValues(val summary: String, val fields: List<ParameterValue>)
internal data class AppliedParameterValues(
    val fragment: ParameterGroupValues? = null,
    val mux: ParameterGroupValues? = null,
    val fingerprint: ParameterGroupValues? = null,
    val udpNoise: ParameterGroupValues? = null,
)

@Composable
internal fun ParametersPanel(initialFragmentExpanded: Boolean = false, applied: AppliedParameterValues? = null) {
    Text("Параметры подбора", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    if (applied == null) {
        Text("Применённые параметры пока недоступны", color = SecondaryText, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
    }
    // TODO: Подключить отображение применённых параметров, когда они появятся в runtime-состоянии.
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
                ParameterPair(field.label, field.value, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
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

@Composable
private fun DiagnosticsPanel(
    state: VpnState,
    importing: Boolean,
    importEnabled: Boolean,
    message: String,
    testing: Boolean,
    checkResults: List<String>,
    onImport: () -> Unit,
    onRunChecks: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Outline, RoundedCornerShape(16.dp))
            .background(PanelBackground, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Диагностика", color = PrimaryText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        SweepButton(
            text = if (importing) "Чтение…" else "Выбрать working.json",
            onClick = onImport,
            enabled = importEnabled,
        )
        if (message.isNotEmpty()) {
            Text(message, color = PrimaryText, fontFamily = SweepMono, style = MaterialTheme.typography.bodyMedium)
        }
        SweepButton(
            text = if (testing) "Проверка сети…" else "Проверить TCP, UDP и DNS",
            onClick = onRunChecks,
            enabled = state.phase == VpnPhase.RUNNING && !testing,
        )
        for (result in checkResults) {
            Text(result, color = PrimaryText, fontFamily = SweepMono, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.outboundDigest.isNotEmpty()) {
            Text("Защищено сокетов: ${state.protectedSockets}", color = PrimaryText, fontFamily = SweepMono, fontSize = 14.sp)
            Text("Ошибок защиты: ${state.protectionFailures}", color = PrimaryText, fontFamily = SweepMono, fontSize = 14.sp)
            Text("Данные TUN → Xray: ${state.tunUplinkBytes} байт", color = PrimaryText, fontFamily = SweepMono, fontSize = 14.sp)
            Text("Данные Xray → TUN: ${state.tunDownlinkBytes} байт", color = PrimaryText, fontFamily = SweepMono, fontSize = 14.sp)
            Text("SHA-256 outbounds:\n${state.outboundDigest}", color = PrimaryText, fontFamily = SweepMono, fontSize = 14.sp)
        } else {
            Text("Счётчики пока недоступны", color = SecondaryText, fontSize = 14.sp)
        }
    }
}

private fun Modifier.focusRing(shape: Shape): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused }
        .then(if (focused) Modifier.border(2.dp, Accent, shape) else Modifier)
}

private enum class SweepIcon { Power, Link, Server, Terminal, Fragment, Mux, Fingerprint, Noise, ChevronRight, ChevronDown }

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
                    drawArc(color, 135f, 180f, false, Offset(3f, 9f), Size(11f, 11f), style = Stroke(2f, cap = StrokeCap.Round))
                    drawArc(color, 315f, 180f, false, Offset(10f, 3f), Size(11f, 11f), style = Stroke(2f, cap = StrokeCap.Round))
                    line(8f, 16f, 16f, 8f)
                }
                SweepIcon.Server -> {
                    drawRoundRect(color, Offset(3f, 4f), Size(18f, 7f), androidx.compose.ui.geometry.CornerRadius(2f), style = Stroke(2f))
                    drawRoundRect(color, Offset(3f, 13f), Size(18f, 7f), androidx.compose.ui.geometry.CornerRadius(2f), style = Stroke(2f))
                    drawCircle(color, 1f, Offset(7f, 7.5f))
                    drawCircle(color, 1f, Offset(7f, 16.5f))
                }
                SweepIcon.Terminal -> {
                    drawRoundRect(color, Offset(3f, 4f), Size(18f, 16f), androidx.compose.ui.geometry.CornerRadius(2f), style = Stroke(2f))
                    line(7f, 8f, 10f, 11f)
                    line(10f, 11f, 7f, 14f)
                    line(12f, 15f, 17f, 15f)
                }
                SweepIcon.Fragment -> {
                    drawRoundRect(color, Offset(4f, 4f), Size(12f, 12f), androidx.compose.ui.geometry.CornerRadius(2f), style = Stroke(2f))
                    drawRoundRect(color, Offset(8f, 8f), Size(12f, 12f), androidx.compose.ui.geometry.CornerRadius(2f), style = Stroke(2f))
                }
                SweepIcon.Mux -> {
                    drawCircle(color, 2.5f, Offset(7f, 5f), style = Stroke(2f))
                    drawCircle(color, 2.5f, Offset(7f, 19f), style = Stroke(2f))
                    drawCircle(color, 2.5f, Offset(18f, 16f), style = Stroke(2f))
                    line(7f, 8f, 7f, 16f)
                    line(8f, 11f, 16f, 14f)
                }
                SweepIcon.Fingerprint -> {
                    drawArc(color, 180f, 180f, false, Offset(4f, 3f), Size(16f, 16f), style = Stroke(2f, cap = StrokeCap.Round))
                    drawArc(color, 180f, 180f, false, Offset(8f, 7f), Size(8f, 8f), style = Stroke(2f, cap = StrokeCap.Round))
                    line(4f, 11f, 4f, 17f)
                    line(8f, 11f, 8f, 20f)
                    line(12f, 11f, 12f, 21f)
                    line(16f, 11f, 16f, 19f)
                    line(20f, 11f, 20f, 16f)
                }
                SweepIcon.Noise -> {
                    line(3f, 10f, 3f, 14f)
                    line(7f, 7f, 7f, 17f)
                    line(11f, 4f, 11f, 20f)
                    line(15f, 8f, 15f, 16f)
                    line(19f, 10f, 19f, 14f)
                }
                SweepIcon.ChevronRight -> {
                    line(9f, 5f, 16f, 12f)
                    line(16f, 12f, 9f, 19f)
                }
                SweepIcon.ChevronDown -> {
                    line(5f, 9f, 12f, 16f)
                    line(12f, 16f, 19f, 9f)
                }
            }
        }
    }
}
