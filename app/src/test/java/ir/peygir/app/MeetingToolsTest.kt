package ir.peygir.app

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class MeetingToolsTest {
    @Test fun conflictRequiresTheSameDayOverlappingTimeAndARealSharedAccount() {
        val day=LocalDate.now().toEpochDay()
        val draft=Meeting(id=10,date=day,start=540,end=600,attendees=listOf("a","b"))
        val others=listOf(draft,
            Meeting(id=11,date=day,start=570,end=630,attendees=listOf("a","a","c")),
            Meeting(id=12,date=day,start=480,end=540,attendees=listOf("a")),
            Meeting(id=13,date=day,start=600,end=660,attendees=listOf("a")),
            Meeting(id=14,date=day+1,start=540,end=600,attendees=listOf("a")),
            Meeting(id=15,date=day,start=540,end=600,attendees=listOf("other-account")),
            Meeting(id=16,date=day,start=null,end=null,attendees=listOf("a")))
        val found=MeetingConflicts.find(draft,others)
        assertEquals(listOf(11L),found.map{it.meeting.id});assertEquals(setOf("a"),found.single().people)
        assertTrue(MeetingConflicts.find(draft.copy(attendees=emptyList()),others).isEmpty())
        assertTrue(MeetingConflicts.find(draft.copy(id=0,start=null,end=null),others).isEmpty())
    }
    @Test fun recurringDraftChecksEveryOccurrenceAgainstTheLocalCalendar() {
        val day=LocalDate.now().toEpochDay()
        val draft=Meeting(date=day,start=540,end=600,attendees=listOf("a","b"),repeat="WEEKLY",until=day+14)
        val others=listOf(Meeting(id=1,date=day+7,start=550,end=580,attendees=listOf("a")),Meeting(id=2,date=day+14,start=590,end=620,attendees=listOf("b")),Meeting(id=3,date=day+1,start=540,end=600,attendees=listOf("a")))
        assertEquals(listOf(1L,2L),MeetingConflicts.find(draft,others).map{it.meeting.id})
    }
    @Test fun copiedDraftKeepsPeopleAndDurationButDropsIdentityAndSeriesAndSurvivesSavedState() {
        val day=LocalDate.now().toEpochDay()
        val source=Meeting(id=9,title="شورا",date=day-7,notes="دستور جلسه",start=555,end=645,location="سالن",attendees=listOf("a","b"),repeat="WEEKLY",until=day+14,series="old-series")
        val copy=MeetingCopies.draft(source,day)
        assertEquals(source.copy(id=0,date=day,repeat="NONE",until=null,series=null),copy)
        assertEquals(copy,MeetingCopies.decode(MeetingCopies.encode(copy)))
        assertEquals(9L,source.id);assertEquals("old-series",source.series)
    }
}
