package com.modulex.translator

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.modulex.translator.ui.navigation.AppNavHost
import com.modulex.translator.ui.theme.ModuleXTheme

class MainActivity : ComponentActivity() {
    /** Text shared into the app (ACTION_SEND) opens the text translator. */
    private val sharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        sharedText.value = intent.sharedText()
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            ModuleXTheme(settings.theme) {
                AppNavHost(sharedText = sharedText.value, onSharedTextConsumed = { sharedText.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.sharedText()?.let { sharedText.value = it }
    }

    private fun Intent.sharedText(): String? =
        if (action == Intent.ACTION_SEND && type == "text/plain") getStringExtra(Intent.EXTRA_TEXT)?.take(5_000) else null
}
