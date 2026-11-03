package ir.peygir.app

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class ScaleDataTest {
    private val f=ScaleFixture
    @After fun clear(){Organization.members.value=emptyList();Organization.contacts.value=emptyList()}
    @Test fun twoHundredMeetingsFiveHundredTasksFiftyPeopleSurviveSQLiteAndBackup() {
        val c=RuntimeEnvironment.getApplication();val name="scale-isolated.db";c.deleteDatabase(name);val store=Store(c,name)
        try {
            f.meetings.forEach{store.saveMeeting(it.copy(id=0))}
            f.tasks.forEach{store.saveTask(it.copy(id=0),listOf(System.currentTimeMillis()+86_400_000L))}
            Organization.receiveUsers(store,JSONArray().apply{f.users.forEach{u->put(JSONObject().put("id",u.id).put("name",u.name).put("username",u.username).put("role",u.role).put("active",1))}})
            val data=store.snapshot()
            assertEquals(200,data.meetings.size);assertEquals(500,data.tasks.size);assertEquals(50,Organization.users(store).size)
            Status.entries.forEach{assertEquals(125,data.tasks.count{t->t.status==it})}
            assertTrue(data.tasks.all{it.created>0})
            assertEquals(f.tasks.toSet(),data.tasks.map{it.copy(created=0)}.toSet())
            val bytes=ByteArrayOutputStream().also{Backup.write(c,it,store)}.toByteArray()
            Backup.read(c,bytes.inputStream()).use{prepared->assertEquals(data.tasks.toSet(),prepared.data.tasks.toSet());assertEquals(data.meetings.toSet(),prepared.data.meetings.toSet())}
        } finally {store.close();c.deleteDatabase(name)}
    }
    @Test fun everyStatusFilterAndPersianMultiwordSearchReturnsTheExpectedRecords() {
        val people=f.users.associateBy{it.id};val meetings=f.meetings.associateBy{it.id}
        assertEquals(375,f.tasks.count{taskMatchesFilter(it,f.day,"open")})
        listOf("active" to Status.ACTIVE,"done" to Status.DONE,"review" to Status.REVIEW).forEach{(filter,status)->assertEquals(f.tasks.filter{it.status==status},f.tasks.filter{taskMatchesFilter(it,f.day,filter)})}
        val t=f.tasks.last();val text=SearchText.task(t,meetings)
        assertTrue(SearchText.matches(text,SearchText.tokens("گزارش 500 "+t.owner)))
        assertTrue(SearchText.matches(SearchText.meeting(f.meetings.first(),people),SearchText.tokens("پروژه علي احمدي")))
        assertFalse(SearchText.matches(text,SearchText.tokens("وجودندارد")))
        assertEquals(50,PeopleSuggestions.choices(f.users,emptyList(),"").size)
        assertEquals(f.users.last(),PeopleSuggestions.choices(f.users,emptyList(),"کریمی ۵۰").single().user)
    }
    @Test fun denseTimelineGroupsPreserveAllMeetingsWithoutDuplicateOrLostIds() {
        f.meetings.groupBy{it.date}.values.forEach {records->
            val placements=MeetingTimeline.place(records)
            for(limit in listOf(2,3)) {
                val groups=MeetingTimeline.denseGroups(placements,limit);val hidden=groups.flatMap{it.meetings}.map{it.id}.toSet()
                val visible=placements.filter{it.meeting.id !in hidden}.map{it.meeting.id}+groups.flatMap{it.meetings}.map{it.id}
                assertEquals(records.map{it.id}.toSet(),visible.toSet());assertEquals(records.size,visible.size)
                groups.forEach{assertTrue(it.start<it.end)}
            }
        }
        assertEquals(6,MeetingTimeline.denseGroups(MeetingTimeline.place(f.meetings.filter{it.date==f.day}),3).single().meetings.size)
    }
    @Test fun reviewAndBothWidgetsRetainEveryRelevantRecordInOrder() {
        for(byReminder in listOf(false,true)) assertEquals(f.tasks.filter{it.due==f.day && (!byReminder || it.status!=Status.DONE)}.map{it.id}.toSet(),ReviewDays.forDay(f.data,f.day,byReminder).tasks.map{it.id}.toSet())
        for(meetings in listOf(false,true)) {
            val rows=WidgetAgenda.rows(f.data,meetings,f.day)
            assertTrue(rows.all{it.day>=f.day})
            if(meetings) assertEquals(f.meetings.filter{it.date in f.day until f.day+30}.sortedWith(MeetingSchedule.order).map{it.id},rows.mapNotNull{it.meeting?.id})
            else assertEquals(f.tasks.filter{it.due in f.day until f.day+30}.map{it.id}.toSet(),rows.mapNotNull{it.task?.id}.toSet())
        }
    }
}
