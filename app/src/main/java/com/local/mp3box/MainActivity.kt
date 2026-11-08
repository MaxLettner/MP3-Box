package com.local.mp3box

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.local.mp3box.ui.AppRoot
import com.local.mp3box.ui.theme.Mp3BoxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            Mp3BoxTheme {
                AppRoot()
            }
        }
    }
}
