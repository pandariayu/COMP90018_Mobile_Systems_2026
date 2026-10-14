package com.comp90018.flashcards.data.notifications

import android.app.NotificationManager
import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.comp90018.flashcards.data.auth.AuthRepository
import com.comp90018.flashcards.data.social.SocialApiException
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

@HiltWorker
class NotificationRegistrationWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted parameters: WorkerParameters,
        private val auth: AuthRepository,
        private val devices: NotificationDeviceRegistration,
    ) : CoroutineWorker(context, parameters) {
        @Suppress("TooGenericExceptionCaught")
        override suspend fun doWork(): Result {
            val uid = auth.currentUid
            if (uid == null || FirebaseApp.getApps(applicationContext).isEmpty()) return Result.success()
            return if (!applicationContext
                    .getSystemService(
                        NotificationManager::class.java,
                    ).areNotificationsEnabled()
            ) {
                Result.success()
            } else {
                try {
                    val token = FirebaseMessaging.getInstance().token.await()
                    if (auth.currentUid == uid) devices.register(uid, token)
                    Result.success()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: SocialApiException) {
                    if (error.code == "account_deleting" || error.status == 401 || error.status == 403) {
                        Result.failure()
                    } else {
                        Result.retry()
                    }
                } catch (_: IllegalStateException) {
                    Result.success()
                } catch (_: Exception) {
                    Result.retry()
                }
            }
        }
    }
