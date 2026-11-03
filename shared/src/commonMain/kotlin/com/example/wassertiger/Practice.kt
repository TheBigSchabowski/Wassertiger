package com.example.wassertiger

/**
 * Practice timers started from another app (MetalRat: "Üben" on a saved video). One interval of
 * the chosen length; at the end Wassertiger signals as usual. Android: an intent with [ACTION];
 * desktop: POST to 127.0.0.1:[PORT]/practice or the start arguments [ARG_SECONDS] / [ARG_TITLE].
 */
object Practice {
    const val ACTION = "com.example.wassertiger.action.PRACTICE"
    const val EXTRA_SECONDS = "seconds"
    const val EXTRA_TITLE = "title"
    const val EXTRA_URL = "url"
    const val PORT = 47124
    const val ARG_SECONDS = "--practice-seconds"
    const val ARG_TITLE = "--practice-title"

    fun session(seconds: Int, title: String?): Session = Session(
        name = title?.trim()?.takeIf { it.isNotEmpty() } ?: "Üben",
        steps = listOf(Step(seconds.coerceIn(60, 8 * 3600), name = "Üben")),
        rounds = 1,
        vibration = 2,
    )
}
