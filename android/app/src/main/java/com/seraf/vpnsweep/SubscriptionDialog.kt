package com.seraf.vpnsweep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.net.URI
import java.net.URISyntaxException

private val SubscriptionSurface = SweepColors.surface
private val SubscriptionAccent = SweepColors.accent
private val SubscriptionText = SweepColors.text
private val SubscriptionSecondaryText = SweepColors.secondary
private val SubscriptionOutline = SweepColors.outline
private val SubscriptionError = SweepColors.error
private val SubscriptionAccentText = SweepColors.onAccent
private val SubscriptionDisabled = Color(0xFF20314F)

internal enum class SubscriptionDialogStatus {
    Empty,
    Input,
    Loading,
    InvalidUrl,
    NetworkError,
    MalformedData,
    Unavailable,
}

@Composable
fun SubscriptionDialog(onDismiss: () -> Unit): Unit {
    var link by remember { mutableStateOf("") }
    val valid = remember(link) { isValidSubscriptionUrl(link) }
    val status = when {
        link.isBlank() -> SubscriptionDialogStatus.Empty
        !valid -> SubscriptionDialogStatus.InvalidUrl
        else -> SubscriptionDialogStatus.Unavailable
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            SubscriptionDialogContent(
                link = link,
                status = status,
                onLinkChange = { link = it },
                onDismiss = onDismiss,
                onSubmit = {
                    // TODO: подключить загрузку подписки, когда интеграция будет доступна.
                },
            )
        }
    }
}

internal fun isValidSubscriptionUrl(value: String): Boolean {
    return try {
        val uri = URI(value.trim())
        (uri.scheme.equals("http", ignoreCase = true) ||
            uri.scheme.equals("https", ignoreCase = true)) &&
            !uri.host.isNullOrEmpty() && (uri.port == -1 || uri.port in 0..65535)
    } catch (_: URISyntaxException) {
        false
    }
}

@Composable
internal fun SubscriptionDialogContent(
    link: String,
    status: SubscriptionDialogStatus,
    onLinkChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
): Unit {
    val loading = status == SubscriptionDialogStatus.Loading
    val canSubmit = when (status) {
        SubscriptionDialogStatus.Input,
        SubscriptionDialogStatus.NetworkError,
        SubscriptionDialogStatus.MalformedData -> isValidSubscriptionUrl(link)
        else -> false
    }
    val focusManager = LocalFocusManager.current
    val submit: () -> Unit = {
        if (canSubmit) {
            focusManager.clearFocus()
            onSubmit()
        }
    }

    Surface(
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = SubscriptionSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, SubscriptionOutline),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = "Добавить подписку",
                    modifier = Modifier.fillMaxWidth(),
                    color = SubscriptionText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 30.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Вставьте ссылку на подписку",
                    modifier = Modifier.fillMaxWidth(),
                    color = SubscriptionSecondaryText,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = "Ссылка",
                    color = SubscriptionText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                Spacer(Modifier.height(8.dp))
                SubscriptionLinkField(
                    link = link,
                    status = status,
                    enabled = !loading,
                    onLinkChange = onLinkChange,
                    onSubmit = submit,
                )
                Spacer(Modifier.height(16.dp))
                SubscriptionStatusMessage(status)
                Spacer(Modifier.height(24.dp))
            }
            SubscriptionActions(
                canSubmit = canSubmit,
                loading = loading,
                onDismiss = onDismiss,
                onSubmit = submit,
            )
        }
    }
}

@Composable
private fun SubscriptionLinkField(
    link: String,
    status: SubscriptionDialogStatus,
    enabled: Boolean,
    onLinkChange: (String) -> Unit,
    onSubmit: () -> Unit,
): Unit {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    var clipboardFocused by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val fieldError = when (status) {
        SubscriptionDialogStatus.InvalidUrl -> "Введите корректную ссылку"
        SubscriptionDialogStatus.NetworkError -> "Не удалось загрузить подписку. Проверьте соединение и повторите"
        SubscriptionDialogStatus.MalformedData -> "Не удалось прочитать подписку. Проверьте ссылку и формат данных"
        else -> null
    }
    val outline = when {
        status == SubscriptionDialogStatus.InvalidUrl -> SubscriptionError
        focused -> SubscriptionAccent
        else -> SubscriptionOutline
    }

    BasicTextField(
        value = link,
        onValueChange = onLinkChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .border(if (focused) 2.dp else 1.dp, outline, RoundedCornerShape(12.dp))
            .semantics {
                contentDescription = "Ссылка"
                if (fieldError != null) error(fieldError)
            },
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(
            color = if (enabled) SubscriptionText else SubscriptionSecondaryText,
            fontFamily = SweepSans,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        cursorBrush = SolidColor(SubscriptionAccent),
        interactionSource = interactions,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Done,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.heightIn(min = 56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f).padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp)) {
                    if (link.isEmpty()) {
                        Text(
                            text = "https://example.com/sub/...",
                            color = SubscriptionSecondaryText,
                            fontSize = 16.sp,
                            lineHeight = 22.sp,
                        )
                    }
                    innerTextField()
                }
                if (status == SubscriptionDialogStatus.InvalidUrl) {
                    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                        SubscriptionAlertIcon()
                    }
                }
                IconButton(
                    onClick = { clipboard.getText()?.text?.let(onLinkChange) },
                    enabled = enabled,
                    modifier = Modifier
                        .size(48.dp)
                        .onFocusChanged { clipboardFocused = it.isFocused }
                        .border(
                            if (clipboardFocused) 2.dp else 0.dp,
                            if (clipboardFocused) SubscriptionAccent else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        )
                        .semantics { contentDescription = "Вставить ссылку" },
                ) {
                    ClipboardIcon()
                }
            }
        },
    )
}

@Composable
private fun ClipboardIcon(): Unit {
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = SubscriptionAccent,
            topLeft = Offset(size.width * 0.2f, size.height * 0.16f),
            size = Size(size.width * 0.6f, size.height * 0.75f),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = SubscriptionAccent,
            topLeft = Offset(size.width * 0.38f, size.height * 0.09f),
            size = Size(size.width * 0.24f, size.height * 0.15f),
            cornerRadius = CornerRadius(1.dp.toPx()),
            style = Stroke(stroke),
        )
        drawLine(SubscriptionAccent, Offset(size.width * 0.37f, size.height * 0.46f), Offset(size.width * 0.63f, size.height * 0.46f), stroke, StrokeCap.Round)
        drawLine(SubscriptionAccent, Offset(size.width * 0.37f, size.height * 0.62f), Offset(size.width * 0.63f, size.height * 0.62f), stroke, StrokeCap.Round)
    }
}

@Composable
private fun SubscriptionStatusMessage(status: SubscriptionDialogStatus): Unit {
    when (status) {
        SubscriptionDialogStatus.Empty -> SubscriptionHelperText("Ссылка нужна для загрузки серверов")
        SubscriptionDialogStatus.Input -> SubscriptionHelperText("Список серверов загрузится после «Ок»")
        SubscriptionDialogStatus.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = SubscriptionAccent, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            SubscriptionHelperText("Загрузка серверов...")
        }
        SubscriptionDialogStatus.InvalidUrl -> Text(
            text = "Введите корректную ссылку",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = SubscriptionError,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        SubscriptionDialogStatus.NetworkError -> SubscriptionErrorMessage(
            "Не удалось загрузить подписку",
            "Проверьте соединение и повторите",
        )
        SubscriptionDialogStatus.MalformedData -> SubscriptionErrorMessage(
            "Не удалось прочитать подписку",
            "Проверьте ссылку и формат данных",
        )
        SubscriptionDialogStatus.Unavailable -> SubscriptionHelperText(
            "Загрузка подписок пока недоступна",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

@Composable
private fun SubscriptionHelperText(text: String, modifier: Modifier = Modifier): Unit {
    Text(text = text, modifier = modifier, color = SubscriptionSecondaryText, fontSize = 14.sp, lineHeight = 20.sp)
}

@Composable
private fun SubscriptionErrorMessage(title: String, detail: String? = null): Unit {
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.Top,
    ) {
        SubscriptionAlertIcon()
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = SubscriptionError, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
            if (detail != null) SubscriptionHelperText(detail)
        }
    }
}

@Composable
private fun SubscriptionAlertIcon(): Unit {
    Canvas(Modifier.size(24.dp)) {
        val stroke = 2.dp.toPx()
        drawCircle(SubscriptionError, radius = size.minDimension / 2 - stroke, style = Stroke(stroke))
        drawLine(SubscriptionError, Offset(center.x, size.height * 0.25f), Offset(center.x, size.height * 0.56f), stroke, StrokeCap.Round)
        drawCircle(SubscriptionError, radius = stroke / 2, center = Offset(center.x, size.height * 0.72f))
    }
}

@Composable
private fun SubscriptionActions(
    canSubmit: Boolean,
    loading: Boolean,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
): Unit {
    val textMeasurer = rememberTextMeasurer()
    val textStyle = TextStyle(fontFamily = SweepSans, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    val cancelWidth = textMeasurer.measure("Отмена", style = textStyle).size.width
    val okWidth = textMeasurer.measure("Ок", style = textStyle).size.width
    val requiredWidth = with(LocalDensity.current) { maxOf(cancelWidth, okWidth).toDp() * 2 + 72.dp }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        if (maxWidth >= requiredWidth) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubscriptionCancelButton(onDismiss, Modifier.weight(1f))
                SubscriptionSubmitButton(canSubmit, loading, onSubmit, Modifier.weight(1f))
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SubscriptionCancelButton(onDismiss, Modifier.fillMaxWidth())
                SubscriptionSubmitButton(canSubmit, loading, onSubmit, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun SubscriptionCancelButton(onDismiss: () -> Unit, modifier: Modifier = Modifier): Unit {
    var focused by remember { mutableStateOf(false) }
    OutlinedButton(
        onClick = onDismiss,
        modifier = modifier.heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused },
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(if (focused) 2.dp else 1.dp, if (focused) SubscriptionAccent else SubscriptionOutline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = SubscriptionText),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Отмена", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SubscriptionSubmitButton(
    enabled: Boolean,
    loading: Boolean,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
): Unit {
    var focused by remember { mutableStateOf(false) }
    Button(
        onClick = onSubmit,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 48.dp)
            .onFocusChanged { focused = it.isFocused }
            .border(if (focused) 2.dp else 0.dp, if (focused) SubscriptionText else Color.Transparent, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SubscriptionAccent,
            contentColor = SubscriptionAccentText,
            disabledContainerColor = SubscriptionDisabled,
            disabledContentColor = SubscriptionSecondaryText,
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = SubscriptionSecondaryText, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text("Ок", fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold)
    }
}
