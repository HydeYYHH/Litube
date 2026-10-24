package com.hhst.youtubelite.ui.design

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class SearchEditingAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun typingAndMiddleEditsPreserveTheCaretThroughParentUpdates() {
        var query by mutableStateOf("first second")
        compose.setContent { AppTheme {
            SearchPanel(query, emptyList(), { query = it }, {}, {}, {})
        } }
        val input = compose.onNodeWithTag("search-input")
        input.performTextInput(" third")
        input.assertTextEquals("first second third")
        input.performTextInputSelection(TextRange(6))
        input.performTextInput("X")
        input.assertTextEquals("first Xsecond third")
        assertEquals(TextRange(7), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.runOnIdle { query = "replacement" }
        input.assertTextEquals("replacement")
        assertEquals(TextRange(11), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }
}
