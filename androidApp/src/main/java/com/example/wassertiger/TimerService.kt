package com.example.wassertiger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps [engine] running in the foreground so it keeps counting with the screen off.
 * It never requests audio focus, so music apps keep playing; step changes are signalled by
 * vibration and by briefly turning the screen on. Only music sessions sound: their drone and
 * drums come from [music].
 */
class TimerService : Service() {

    private val scope = MainScope()
    private var cpuLock: PowerManager.WakeLock? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine.listener = ::onTimerEvent
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val session = intent.getStringExtra(EXTRA_SESSION_ID)?.let { sessionStore(this).get(it) }
                if (session == null || session.steps.isEmpty()) stopSelf() else start(session)
            }
            ACTION_TOGGLE_PAUSE -> engine.togglePause()
            ACTION_SKIP -> engine.skip()
            ACTION_STOP -> engine.stop()
        }
        // Actions from a stale notification after the session ended: nothing left to run.
        if (engine.state.value == null) shutdown()
        return START_NOT_STICKY
    }

    private fun start(session: Session) {
        createChannels()
        engine.start(session)
        val notification = ongoingNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_ONGOING, notification)
        }
        cpuLock?.takeIf { it.isHeld }?.release()
        cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Wassertiger:run").apply {
            setReferenceCounted(false)
            acquire(CPU_LOCK_MS)
        }
    }

    private fun onTimerEvent(event: TimerEvent, state: RunState) {
        music.follow(state.takeIf { event != TimerEvent.Stopped })
        when (event) {
            TimerEvent.Started -> vibrate(PATTERN_START)
            TimerEvent.Step -> {
                cpuLock?.acquire(CPU_LOCK_MS) // endless sessions may run past the initial timeout
                vibrate(PATTERN_STEP)
                wakeScreen(state)
                notifications.notify(ID_ONGOING, ongoingNotification())
            }
            TimerEvent.PauseChanged -> notifications.notify(ID_ONGOING, ongoingNotification())
            TimerEvent.Finished -> {
                vibrate(PATTERN_FINISH)
                wakeScreen(state)
                scope.launch {
                    delay(3000) // let the final vibration and flash complete before releasing locks
                    shutdown()
                }
            }
            TimerEvent.Stopped -> shutdown()
        }
    }

    private fun shutdown() {
        notifications.cancel(ID_FLASH)
        stopForeground(STOP_FOREGROUND_REMOVE)
        cpuLock?.takeIf { it.isHeld }?.release()
        stopSelf()
    }

    override fun onDestroy() {
        music.stop()
        cpuLock?.takeIf { it.isHeld }?.release()
        engine.listener = { _, _ -> }
        scope.cancel()
        super.onDestroy()
    }

    // --- signalling -------------------------------------------------------

    private fun vibrate(pattern: LongArray) {
        val vibrator = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        val effect = VibrationEffect.createWaveform(pattern, -1)
        // Alarm usage so the buzz still comes through with touch vibration turned off.
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(
                effect,
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
            )
        }
    }

    /**
     * Turns the screen on for a moment. The full-screen intent launches [FlashActivity]
     * over the lock screen when the display is off; when it is on, the same notification
     * just appears as a silent heads-up banner.
     */
    private fun wakeScreen(state: RunState) {
        val (title, text) = state.flashText()
        val flashIntent = Intent(this, FlashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(FlashActivity.EXTRA_TITLE, title)
            .putExtra(FlashActivity.EXTRA_TEXT, text)
            .putExtra(FlashActivity.EXTRA_FINISHED, state.finished)
        val pending = PendingIntent.getActivity(
            this, 1, flashIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_FLASH)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setContentIntent(mainPendingIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(4000)
            .build()
        notifications.cancel(ID_FLASH) // re-posting is what fires the full-screen intent again
        notifications.notify(ID_FLASH, notification)

        // Fallback for devices where the full-screen intent is not allowed.
        if (!power.isInteractive) {
            @Suppress("DEPRECATION")
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Wassertiger:flash",
            ).acquire(2500)
        }
    }

    // --- notifications ----------------------------------------------------

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Laufende Session", NotificationManager.IMPORTANCE_LOW)
        )
        // High importance is required for full-screen intents; sound and vibration stay off
        // so the channel never interrupts music (the service vibrates itself).
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_FLASH, "Intervallwechsel", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
    }

    private fun ongoingNotification(): Notification {
        val s = engine.state.value
        val builder = Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainPendingIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        val step = s?.step
        if (s != null && step != null) {
            val label = listOf(step.name, "Schritt ${s.index + 1} / ${s.session.steps.size}", s.roundLabel)
                .filter { it.isNotBlank() }.joinToString(" · ")
            builder.setContentTitle(if (s.paused) "Pausiert – ${s.session.name}" else s.session.name)
                .setContentText(label)
            if (!s.paused) {
                builder.setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setWhen(System.currentTimeMillis() + s.remainingMs())
                    .setShowWhen(true)
            } else {
                builder.setShowWhen(false)
            }
        }
        builder.addAction(
            Notification.Action.Builder(null, if (s?.paused == true) "Weiter" else "Pause", servicePendingIntent(ACTION_TOGGLE_PAUSE)).build()
        )
        builder.addAction(Notification.Action.Builder(null, "Überspringen", servicePendingIntent(ACTION_SKIP)).build())
        builder.addAction(Notification.Action.Builder(null, "Stopp", servicePendingIntent(ACTION_STOP)).build())
        return builder.build()
    }

    private fun mainPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun servicePendingIntent(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(), Intent(this, TimerService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_TOGGLE_PAUSE = "pause"
        private const val ACTION_SKIP = "skip"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_SESSION_ID = "session"
        private const val CHANNEL_ONGOING = "ongoing"
        private const val CHANNEL_FLASH = "flash"
        private const val ID_ONGOING = 1
        private const val ID_FLASH = 2
        private const val CPU_LOCK_MS = 4 * 60 * 60 * 1000L

        private val PATTERN_START = longArrayOf(0, 200)
        private val PATTERN_STEP = longArrayOf(0, 350, 150, 350)
        private val PATTERN_FINISH = longArrayOf(0, 600, 200, 600, 200, 600)

        /** Process-wide, so the UI and the service see the same run. */
        val engine = TimerEngine(MainScope())

        private val music by lazy { MusicPlayer(LiveStream(::TrackSink)) { DrumSamples.load(::bundledResource) } }

        fun start(context: Context, session: Session) = ContextCompat.startForegroundService(
            context,
            Intent(context, TimerService::class.java).setAction(ACTION_START).putExtra(EXTRA_SESSION_ID, session.id),
        )
    }
}
