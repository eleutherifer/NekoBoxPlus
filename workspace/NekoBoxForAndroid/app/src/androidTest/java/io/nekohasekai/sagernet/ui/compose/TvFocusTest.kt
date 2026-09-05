package io.nekohasekai.sagernet.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TvFocusTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var inputModeManager: InputModeManager

    private fun requestTvFocus(focusRequester: FocusRequester) {
        composeRule.runOnIdle {
            assertTrue(inputModeManager.requestInputMode(InputMode.Keyboard))
            assertTrue(focusRequester.requestFocus())
        }
    }

    @Test
    fun dpadFocusBringsLazyListTargetsIntoView() {
        val first = FocusRequester()
        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            MaterialTheme {
                CompositionLocalProvider(LocalTelevisionUiOverride provides true) {
                    LazyColumn(Modifier.height(120.dp)) {
                        items((0..7).toList()) { index ->
                            Text(
                                text = "Item $index",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .tvFocusTarget()
                                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                    .clickable {}
                                    .testTag("item-$index")
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }
        }

        requestTvFocus(first)
        repeat(6) {
            composeRule.onNodeWithTag("item-$it").performKeyInput {
                pressKey(Key.DirectionDown)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("item-6").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun dpadFocusBringsScrollColumnTargetsIntoView() {
        val first = FocusRequester()
        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            MaterialTheme {
                CompositionLocalProvider(LocalTelevisionUiOverride provides true) {
                    Column(Modifier.height(120.dp).verticalScroll(rememberScrollState())) {
                        repeat(8) { index ->
                            Text(
                                text = "Item $index",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .tvFocusTarget()
                                    .then(if (index == 0) Modifier.focusRequester(first) else Modifier)
                                    .clickable {}
                                    .testTag("scroll-item-$index")
                                    .padding(8.dp),
                            )
                        }
                    }
                }
            }
        }

        requestTvFocus(first)
        repeat(6) {
            composeRule.onNodeWithTag("scroll-item-$it").performKeyInput {
                pressKey(Key.DirectionDown)
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("scroll-item-6").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun passiveCheckboxDoesNotCreateASecondFocusStop() {
        val first = FocusRequester()
        var activated = false
        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            MaterialTheme {
                CompositionLocalProvider(LocalTelevisionUiOverride provides true) {
                    Column {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .tvFocusTarget()
                                .focusRequester(first)
                                .clickable { activated = true }
                                .testTag("compound-row"),
                        ) {
                            Checkbox(
                                checked = true,
                                onCheckedChange = null,
                                modifier = Modifier.size(48.dp).testTag("passive-checkbox"),
                            )
                        }
                        Text(
                            "Disabled",
                            Modifier
                                .tvFocusTarget(enabled = false)
                                .clickable(enabled = false) {}
                                .testTag("disabled-row")
                                .padding(16.dp),
                        )
                        Text(
                            "Next",
                            Modifier
                                .tvFocusTarget()
                                .clickable {}
                                .testTag("next-row")
                                .padding(16.dp),
                        )
                    }
                }
            }
        }

        requestTvFocus(first)
        composeRule.onNodeWithTag("compound-row").assertIsFocused().performKeyInput {
            pressKey(Key.Enter)
        }
        composeRule.runOnIdle { assertTrue(activated) }
        composeRule.onNodeWithTag("compound-row").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        composeRule.onNodeWithTag("next-row").assertIsFocused()
    }
}
