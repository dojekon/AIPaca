package com.aipaca.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aipaca.android.ui.AppRoot
import com.aipaca.android.ui.applyLanguage
import com.aipaca.android.ui.theme.AiPacaTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as AiPacaApp).container

        lifecycleScope.launch {
            val language = container.settingsRepository.settings.first().language
            applyLanguage(language)
        }

        setContent {
            AiPacaTheme {
                AppRoot(container = container)
            }
        }
    }
}
