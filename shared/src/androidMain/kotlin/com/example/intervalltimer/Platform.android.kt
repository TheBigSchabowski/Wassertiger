package com.example.intervalltimer

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

actual fun monotonicMillis(): Long = SystemClock.elapsedRealtime()

@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) = BackHandler(onBack = onBack)
