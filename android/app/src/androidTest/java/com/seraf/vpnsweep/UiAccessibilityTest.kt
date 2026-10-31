package com.seraf.vpnsweep

import com.seraf.vpnsweep.ui.ServerSelectionDialogContent
import com.seraf.vpnsweep.ui.DialogBodyFooter
import com.seraf.vpnsweep.ui.SubscriptionDialogContent
import com.seraf.vpnsweep.ui.SubscriptionDialogStatus
import com.seraf.vpnsweep.ui.SweepTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class UiAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connectingPowerOffersCancellation(): Unit {
        var clicked = false
        compose.setContent {
            SweepTheme {
                VpnSweepScreen(
                    state = VpnState(VpnPhase.CONNECTING, "Подключение…"),
                    selected = true,
                    importing = false,
                    message = "",
                    testing = false,
                    checkResults = emptyList(),
                    onPower = { clicked = true },
                    onImport = {}, onRunChecks = {}, onSubscription = {}, onServers = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Отменить подключение VPN").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }

    @Test
    fun missingConfigurationDisablesPower(): Unit {
        compose.setContent {
            SweepTheme {
                VpnSweepScreen(
                    state = VpnState(),
                    selected = false,
                    importing = false,
                    message = "",
                    testing = false,
                    checkResults = emptyList(),
                    onPower = {}, onImport = {}, onRunChecks = {}, onSubscription = {}, onServers = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Подключить VPN").assertIsNotEnabled()
        compose.onNodeWithText("Конфигурация VPN пока недоступна").assertIsDisplayed()
        compose.onNodeWithText("Выбрать working.json").assertDoesNotExist()
        compose.onNodeWithText("Проверить TCP, UDP и DNS").assertDoesNotExist()
    }

    @Test
    fun permissionFailureIsAnnouncedNearPower(): Unit {
        compose.setContent {
            SweepTheme {
                VpnSweepScreen(
                    state = VpnState(), selected = true, importing = false, message = "",
                    testing = false, checkResults = emptyList(),
                    onPower = {}, onImport = {}, onRunChecks = {}, onSubscription = {}, onServers = {},
                    permissionMessage = "Подключение VPN не разрешено",
                )
            }
        }
        compose.onNodeWithText("Подключение VPN не разрешено")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun screenKeepsContentWithinSafeInsets(): Unit {
        compose.setContent {
            SweepTheme {
                VpnSweepScreen(
                    state = VpnState(), selected = false, importing = false, message = "",
                    testing = false, checkResults = emptyList(),
                    onPower = {}, onImport = {}, onRunChecks = {}, onSubscription = {}, onServers = {},
                    contentWindowInsets = WindowInsets(left = 0.dp, top = 64.dp, right = 0.dp, bottom = 96.dp),
                )
            }
        }
        val titleTop = compose.onNodeWithText("VPN Sweep").getUnclippedBoundsInRoot().top
        assertTrue(titleTop >= 64.dp)
        val lastContent = compose.onNodeWithText("Событий пока нет").performScrollTo()
        val contentBottom = lastContent.getUnclippedBoundsInRoot().bottom
        val rootBottom = compose.onRoot().getUnclippedBoundsInRoot().bottom
        assertTrue(contentBottom <= rootBottom - 96.dp)
    }

    @Test
    fun urlKeepsAccessibleLabelAndUnavailableAction(): Unit {
        compose.setContent { SweepTheme { SubscriptionDialog(onDismiss = {}) } }
        compose.onNodeWithContentDescription("Ссылка").performTextInput("https://example.com/sub")
        compose.onNodeWithContentDescription("Ссылка").assertExists()
        compose.onNodeWithText("Загрузка подписок пока недоступна").assertExists()
        compose.onNodeWithText("Ок").assertIsNotEnabled()
    }

    @Test
    fun pasteIsAvailableForEmptyAndInvalidLinks(): Unit {
        val clipboard = TestClipboard(AnnotatedString("https://example.com/sub"))
        compose.setContent {
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                SweepTheme { SubscriptionDialog(onDismiss = {}) }
            }
        }
        compose.onNodeWithContentDescription("Вставить ссылку").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Ссылка").assertTextContains("https://example.com/sub")
        compose.onNodeWithContentDescription("Ссылка").performTextClearance()
        compose.onNodeWithContentDescription("Ссылка").performTextInput("invalid")
        compose.onNodeWithText("Введите корректную ссылку").assertExists()
        compose.onNodeWithContentDescription("Вставить ссылку").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Ссылка").assertTextContains("https://example.com/sub")
        compose.onNodeWithText("Введите корректную ссылку").assertDoesNotExist()
    }

    @Test
    fun subscriptionFooterFitsAboveKeyboardWithLargeText(): Unit {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                SweepTheme {
                    Box(Modifier.size(width = 272.dp, height = 148.dp)) {
                        SubscriptionDialogContent("", SubscriptionDialogStatus.Empty, {}, {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Отмена").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Ок").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun serverFooterFitsAboveKeyboardWithLargeText(): Unit {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                SweepTheme {
                    Box(Modifier.size(width = 320.dp, height = 196.dp)) {
                        ServerSelectionDialogContent(emptyList(), emptySet(), {}, {})
                    }
                }
            }
        }
        compose.onNodeWithText("Отмена").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Ок").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun subscriptionFooterRemainsVisibleInShortViewport(): Unit {
        subscriptionFooterInShortViewport(fontScale = 1f)
    }

    @Test
    fun subscriptionFooterRemainsVisibleWithLargeTextInShortViewport(): Unit {
        subscriptionFooterInShortViewport(fontScale = 2f)
    }

    private fun subscriptionFooterInShortViewport(fontScale: Float): Unit {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SweepTheme {
                    Box(Modifier.size(width = 320.dp, height = if (fontScale == 2f) 160.dp else 140.dp)) {
                        SubscriptionDialogContent("", SubscriptionDialogStatus.Empty, {}, {}, {})
                    }
                }
            }
        }
        val cancel = compose.onNodeWithText("Отмена").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Ок").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithText("Отмена").assertCountEquals(1)
        compose.onAllNodesWithText("Ок").assertCountEquals(1)
        val initialBottom = cancel.getUnclippedBoundsInRoot().bottom
        compose.onNodeWithText("Ссылка").performScrollTo()
        cancel.assertIsDisplayed()
        assertEquals(initialBottom, cancel.getUnclippedBoundsInRoot().bottom)
    }

    @Test
    fun serverSummaryAndActionsRemainVisibleInShortViewport(): Unit {
        serverFooterInShortViewport(fontScale = 1f, height = 250)
    }

    @Test
    fun serverSummaryAndActionsRemainVisibleWithLargeText(): Unit {
        serverFooterInShortViewport(fontScale = 2f, height = 340)
    }

    private fun serverFooterInShortViewport(fontScale: Float, height: Int): Unit {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                SweepTheme {
                    Box(Modifier.size(width = 320.dp, height = height.dp)) {
                        ServerSelectionDialogContent(emptyList(), emptySet(), {}, {})
                    }
                }
            }
        }
        val summary = compose.onNodeWithText("Серверы не загружены").assertIsDisplayed()
        compose.onAllNodesWithText("Серверы не загружены").assertCountEquals(1)
        compose.onNodeWithText("Отмена").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Ок").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithText("Отмена").assertCountEquals(1)
        compose.onAllNodesWithText("Ок").assertCountEquals(1)
        val initialTop = summary.getUnclippedBoundsInRoot().top
        compose.onNodeWithContentDescription("Поиск сервера").performScrollTo()
        summary.assertIsDisplayed()
        assertEquals(initialTop, summary.getUnclippedBoundsInRoot().top)
    }

    @Test
    fun shortServerDialogCanScrollToSearchWithoutMovingActions(): Unit {
        compose.setContent {
            SweepTheme {
                Box(Modifier.size(width = 320.dp, height = 200.dp)) {
                    ServerSelectionDialogContent(emptyList(), emptySet(), {}, {})
                }
            }
        }
        val cancel = compose.onNodeWithText("Отмена").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val initialBottom = cancel.getUnclippedBoundsInRoot().bottom
        compose.onNodeWithContentDescription("Поиск сервера").performScrollTo().assertIsDisplayed()
        cancel.assertIsDisplayed()
        compose.onNodeWithText("Ок").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        assertEquals(initialBottom, cancel.getUnclippedBoundsInRoot().bottom)
    }

    @Test
    fun subscriptionRetainsFocusInputAndScrollWhenViewportResizesAcrossFooter(): Unit {
        var height by mutableStateOf(190.dp)
        compose.setContent {
            var link by remember { mutableStateOf("") }
            SweepTheme {
                Box(Modifier.size(width = 320.dp, height = height)) {
                    SubscriptionDialogContent(
                        link = link,
                        status = SubscriptionDialogStatus.Input,
                        onLinkChange = { link = it },
                        onDismiss = {},
                        onSubmit = {},
                    )
                }
            }
        }
        val field = compose.onNodeWithContentDescription("Ссылка")
        field.performScrollTo().performClick().performTextInput("https://example.com/sub")
        field.assertIsFocused().assertTextContains("https://example.com/sub")
        assertTrue(verticalScrollOffset() > 0f)
        for (nextHeight in listOf(80.dp, 79.dp, 81.dp, 190.dp)) {
            compose.runOnIdle { height = nextHeight }
            field.assertIsFocused().assertTextContains("https://example.com/sub")
            assertTrue(verticalScrollOffset() > 0f)
        }
    }

    @Test
    fun bodyAndActionsAreReachableAtFooterHeightAndAdjacentHeights(): Unit {
        var height by mutableStateOf(49.dp)
        compose.setContent {
            Box(Modifier.size(width = 320.dp, height = height)) {
                DialogBodyFooter(
                    body = {
                        Spacer(Modifier.height(140.dp))
                        BasicTextField(
                            value = "Тело",
                            onValueChange = {},
                            modifier = Modifier.size(width = 100.dp, height = 48.dp).testTag("dialog-body"),
                        )
                    },
                    footer = {
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("dialog-action"))
                    },
                )
            }
        }
        val density = compose.density
        val footerHeightPx = with(density) { 48.dp.roundToPx() }
        val bodyHeightPx = with(density) { maxOf(56.dp.toPx(), 30.sp.toPx()).roundToInt() }
        val stepPx = with(density) { maxOf(1, 1.dp.roundToPx()) }
        val fixedBoundaryPx = footerHeightPx + bodyHeightPx
        val heightsPx = listOf(
            footerHeightPx - stepPx, footerHeightPx, footerHeightPx + stepPx,
            fixedBoundaryPx - stepPx, fixedBoundaryPx, fixedBoundaryPx + stepPx,
            fixedBoundaryPx, fixedBoundaryPx - stepPx, footerHeightPx,
        )
        for (nextHeightPx in heightsPx) {
            compose.runOnIdle { height = with(density) { nextHeightPx.toDp() } }
            val action = compose.onNodeWithTag("dialog-action")
            val fixedActionTop = if (nextHeightPx >= fixedBoundaryPx) {
                action.assertIsDisplayed().getUnclippedBoundsInRoot().top
            } else null
            compose.onNodeWithTag("dialog-body").performScrollTo().assertIsDisplayed()
            if (fixedActionTop == null) action.performScrollTo()
            action.assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            if (fixedActionTop != null) assertEquals(fixedActionTop, action.getUnclippedBoundsInRoot().top)
        }
    }

    private fun verticalScrollOffset(): Float = compose.onNode(
        hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        useUnmergedTree = true,
    ).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    @Test
    fun parameterGroupAnnouncesExpansion(): Unit {
        compose.setContent {
            SweepTheme {
                VpnSweepScreen(
                    state = VpnState(),
                    selected = false,
                    importing = false,
                    message = "",
                    testing = false,
                    checkResults = emptyList(),
                    onPower = {}, onImport = {}, onRunChecks = {}, onSubscription = {}, onServers = {},
                )
            }
        }
        compose.onNodeWithText("Параметры").performScrollTo().performClick()
        compose.onNodeWithText("Fragment").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Свёрнуто"))
            .performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Развёрнуто"))
    }

    @Test
    fun searchLabelPersistsAndSelectAllPreservesHiddenSelection(): Unit {
        val servers = listOf(
            ServerChoice("de", "Германия", "VLESS", true),
            ServerChoice("se", "Швеция", "Trojan", true),
            ServerChoice("bad", "Недоступный", "Пример", false),
        )
        var confirmed = emptySet<String>()
        compose.setContent {
            SweepTheme {
                ServerSelectionDialogContent(servers, setOf("de", "se"), {}, { confirmed = it })
            }
        }
        compose.onNodeWithContentDescription("Поиск сервера").performTextInput("Германия")
        compose.onNodeWithContentDescription("Поиск сервера").assertExists()
        compose.onNodeWithText("Выбрать все").performClick()
        compose.onNodeWithText("Ок").performClick()
        compose.runOnIdle { assertEquals(setOf("se"), confirmed) }
    }

    @Test
    fun serverListScrollKeepsSearchAndFooterVisible(): Unit {
        val servers = (1..12).map { ServerChoice("$it", "Сервер $it", "Пример", true) }
        compose.setContent {
            SweepTheme { ServerSelectionDialogContent(servers, setOf("1"), {}, {}) }
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Сервер 12"))
        compose.onNodeWithContentDescription("Поиск сервера").assertIsDisplayed()
        compose.onNodeWithText("Отмена").assertIsDisplayed()
        compose.onNodeWithText("Ок").assertIsDisplayed()
    }
}

private class TestClipboard(private var value: AnnotatedString?) : ClipboardManager {
    override fun getText(): AnnotatedString? = value

    override fun setText(annotatedString: AnnotatedString): Unit {
        value = annotatedString
    }
}
