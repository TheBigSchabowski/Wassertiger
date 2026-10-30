package com.example.wassertiger

import androidx.compose.runtime.Composable

actual fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

@Composable
actual fun PlatformBackHandler(onBack: () -> Unit) {
}
