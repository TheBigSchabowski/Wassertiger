package com.example.wassertiger

import androidx.compose.runtime.Composable

actual fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

actual fun wallClockMillis(): Long = System.currentTimeMillis()

@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) {
}
