package ir.peygir.app

import org.junit.Test
import org.junit.Assert.*
import java.time.*

class ReviewDaysTest {
    private val zone=ZoneId.of("Asia/Tehran")
    private val day=LocalDate.of(2026,10,23).toEpochDay()
    @Test fun deadlinePagesKeepCompletedItemsAndSeparateDates() {
        val data=Snapshot(tasks=listOf(Resolution(id=1,title="الف",due=day),Resolution(id=2,title="ب",due=day,status=Status.DONE),Resolution(id=3,title="ج",due=day+1)))
        assertEquals(listOf(1L,2L),ReviewDays.forDay(data,day,false,zone).tasks.map{it.id})
        assertEquals(listOf(3L),ReviewDays.forDay(data,day+1,false,zone).tasks.map{it.id})
        assertTrue(ReviewDays.forDay(data,day-1,false,zone).tasks.isEmpty())
    }
    @Test fun reminderPagesDeduplicateTasksAndUseLocalMidnight() {
        val midnight=LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        val data=Snapshot(tasks=listOf(Resolution(id=1,due=day+10),Resolution(id=2,due=day)),reminders=listOf(Reminder(1,1,midnight,false),Reminder(2,1,midnight+60000,true),Reminder(3,2,midnight-1,false)))
        assertEquals(listOf(1L),ReviewDays.forDay(data,day,true,zone).tasks.map{it.id})
        assertEquals(listOf(2L),ReviewDays.forDay(data,day-1,true,zone).tasks.map{it.id})
    }
    @Test fun reviewContainsOnlyResolutionsEvenWhenMeetingsAndRecordingsExist() {
        val midnight=LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        val data=Snapshot(meetings=listOf(Meeting(id=1,date=day),Meeting(id=2,date=day-1)),tasks=(1..2000).map{Resolution(id=it.toLong(),due=day)},recordings=listOf(Recording(id=1,path="a.wav",created=midnight,seconds=2),Recording(id=2,path="b.wav",created=midnight-1,seconds=2)))
        val review=ReviewDays.forDay(data,day,false,zone)
        assertEquals(2000,review.tasks.size)
        assertTrue(review.meetings.isEmpty())
        assertTrue(review.recordings.isEmpty())
    }
}
