package com.example.wassertiger

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Base64

actual suspend fun httpRequest(method: String, url: String, user: String, password: String, body: String?): HttpResult =
    withContext(Dispatchers.IO) {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray()))
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            HttpResult(code, text)
        } finally {
            conn.disconnect()
        }
    }

actual fun isoInstant(millis: Long): String = Instant.ofEpochMilli(millis).truncatedTo(ChronoUnit.SECONDS).toString()

actual fun clockTime(millis: Long): String =
    LocalTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("HH:mm"))

private fun parseLocalDateOrToday(date: String): LocalDate =
    runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now(ZoneId.systemDefault()) }

actual fun todayDate(): String = LocalDate.now(ZoneId.systemDefault()).toString()

actual fun dateOfMillis(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

actual fun startOfIsoWeek(date: String): String =
    parseLocalDateOrToday(date).with(DayOfWeek.MONDAY).toString()

actual fun addDaysToDate(date: String, days: Int): String =
    parseLocalDateOrToday(date).plusDays(days.toLong()).toString()

actual fun formatDayHeader(date: String): Pair<String, String> {
    val d = parseLocalDateOrToday(date)
    val weekday = when (d.dayOfWeek) {
        DayOfWeek.MONDAY -> "Mo"
        DayOfWeek.TUESDAY -> "Di"
        DayOfWeek.WEDNESDAY -> "Mi"
        DayOfWeek.THURSDAY -> "Do"
        DayOfWeek.FRIDAY -> "Fr"
        DayOfWeek.SATURDAY -> "Sa"
        DayOfWeek.SUNDAY -> "So"
    }
    val dayMonth = d.format(DateTimeFormatter.ofPattern("dd.MM."))
    return weekday to dayMonth
}

actual fun formatWeekHeader(weekStart: String): String {
    val start = parseLocalDateOrToday(weekStart)
    val end = start.plusDays(6)
    val kw = start.get(WeekFields.ISO.weekOfWeekBasedYear())
    val fmt = DateTimeFormatter.ofPattern("dd.MM.")
    return "KW $kw · ${start.format(fmt)}–${end.format(fmt)}"
}

actual fun combineDateAndTime(date: String, hhMm: String): Long? = runCatching {
    val d = LocalDate.parse(date.trim())
    val t = LocalTime.parse(hhMm.trim(), DateTimeFormatter.ofPattern("H:mm"))
    d.atTime(t).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()
