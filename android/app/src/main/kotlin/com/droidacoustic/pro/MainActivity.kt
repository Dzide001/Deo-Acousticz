package com.droidacoustic.pro

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.droidacoustic.pro.ui.shell.AppShell
import com.droidacoustic.pro.ui.theme.DroidAcousticTheme

/**
 * Single-activity entry point.
 *
 * Layout strategy: [AppShell] owns the full viewport. The 3D view is the
 * document; the tool rail, inspector and analysis strip are instrumentation
 * arranged around it.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DroidAcousticTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppShell(activity = this)
                }
            }
        }
    }
}
