package com.seraf.vpnsweep

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
}
