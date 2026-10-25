package ir.peygir.app

import java.time.*

object DefaultFollowUp {
    /** A calendar day before the due date; never silently creates a past alarm. */
    fun forDue(due: Long, now: ZonedDateTime = ZonedDateTime.now()): Long? {
        val day=LocalDate.ofEpochDay(due).minusDays(1)
        if(day<now.toLocalDate()) return null
        val morning=day.atTime(9,0).atZone(now.zone)
        if(morning>now) return morning.toInstant().toEpochMilli()
        val soon=now.plusMinutes(5).withSecond(0).withNano(0)
        return soon.takeIf{it.toLocalDate()==day && it>now}?.toInstant()?.toEpochMilli()
    }
}
