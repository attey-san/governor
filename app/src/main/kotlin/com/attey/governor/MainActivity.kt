package com.attey.governor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.attey.governor.core.TriggerService
import com.attey.governor.ui.GovernorApp
import com.attey.governor.ui.theme.GovernorTheme

class MainActivity : ComponentActivity() {

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // The trigger watcher is a foreground service, and from API 33 a
        // foreground service with no postable notification is a service the user
        // cannot see running. Ask once; the app works either way.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Triggers survive a reboot through BootReceiver, but also need picking up
        // after a force-stop, which kills the service without clearing the rules.
        TriggerService.sync(this)

        setContent {
            GovernorTheme {
                GovernorApp()
            }
        }
    }
}
