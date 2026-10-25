package ir.peygir.app

import android.icu.util.Calendar
import android.icu.util.ULocale
import java.time.LocalDate
import java.time.ZoneId
import java.time.Instant

fun Any.fa() = toString().map { if (it in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[it-'0'] else it }.joinToString("")
fun String.latin() = map { when(it) { in '۰'..'۹' -> '0'+(it-'۰'); in '٠'..'٩' -> '0'+(it-'٠'); else -> it } }.joinToString("")
data class SolarDate(val year: Int, val month: Int, val day: Int)
object Solar {
    val months = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
    private fun calendar() = Calendar.getInstance(ULocale("fa_IR@calendar=persian")).apply { isLenient = false }
    fun parts(date: LocalDate): SolarDate = calendar().run {
        timeInMillis = date.atTime(12,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        SolarDate(get(Calendar.YEAR), get(Calendar.MONTH)+1, get(Calendar.DAY_OF_MONTH))
    }
    fun toDate(year: Int, month: Int, day: Int): LocalDate? = runCatching {
        require(year in 1200..1600 && month in 1..12 && day in 1..31)
        val c = calendar().apply { clear(); set(year, month-1, day, 12, 0, 0) }
        Instant.ofEpochMilli(c.timeInMillis).atZone(ZoneId.systemDefault()).toLocalDate().also {
            require(parts(it) == SolarDate(year, month, day))
        }
    }.getOrNull()
    fun parse(value: String): LocalDate? {
        val m = Regex("(?<!\\d)(1[2-6][0-9]{2})[/\\-٫.](\\d{1,2})[/\\-٫.](\\d{1,2})(?!\\d)").find(value.latin()) ?: return null
        return toDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }
    fun label(date: LocalDate): String = parts(date).let { "${it.day.fa()} ${months[it.month-1]} ${it.year.fa()}" }
    fun numeric(date: LocalDate): String = parts(date).let { "%04d/%02d/%02d".format(java.util.Locale.US,it.year,it.month,it.day).fa() }
    fun monthStart(date: LocalDate) = date.minusDays(parts(date).day.toLong()-1)
    fun nextMonth(date: LocalDate) = monthStart(monthStart(date).plusDays(32))
    fun previousMonth(date: LocalDate) = monthStart(monthStart(date).minusDays(1))
}
