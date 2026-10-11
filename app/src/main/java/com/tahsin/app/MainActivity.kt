package com.tahsin.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.tahsin.app.navigation.TahsinNavHost
import com.tahsin.app.settings.TahsinSettings
import com.tahsin.app.ui.theme.TahsinTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val container = (LocalContext.current.applicationContext as TahsinApplication).container
            val settings by container.settingsRepository.settings
                .collectAsState(initial = TahsinSettings())

            TahsinTheme(themeChoice = settings.theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    TahsinNavHost()
                }
            }
        }
    }
}
