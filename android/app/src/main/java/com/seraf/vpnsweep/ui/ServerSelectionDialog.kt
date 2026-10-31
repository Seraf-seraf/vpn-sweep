package com.seraf.vpnsweep.ui

import com.seraf.vpnsweep.ServerChoice
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxColors
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private object ServerDialogColors {
    val surface = SweepColors.surface
    val text = SweepColors.text
    val secondary = SweepColors.secondary
    val outline = SweepColors.outline
    val accent = SweepColors.accent
    val accentText = SweepColors.onAccent
    val selected = Color(0xFF202A4D)
    val disabled = Color(0xFF617093)
}

@Composable
internal fun ServerSelectionDialogHost(
    servers: List<ServerChoice>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
): Unit {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        ServerSelectionDialogContent(servers, selectedIds, onDismiss, onConfirm)
    }
}

@Composable
internal fun ServerSelectionDialogContent(
    servers: List<ServerChoice>,
    selectedIds: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
): Unit {
    val supportedIds = remember(servers) {
        servers.filter { it.supported }.map { it.id }.toSet()
    }
    var selected by remember(servers, selectedIds) {
        mutableStateOf(selectedIds.intersect(supportedIds))
    }
    var search by remember { mutableStateOf("") }
    val filteredServers = remember(servers, search) {
        val query = search.trim()
        servers.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.subtitle.contains(query, ignoreCase = true)
        }
    }
    val visibleSupportedIds = remember(filteredServers) {
        filteredServers.filter { it.supported }.map { it.id }.toSet()
    }
    val visibleSelected = visibleSupportedIds.count { it in selected }
    val selectAllState = when {
        visibleSelected == 0 -> ToggleableState.Off
        visibleSelected == visibleSupportedIds.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = 700.dp),
            shape = RoundedCornerShape(24.dp),
            color = ServerDialogColors.surface,
            border = BorderStroke(1.dp, ServerDialogColors.outline),
        ) {
            val listState = rememberLazyListState()
            BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                val actions = dialogActionsLayout(maxWidth, horizontalPadding = 12.dp, outerPadding = 6.dp)
                val actionHeight = maxOf(48.dp, with(LocalDensity.current) { 22.sp.toDp() } + 16.dp) + 6.dp
                val footerHeight = if (actions.stacked) actionHeight * 2 + 8.dp else actionHeight
                val scrollEntireDialog = maxHeight < footerHeight + 48.dp
                Column(
                    modifier = Modifier.fillMaxWidth().then(
                        if (scrollEntireDialog) Modifier.verticalScroll(rememberScrollState()) else Modifier,
                    ),
                ) {
                    Column(
                        modifier = if (scrollEntireDialog) Modifier else
                            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    ) {
                        ServerDialogHeader()
                        Spacer(Modifier.height(16.dp))
                        ServerSearch(search, { search = it })
                        Spacer(Modifier.height(8.dp))
                        SelectAllServers(
                            state = selectAllState,
                            enabled = visibleSupportedIds.isNotEmpty(),
                            selectedCount = visibleSelected,
                            totalCount = visibleSupportedIds.size,
                            onClick = {
                                selected = updateVisibleSelection(
                                    selected = selected,
                                    visibleSupportedIds = visibleSupportedIds,
                                    select = selectAllState != ToggleableState.On,
                                )
                            },
                        )
                        Box(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(end = 10.dp),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (servers.isEmpty()) {
                                    item { ServerListMessage("Нет загруженных серверов") }
                                } else if (filteredServers.isEmpty()) {
                                    item { ServerListMessage("Серверы не найдены") }
                                } else {
                                    itemsIndexed(
                                        items = filteredServers,
                                        key = { index, server -> "$index:${server.id}" },
                                    ) { _, server ->
                                        ServerRow(
                                            server = server,
                                            checked = server.supported && server.id in selected,
                                            onCheckedChange = { checked ->
                                                selected = if (checked) {
                                                    selected + server.id
                                                } else {
                                                    selected - server.id
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                            ServerListScrollbar(listState, Modifier.matchParentSize())
                        }
                        ServerSelectionSummary(selectedCount = selected.size, noServers = servers.isEmpty())
                    }
                    ServerDialogFooter(
                        selectedCount = selected.size,
                        onDismiss = onDismiss,
                        onConfirm = { onConfirm(selected.toSet()) },
                        layout = actions,
                    )
                }
            }
        }
    }
}

internal fun updateVisibleSelection(
    selected: Set<String>,
    visibleSupportedIds: Set<String>,
    select: Boolean,
): Set<String> = if (select) selected + visibleSupportedIds else selected - visibleSupportedIds

@Composable
private fun ServerDialogHeader(): Unit {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Выберите серверы",
            color = ServerDialogColors.text,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Их конфигурации участвуют в подборе",
            color = ServerDialogColors.secondary,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ServerSearch(value: String, onValueChange: (String) -> Unit): Unit {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Поиск сервера" },
        label = { Text("Поиск сервера") },
        leadingIcon = {
            Canvas(Modifier.size(24.dp)) {
                val stroke = 2.dp.toPx()
                drawCircle(
                    color = ServerDialogColors.accent,
                    radius = size.minDimension * 0.29f,
                    center = Offset(size.width * 0.42f, size.height * 0.42f),
                    style = Stroke(width = stroke),
                )
                drawLine(
                    color = ServerDialogColors.accent,
                    start = Offset(size.width * 0.64f, size.height * 0.64f),
                    end = Offset(size.width * 0.88f, size.height * 0.88f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = ServerDialogColors.text,
            unfocusedTextColor = ServerDialogColors.text,
            focusedLabelColor = ServerDialogColors.accent,
            unfocusedLabelColor = ServerDialogColors.secondary,
            focusedBorderColor = ServerDialogColors.accent,
            unfocusedBorderColor = ServerDialogColors.outline,
            cursorColor = ServerDialogColors.accent,
        ),
    )
}

@Composable
private fun SelectAllServers(
    state: ToggleableState,
    enabled: Boolean,
    selectedCount: Int,
    totalCount: Int,
    onClick: () -> Unit,
): Unit {
    val textMeasurer = rememberTextMeasurer()
    val labelWidth = textMeasurer.measure(
        "Выбрать все",
        style = TextStyle(fontFamily = SweepSans, fontSize = 16.sp),
    ).size.width
    val countWidth = textMeasurer.measure(
        "Выбрано $selectedCount из $totalCount",
        style = TextStyle(fontFamily = SweepSans, fontSize = 14.sp),
    ).size.width
    val requiredWidth = with(LocalDensity.current) { (labelWidth + countWidth).toDp() } + 56.dp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stack = maxWidth < requiredWidth
        if (stack) {
            Column {
                SelectAllToggle(state, enabled, onClick, Modifier.fillMaxWidth())
                Text(
                    text = "Выбрано $selectedCount из $totalCount",
                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    color = ServerDialogColors.secondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectAllToggle(state, enabled, onClick, Modifier.weight(1f))
                Text(
                    text = "Выбрано $selectedCount из $totalCount",
                    color = ServerDialogColors.secondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
private fun SelectAllToggle(
    state: ToggleableState,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    Row(
        modifier = modifier
            .serverDialogFocusRing()
            .heightIn(min = 48.dp)
            .triStateToggleable(state = state, enabled = enabled, role = Role.Checkbox, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state = state,
            onClick = null,
            enabled = enabled,
            colors = serverCheckboxColors(),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Выбрать все",
            color = if (enabled) ServerDialogColors.text else ServerDialogColors.disabled,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        )
    }
}

@Composable
private fun ServerRow(server: ServerChoice, checked: Boolean, onCheckedChange: (Boolean) -> Unit): Unit {
    val borderColor by animateColorAsState(
        targetValue = if (checked) ServerDialogColors.accent else ServerDialogColors.outline,
        animationSpec = tween(durationMillis = 160),
        label = "server border",
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (checked) ServerDialogColors.selected else ServerDialogColors.surface,
        animationSpec = tween(durationMillis = 160),
        label = "server background",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .serverDialogFocusRing()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .background(backgroundColor, RoundedCornerShape(12.dp))
            .heightIn(min = 64.dp)
            .toggleable(value = checked, enabled = server.supported, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            enabled = server.supported,
            colors = serverCheckboxColors(),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.title,
                color = if (server.supported) ServerDialogColors.text else ServerDialogColors.disabled,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            )
            Text(
                text = server.subtitle,
                color = if (server.supported) ServerDialogColors.secondary else ServerDialogColors.disabled,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            if (!server.supported) {
                Text("Недоступен", color = ServerDialogColors.disabled, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun serverCheckboxColors(): CheckboxColors = CheckboxDefaults.colors(
    checkedColor = ServerDialogColors.accent,
    uncheckedColor = ServerDialogColors.accent,
    checkmarkColor = ServerDialogColors.accentText,
    disabledCheckedColor = ServerDialogColors.disabled,
    disabledUncheckedColor = ServerDialogColors.disabled,
    disabledIndeterminateColor = ServerDialogColors.disabled,
)

@Composable
private fun ServerListMessage(message: String): Unit {
    Text(
        text = message,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp),
        color = ServerDialogColors.secondary,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ServerListScrollbar(state: LazyListState, modifier: Modifier = Modifier): Unit {
    Canvas(modifier) {
        val layout = state.layoutInfo
        val items = layout.visibleItemsInfo
        if (items.isNotEmpty() && layout.totalItemsCount > 0) {
            val viewport = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
            val averageItemSize = items.sumOf { it.size }.toFloat() / items.size + 4.dp.toPx()
            val contentSize = averageItemSize * layout.totalItemsCount
            if (viewport > 0 && contentSize > viewport) {
                val thumbSize = (viewport * viewport / contentSize).coerceIn(24.dp.toPx().coerceAtMost(viewport), viewport)
                val scrollOffset = items.first().index * averageItemSize - items.first().offset
                val thumbOffset = (scrollOffset / (contentSize - viewport)).coerceIn(0f, 1f) * (viewport - thumbSize)
                drawRoundRect(
                    color = ServerDialogColors.outline,
                    topLeft = Offset(size.width - 4.dp.toPx(), 0f),
                    size = Size(4.dp.toPx(), size.height),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                drawRoundRect(
                    color = ServerDialogColors.accent,
                    topLeft = Offset(size.width - 4.dp.toPx(), thumbOffset),
                    size = Size(4.dp.toPx(), thumbSize),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun ServerSelectionSummary(
    selectedCount: Int,
    noServers: Boolean,
): Unit {
    HorizontalDivider(modifier = Modifier.padding(top = 8.dp), color = ServerDialogColors.outline)
    Text(
        text = when {
            noServers -> "Серверы не загружены"
            selectedCount == 0 -> "Выберите хотя бы один сервер"
            else -> "Выбрано $selectedCount ${serverCountWord(selectedCount)}"
        },
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
        color = ServerDialogColors.text,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    )
}

@Composable
private fun ServerDialogFooter(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    layout: DialogActionsLayout,
): Unit {
    Box(modifier = Modifier.fillMaxWidth()) {
        if (layout.stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerCancelButton(onDismiss, Modifier.fillMaxWidth())
                ServerConfirmButton(selectedCount > 0, onConfirm, Modifier.fillMaxWidth())
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerCancelButton(onDismiss, Modifier.weight(layout.cancelWeight))
                ServerConfirmButton(selectedCount > 0, onConfirm, Modifier.weight(layout.confirmWeight))
            }
        }
    }
}

@Composable
private fun ServerCancelButton(onClick: () -> Unit, modifier: Modifier = Modifier): Unit {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.serverDialogFocusRing().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, ServerDialogColors.accent),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = ServerDialogColors.text),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("Отмена", fontSize = 16.sp, lineHeight = 22.sp)
    }
}

@Composable
private fun ServerConfirmButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier): Unit {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.serverDialogFocusRing().heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = ServerDialogColors.accent,
            contentColor = ServerDialogColors.accentText,
            disabledContainerColor = ServerDialogColors.outline,
            disabledContentColor = ServerDialogColors.secondary,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text("Ок", fontSize = 16.sp, lineHeight = 22.sp)
    }
}

private fun Modifier.serverDialogFocusRing(): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    onFocusChanged { focused = it.isFocused || it.hasFocus }
        .border(2.dp, if (focused) ServerDialogColors.accent else Color.Transparent, RoundedCornerShape(16.dp))
        .padding(3.dp)
}

private fun serverCountWord(count: Int): String = when {
    count % 100 in 11..14 -> "серверов"
    count % 10 == 1 -> "сервер"
    count % 10 in 2..4 -> "сервера"
    else -> "серверов"
}
