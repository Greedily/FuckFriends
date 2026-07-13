package org.hornecker.fuckfriends

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import org.hornecker.fuckfriends.ui.theme.FuckFriendsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            FuckFriendsTheme {
                AppRoot()
            }
        }
    }

    @Composable
    private fun AppRoot() {
        var displayName by remember {
            mutableStateOf(DeviceIdentity.getDisplayName(this@MainActivity))
        }

        if (displayName == null) {
            NameSetupScreen(onNameSet = { name ->
                displayName = name
                TimeRequestRepository(this@MainActivity).registerDevice(name)
                checkPermissionsAndStartService()
            })
        } else {
            LaunchedEffect(Unit) {
                TimeRequestRepository(this@MainActivity).registerDevice(displayName!!)
                checkPermissionsAndStartService()
            }
            FriendRequestsScreen()
        }
    }

    private fun checkPermissionsAndStartService() {
        // Optimierter Check via AppOpsManager verhindert das Einfrieren des Main-Threads
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        }
        val hasUsagePermission = mode == android.app.AppOpsManager.MODE_ALLOWED

        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }

        when {
            !hasUsagePermission -> {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
            !hasOverlayPermission -> {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            }
            else -> {
                val serviceIntent = Intent(this, ScreenTimeService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
        }
    }
}