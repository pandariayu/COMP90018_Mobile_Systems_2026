package com.comp90018.flashcards.ui.account

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.comp90018.flashcards.data.social.SocialProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccountUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun displayNameCanChangeWithoutChangingUsername() {
        var saved: String? = null
        compose.setContent {
            MaterialTheme { ProfileDetails(SocialProfile("id", "alice", "Alice"), true) { saved = it } }
        }
        compose.onNodeWithText("@alice").assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit display name").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(" ")
        compose.onNodeWithContentDescription("Save display name").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement(" New name ")
        compose.onNodeWithContentDescription("Save display name").performClick()
        assertEquals("New name", saved)
    }

    @Test
    fun passwordDeletionRequiresInputAndCancellationDoesNotDelete() {
        var deleted = 0
        var cancelled = 0
        compose.setContent { MaterialTheme { DeleteAccountConfirmation(true, true, { deleted++ }, { cancelled++ }) } }
        compose.onNodeWithText("Delete permanently").assertIsNotEnabled()
        compose.onNodeWithText("Password").performTextReplacement("secret")
        compose.onNodeWithText("Delete permanently").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, deleted)
        assertEquals(1, cancelled)
    }

    @Test
    fun googleDeletionRequestsConfirmationWithoutPassword() {
        var confirmed: String? = null
        compose.setContent { MaterialTheme { DeleteAccountConfirmation(false, true, { confirmed = it }, {}) } }
        compose.onNodeWithText("Confirm your Google account in the next step.").assertIsDisplayed()
        compose.onNodeWithText("Delete permanently").performClick()
        assertEquals("", confirmed)
    }
}
