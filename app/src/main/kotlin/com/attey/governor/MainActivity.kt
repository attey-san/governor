package com.attey.governor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.attey.governor.ui.GovernorApp
import com.attey.governor.ui.theme.GovernorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GovernorTheme {
                GovernorApp()
            }
        }
    }
}
