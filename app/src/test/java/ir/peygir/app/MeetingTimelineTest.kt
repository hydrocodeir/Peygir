package ir.peygir.app

import org.junit.Assert.*
import org.junit.Test

class MeetingTimelineTest {
    @Test fun emptyTimePositionMapsToQuarterHoursAfterScrollingAndAtDayEdges() {
        assertEquals(0,MeetingTimeline.minuteAt(-10f,1440f))
        assertEquals(555,MeetingTimeline.minuteAt(569f,1440f))
        assertEquals(555,MeetingTimeline.minuteAt(555f*2,2880f))
        assertEquals(1425,MeetingTimeline.minuteAt(1440f,1440f))
    }
    @Test fun overlappingAppointmentsUseSeparateLanesAndTouchingMeetingsRegainFullWidth() {
        val placed=MeetingTimeline.place(listOf(
            Meeting(id=1,start=480,end=720),Meeting(id=2,start=540,end=600),
            Meeting(id=3,start=600,end=660),Meeting(id=4,start=720,end=780)
        ).reversed())
        assertEquals(listOf(0,1,1,0),placed.map{it.lane})
        assertEquals(listOf(2,2,2,1),placed.map{it.lanes})
        assertEquals(listOf(1L,2L,3L,4L),placed.map{it.meeting.id})
    }
    @Test fun nestedAndConcurrentMeetingsNeverShareASlotAndUntimedRecordsAreSeparate() {
        val meetings=listOf(Meeting(id=1,start=0,end=1440),Meeting(id=2,start=5,end=15),Meeting(id=3,start=10,end=20),Meeting(id=4,start=20,end=30),Meeting(id=5,start=null,end=null))
        val placed=MeetingTimeline.place(meetings)
        assertEquals(4,placed.size);assertTrue(placed.all{it.lanes==3})
        for(a in placed) for(b in placed) if(a.meeting.id!=b.meeting.id && a.meeting.start!!<b.meeting.end!! && b.meeting.start!!<a.meeting.end!!) assertNotEquals(a.lane,b.lane)
    }
}
