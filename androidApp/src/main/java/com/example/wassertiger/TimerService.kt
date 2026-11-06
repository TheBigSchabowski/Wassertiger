package com.example.wassertiger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * vibration and by briefly turning the screen on, and by a [chimes] tone if the session wants one.
 * Music sessions also sound: their drone and drums come from [music].
 *
 * While a session runs, turning the screen on over the lock screen shows [FlashActivity]'s big
 * countdown instead (see [screenOn]).
 *
 * Several sessions can run at once; each has its own notification with its own buttons.
 */
class TimerService : Service() {

    private val scope = MainScope()
    private var cpuLock: PowerManager.WakeLock? = null
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val power by lazy { getSystemService(PowerManager::class.java) }
    private val keyguard by lazy { getSystemService(KeyguardManager::class.java) }

    /** Power button pressed during a session while locked: put the countdown over the lock screen. */
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (engine.active.isNotEmpty() && keyguard.isKeyguardLocked && !FlashActivity.alive) showClock()
        }
    }
    private var screenOnRegistered = false
    /** Notifications posted for runs beyond the first, under [ID_EXTRA] + position. */
    private var extraPosted = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        engine.listener = ::onTimerEvent
        engine.keepStopped = keepForToggl(settingsStore(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val session = intent.getStringExtra(EXTRA_SESSION_ID)?.let { sessionStore(this).get(it) }
                if (session == null || session.steps.isEmpty()) stopSelf() else start(session)
            }
            ACTION_PRACTICE -> start(
                Practice.session(intent.getIntExtra(Practice.EXTRA_SECONDS, 300), intent.getStringExtra(Practice.EXTRA_TITLE)),
            )
            ACTION_TOGGLE_PAUSE -> runOf(intent)?.let(engine::togglePause)
            ACTION_SKIP -> runOf(intent)?.let(engine::skip)
            ACTION_STOP -> runOf(intent)?.let(engine::stop)
        }
        // Actions from a stale notification after the session ended: nothing left to run.
        if (engine.active.isEmpty() && !finishing) shutdown()
        return START_NOT_STICKY
    }

    /** The run a notification or widget button is for; without one, the oldest still going. */
    private fun runOf(intent: Intent): Long? =
        intent.getLongExtra(EXTRA_RUN_ID, -1).takeIf { it >= 0 } ?: engine.active.firstOrNull()?.id

    /** A run just finished and its last flash is still going; [shutdown] follows. */
    private var finishing = false

    private fun start(session: Session) {
        createChannels()
        finishing = false
        val id = engine.start(session)
        val notification = ongoingNotification(engine.run(id) ?: return)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID_ONGOING, notification)
        }
        updateNotifications()
        cpuLock?.takeIf { it.isHeld }?.release()
        cpuLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Wassertiger:run").apply {
            setReferenceCounted(false)
            acquire(CPU_LOCK_MS)
        }
        if (!screenOnRegistered) {
            ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
            screenOnRegistered = true
        }
    }

    private fun onTimerEvent(event: TimerEvent, state: RunState) {
        togglTracker(this).onEvent(event, state)
        // One drone at a time: the oldest music session still going plays.
        music.follow(engine.active.firstOrNull { it.session.music })
        when (event) {
            TimerEvent.Started -> vibrate(state, Buzz.START)
            TimerEvent.Step -> {
                cpuLock?.acquire(CPU_LOCK_MS) // endless sessions may run past the initial timeout
                vibrate(state, Buzz.STEP)
                chimes.signal(state.session, finish = false)
                wakeScreen(state)
                updateNotifications()
            }
            TimerEvent.PauseChanged -> updateNotifications()
            TimerEvent.Finished -> {
                val buzzMs = vibrate(state, Buzz.FINISH)
                chimes.signal(state.session, finish = true)
                val chimeMs = if (state.session.tone.sounds(finish = true)) chimeMillis(state.session.chime, finish = true) else 0
                wakeScreen(state)
                if (engine.active.isEmpty()) {
                    finishing = true
                    scope.launch {
                        // Let the final vibration, tone and flash complete before releasing locks.
                        delay(maxOf(state.session.flashMillis + 500, buzzMs + 500, chimeMs + 500))
                        // Unless another session was started meanwhile.
                        if (finishing && engine.active.isEmpty()) shutdown()
                    }
                } else {
                    updateNotifications()
                }
            }
            TimerEvent.Stopped -> if (engine.active.isEmpty()) shutdown() else updateNotifications()
        }
        Widgets.update(this)
    }

    private fun shutdown() {
        finishing = false
        notifications.cancel(ID_FLASH)
        notifications.cancel(ID_CLOCK)
        for (i in 1..extraPosted) notifications.cancel(ID_EXTRA + i)
        extraPosted = 0
        unregisterScreenOn()
        stopForeground(STOP_FOREGROUND_REMOVE)
        cpuLock?.takeIf { it.isHeld }?.release()
        stopSelf()
    }

    private fun unregisterScreenOn() {
        if (screenOnRegistered) unregisterReceiver(screenOn)
        screenOnRegistered = false
    }

    override fun onDestroy() {
        unregisterScreenOn()
        music.stop()
        cpuLock?.takeIf { it.isHeld }?.release()
        engine.listener = { _, _ -> }
        scope.cancel()
        super.onDestroy()
    }

    // --- signalling -------------------------------------------------------

    /** Buzzes as long as the session asks for; returns how long that takes in ms. */
    private fun vibrate(state: RunState, buzz: Buzz): Long {
        val pattern = vibrationPattern(state.session.vibration, buzz)
        if (pattern.isEmpty()) return 0
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
        return pattern.sum()
    }

    /**
     * Turns the screen on for a moment. The full-screen intent launches [FlashActivity]
     * over the lock screen when the display is off; when it is on, the same notification
     * just appears as a silent heads-up banner.
     */
    private fun wakeScreen(state: RunState) {
        // The countdown over the lock screen is showing: it flashes by itself (see FlashActivity).
        if (FlashActivity.visible && power.isInteractive) return
        postFlash(state)
        // Fallback for devices where the full-screen intent is not allowed.
        if (!power.isInteractive) {
            @Suppress("DEPRECATION")
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "Wassertiger:flash",
            ).acquire(state.session.flashMillis)
            // Now and then the system lets a full-screen intent pass without lighting up; one more try.
            scope.launch {
                delay(FLASH_RETRY_MS)
                val now = engine.run(state.id)
                if (!power.isInteractive && now != null && now.index == state.index && now.round == state.round) postFlash(now)
            }
        }
    }

    private fun postFlash(state: RunState) {
        val (title, text) = state.flashText()
        val flashIntent = Intent(this, FlashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(FlashActivity.EXTRA_RUN_ID, state.id)
            .putExtra(FlashActivity.EXTRA_TITLE, title)
            .putExtra(FlashActivity.EXTRA_TEXT, text)
            .putExtra(FlashActivity.EXTRA_FINISHED, state.finished)
            .putExtra(FlashActivity.EXTRA_MILLIS, state.session.flashMillis)
            .putExtra(FlashActivity.EXTRA_COLOR, state.session.flashColor)
        val pending = PendingIntent.getActivity(
            this, 1, flashIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_FLASH)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(if (state.finished) title else state.session.name)
            .setContentText(if (state.finished) text else listOf(title, text).filter { it.isNotBlank() }.joinToString(" · "))
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setContentIntent(mainPendingIntent())
            .setAutoCancel(true)
            // Never outlive the interval, or its countdown runs past zero.
            .setTimeoutAfter(if (state.finished) state.session.flashMillis + 1500 else minOf(state.session.flashMillis + 1500, state.remainingMs()))
        // The banner (screen on) keeps counting the new interval down instead of freezing on its length.
        if (!state.finished) builder.countDown(state)
        notifications.cancel(ID_FLASH) // re-posting is what fires the full-screen intent again
        notifications.notify(ID_FLASH, builder.build())
    }

    private fun Notification.Builder.countDown(run: RunState): Notification.Builder =
        setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(System.currentTimeMillis() + run.remainingMs())
            .setShowWhen(true)

    /**
     * Shows the countdown over the lock screen that just lit up. A full-screen intent is the one way
     * a background service may open an activity; with the lock screen showing, the system launches it
     * right away. [FlashActivity] withdraws the notification as soon as it is up.
     */
    private fun showClock() {
        val pending = PendingIntent.getActivity(
            this, 2, Intent(this, FlashActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_FLASH)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setContentTitle(engine.active.firstOrNull()?.session?.name ?: "Wassertiger")
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setContentIntent(mainPendingIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(3000)
            .build()
        notifications.notify(ID_CLOCK, notification)
    }

    // --- notifications ----------------------------------------------------

    private fun createChannels() {
        // Default importance, silent: low-importance ("silent") notifications are hidden on many lock
        // screens, and this one carries the countdown. Importance cannot be raised on an existing
        // channel, hence the new id; the old low one goes.
        notifications.deleteNotificationChannel(CHANNEL_ONGOING_OLD)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Laufende Session", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
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

    /** One notification per running session: the first is the service's own, the others follow it. */
    private fun updateNotifications() {
        val active = engine.active
        active.forEachIndexed { i, run -> notifications.notify(if (i == 0) ID_ONGOING else ID_EXTRA + i, ongoingNotification(run)) }
        for (i in maxOf(active.size, 1)..extraPosted) notifications.cancel(ID_EXTRA + i)
        extraPosted = maxOf(active.size - 1, 0)
    }

    private fun ongoingNotification(s: RunState): Notification {
        val builder = Notification.Builder(this, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_timer)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(mainPendingIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        val step = s.step
        if (step != null) {
            val label = listOf(step.name, "Schritt ${s.index + 1} / ${s.session.steps.size}", s.roundLabel)
                .filter { it.isNotBlank() }.joinToString(" · ")
            builder.setContentTitle(if (s.paused) "Pausiert – ${s.session.name}" else s.session.name)
                .setContentText(label)
            if (!s.paused) {
                builder.countDown(s)
            } else {
                builder.setShowWhen(false)
            }
        }
        builder.addAction(
            Notification.Action.Builder(null, if (s.paused) "Weiter" else "Pause", actionPendingIntent(this, ACTION_TOGGLE_PAUSE, s.id)).build()
        )
        builder.addAction(Notification.Action.Builder(null, "Überspringen", actionPendingIntent(this, ACTION_SKIP, s.id)).build())
        builder.addAction(Notification.Action.Builder(null, "Stopp", actionPendingIntent(this, ACTION_STOP, s.id)).build())
        return builder.build()
    }

    private fun mainPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val ACTION_START = "start"
        private const val ACTION_PRACTICE = "practice"
        const val ACTION_TOGGLE_PAUSE = "pause"
        const val ACTION_SKIP = "skip"
        const val ACTION_STOP = "stop"
        const val EXTRA_SESSION_ID = "session"
        const val EXTRA_RUN_ID = "run"
        private const val CHANNEL_ONGOING = "session"
        private const val CHANNEL_ONGOING_OLD = "ongoing"
        private const val CHANNEL_FLASH = "flash"
        private const val ID_ONGOING = 1
        private const val ID_FLASH = 2
        /** The notification that opens the lock-screen countdown; [FlashActivity] cancels it once shown. */
        const val ID_CLOCK = 3
        /** Base id of the notifications of the second, third … parallel run. */
        private const val ID_EXTRA = 10
        private const val FLASH_RETRY_MS = 1200L
        private const val CPU_LOCK_MS = 4 * 60 * 60 * 1000L

        /** Process-wide, so the UI and the service see the same run. */
        val engine = TimerEngine(MainScope())

        private val music by lazy { MusicPlayer(LiveStream(::TrackSink)) { DrumSamples.load(::bundledResource) } }

        /** Also plays the editor's previews. */
        val chimes by lazy { ChimePlayer(::TrackSink) }

        fun start(context: Context, session: Session) =
            ContextCompat.startForegroundService(context, startIntent(context).putExtra(EXTRA_SESSION_ID, session.id))

        /** A one-interval practice timer that is not stored as a session (see [Practice]). */
        fun startPractice(context: Context, seconds: Int, title: String?) = ContextCompat.startForegroundService(
            context,
            Intent(context, TimerService::class.java).setAction(ACTION_PRACTICE)
                .putExtra(Practice.EXTRA_SECONDS, seconds)
                .putExtra(Practice.EXTRA_TITLE, title),
        )

        /** Starts the session named by [EXTRA_SESSION_ID], which the caller (or a fill-in intent) adds. */
        fun startIntent(context: Context): Intent = Intent(context, TimerService::class.java).setAction(ACTION_START)

        /**
         * Starts the session [sessionId] from a widget tap. A tap on a widget may start a foreground
         * service from the background, so this works with the app closed.
         */
        fun startPendingIntent(context: Context, sessionId: String): PendingIntent = PendingIntent.getForegroundService(
            context, sessionId.hashCode(), startIntent(context).putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /** Pause/skip/stop for run [runId], from its notification or a widget. */
        fun actionPendingIntent(context: Context, action: String, runId: Long): PendingIntent = PendingIntent.getService(
            context, "$action/$runId".hashCode(),
            Intent(context, TimerService::class.java).setAction(action).putExtra(EXTRA_RUN_ID, runId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
