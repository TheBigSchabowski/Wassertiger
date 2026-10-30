package com.example.intervalltimer

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * Lights up the screen over the lock screen for a moment at each step change and then
 * gets out of the way. The lock screen stays locked; it times out on its own afterwards.
 */
class FlashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        val finished = intent.getBooleanExtra(EXTRA_FINISHED, false)
        setContent {
            LaunchedEffect(Unit) {
                delay(2500)
                finish()
            }
            FlashContent(title, text, finished, Modifier.clickable { finish() })
        }
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FINISHED = "finished"
    }
}
