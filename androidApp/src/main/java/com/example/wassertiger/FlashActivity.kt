package com.example.wassertiger

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * Lives over the lock screen while a session runs. At each step change it lights the screen up in
 * amber for a moment ([EXTRA_TITLE] …); otherwise it shows the big countdown, so the power button
 * brings up the remaining time instead of the lock screen. It stays until the phone is unlocked
 * ("Entsperren" or the usual way) or the session ends; the lock screen stays locked meanwhile.
 */
class FlashActivity : ComponentActivity() {

    private class Flash(val title: String, val text: String, val finished: Boolean, val millis: Long, val color: Int)

    private var flash by mutableStateOf<Flash?>(null)
    /** The phone was unlocked during a flash: leave once the flash is over. */
    private var leaveAfterFlash = false

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (flash == null) finish() else leaveAfterFlash = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alive = true
        ContextCompat.registerReceiver(this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_NOT_EXPORTED)
        show(intent)
        // A flash has painted the window in its colour (see show); the countdown starts dark.
        if (flash == null) window.setBackgroundDrawable(ColorDrawable(CLOCK_BACKGROUND))
        setContent {
            WassertigerTheme {
                val run by TimerService.engine.state.collectAsState()
                val f = flash
                val r = run
                when {
                    f != null -> {
                        LaunchedEffect(f) {
                            delay(f.millis)
                            endFlash()
                        }
                        FlashContent(f.title, f.text, f.finished, f.color, Modifier.clickable { endFlash() })
                    }
                    r == null -> LaunchedEffect(Unit) { finish() }
                    // The "Fertig!" flash is on its way; leave only if it does not come.
                    r.finished -> LaunchedEffect(Unit) {
                        delay(2000)
                        finish()
                    }
                    else -> Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        RunScreen(r, TimerService.engine, hint = "") {
                            TextButton(onClick = ::unlock, modifier = Modifier.padding(top = 16.dp)) { Text("Entsperren") }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        show(intent)
    }

    /** A flash for a step change, or (without [EXTRA_TITLE]) just the countdown. */
    private fun show(intent: Intent) {
        getSystemService(NotificationManager::class.java).cancel(TimerService.ID_CLOCK)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        flash = Flash(
            title,
            intent.getStringExtra(EXTRA_TEXT).orEmpty(),
            intent.getBooleanExtra(EXTRA_FINISHED, false),
            intent.getLongExtra(EXTRA_MILLIS, 2500),
            intent.getIntExtra(EXTRA_COLOR, AMBER_RGB),
        ).also {
            // The window shows before Compose draws: paint it in the flash colour, not the theme's amber.
            window.setBackgroundDrawable(ColorDrawable(0xFF000000.toInt() or (if (it.finished) DONE_GREEN_RGB else it.color)))
        }
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
    }

    private fun endFlash() {
        val f = flash ?: return
        flash = null
        // The countdown follows at the usual brightness, so it does not dazzle at night.
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        val run = TimerService.engine.state.value
        if (f.finished || leaveAfterFlash || run == null || run.finished) finish()
    }

    private fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    /** Asks for PIN / fingerprint; once unlocked, [unlocked] closes the countdown. */
    private fun unlock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) {
            finish()
            return
        }
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish()
        })
    }

    override fun onDestroy() {
        alive = false
        unregisterReceiver(unlocked)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FINISHED = "finished"
        const val EXTRA_MILLIS = "millis"
        const val EXTRA_COLOR = "color"
        private const val CLOCK_BACKGROUND = 0xFF121417.toInt()

        /** Whether the activity is up (possibly behind a dark screen); the power button then shows it anyway. */
        @Volatile var alive = false
            private set
    }
}
