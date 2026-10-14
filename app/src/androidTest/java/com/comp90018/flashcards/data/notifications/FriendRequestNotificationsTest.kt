package com.comp90018.flashcards.data.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.comp90018.flashcards.data.social.IncomingFriendRequests
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FriendRequestNotificationsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifications = FriendRequestNotifications(context)
    private val uid = "notification-test-account"

    @Before
    fun prepare() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation
                .executeShellCommand(
                    "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
                ).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).readBytes() }
        }
        instrumentation.waitForIdleSync()
        context
            .getSharedPreferences("friend_notifications", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        notifications.clearOtherAccounts(null)
    }

    @After
    fun cleanup() {
        notifications.clearOtherAccounts(null)
    }

    @Test
    fun actionsAreDistinctImmutableAndBoundToTheirRequest() {
        notifications.show(uid, "first-request", "Alice")
        notifications.show(uid, "second-request", "Charlie")
        val first = manager.activeNotifications.single { it.tag == "friend:$uid:first-request" }.notification
        val second = manager.activeNotifications.single { it.tag == "friend:$uid:second-request" }.notification
        assertEquals(listOf("Accept", "Decline"), first.actions.map { it.title.toString() })
        assertNotEquals(first.actions[0].actionIntent, first.actions[1].actionIntent)
        assertNotEquals(first.actions[0].actionIntent, second.actions[0].actionIntent)
        assertTrue(first.contentIntent.isActivity)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue(first.actions.all { it.actionIntent.isImmutable })
        }
    }

    @Test
    fun dismissedRequestIsNotRepostedByRepeatedDelivery() {
        notifications.show(uid, "first-request", "Alice")
        notifications.dismiss(uid, "first-request")
        notifications.show(uid, "first-request", "Alice")
        assertTrue(manager.activeNotifications.none { it.tag == "friend:$uid:first-request" })
    }

    @Test
    fun oldSnapshotCannotCancelNewPushAndFreshSnapshotRemovesResolvedRequest() {
        val beforePush = System.currentTimeMillis() - 60_000
        notifications.show(uid, "first-request", "Alice")
        notifications.sync(uid, IncomingFriendRequests(emptyList(), beforePush))
        assertTrue(manager.activeNotifications.any { it.tag == "friend:$uid:first-request" })
        notifications.sync(uid, IncomingFriendRequests(emptyList(), System.currentTimeMillis() + 1))
        assertFalse(manager.activeNotifications.any { it.tag == "friend:$uid:first-request" })
    }

    @Test
    fun changingAccountClearsOtherAccountsNotifications() {
        notifications.show(uid, "first-request", "Alice")
        notifications.show("another-test-account", "second-request", "Charlie")
        notifications.clearOtherAccounts(uid)
        assertEquals(listOf("friend:$uid:first-request"), manager.activeNotifications.map { it.tag })
    }
}
