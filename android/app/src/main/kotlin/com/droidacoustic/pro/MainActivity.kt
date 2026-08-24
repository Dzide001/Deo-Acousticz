package com.droidacoustic.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.droidacoustic.pro.ui.MainScreen
import com.droidacoustic.pro.ui.theme.DroidAcousticTheme

/**
 * Single-activity entry point.
 *
 * Layout strategy: the [MainScreen] composable owns the full viewport and sets
 * up the two-pane (properties rail + 3D view) layout based on [WindowSizeClass].
 * Individual phases add composable content behind the same scaffold.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DroidAcousticTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(activity = this)
                }
            }
        }
    }
}
