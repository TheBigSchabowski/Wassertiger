package com.example.wassertiger

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible: started by MetalRat's "Üben". While visible it may start the foreground service, so
 * the timer runs in the background; then it opens the video and gets out of the way.
 */
class PracticeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val seconds = intent.getIntExtra(Practice.EXTRA_SECONDS, 300)
        TimerService.startPractice(this, seconds, intent.getStringExtra(Practice.EXTRA_TITLE))
        Toast.makeText(applicationContext, "Wassertiger: ${formatDuration(seconds)} üben", Toast.LENGTH_SHORT).show()
        intent.getStringExtra(Practice.EXTRA_URL)?.let { url ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                // No app for the link: the timer still runs.
            }
        }
        finish()
    }
}
