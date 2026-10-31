package com.seraf.vpnsweep

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
