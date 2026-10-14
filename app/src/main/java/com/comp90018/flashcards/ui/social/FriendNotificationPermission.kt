package com.comp90018.flashcards.ui.social

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

@Composable
fun FriendNotificationPermission(onPermissionGrant: () -> Unit) {
    val context = LocalContext.current
    val permissionGrant by rememberUpdatedState(onPermissionGrant)
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) permissionGrant()
        }
    LaunchedEffect(Unit) {
        val preferences = context.getSharedPreferences("notification_permission", Context.MODE_PRIVATE)
        when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> permissionGrant()
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED -> permissionGrant()
            !preferences.getBoolean("asked", false) -> {
                preferences.edit().putBoolean("asked", true).apply()
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
