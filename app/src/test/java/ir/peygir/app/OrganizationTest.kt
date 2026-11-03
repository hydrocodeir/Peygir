package ir.peygir.app

import android.app.Application
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class OrganizationTest {
    private lateinit var store: Store
    private val context get()=RuntimeEnvironment.getApplication()
    private fun id()=UUID.randomUUID().toString()
    @Before fun setup() {context.deleteDatabase("organization.db");context.getSharedPreferences("organization_access",0).edit().clear().commit();store=Store(context,"organization.db")}
    @After fun close() {store.close();OrgAccess.set(context,null)}
    @Test fun weeklySeriesAreIndependentMeetingsAndEditingAnOccurrenceDoesNotRegenerateTheSeries() {
        val first=LocalDate.now().toEpochDay();val person=id()
        store.saveMeetingSeries(Meeting(title="شورای معاونین",date=first,start=540,end=600,attendees=listOf(person),repeat="WEEKLY",until=first+21))
        val meetings=store.snapshot().meetings.sortedBy{it.date};assertEquals(4,meetings.size);assertEquals(1,meetings.map{it.series}.distinct().size)
        assertEquals(listOf(first,first+7,first+14,first+21),meetings.map{it.date})
        store.saveMeetingSeries(meetings[1].copy(title="نوبت ویرایش‌شده"))
        assertEquals(4,store.snapshot().meetings.size);assertEquals(1,store.snapshot().meetings.count{it.title=="نوبت ویرایش‌شده"})
    }
    @Test fun monthlyRecurrenceUsesSolarMonthsAndClampsShortMonthsWithoutDrifting() {
        val first=Solar.toDate(1405,6,31)!!;val end=Solar.toDate(1405,9,30)!!
        val values=MeetingRecurrence.expand(Meeting(title="جلسه",date=first.toEpochDay(),repeat="MONTHLY",until=end.toEpochDay()))
        assertEquals(listOf(SolarDate(1405,6,31),SolarDate(1405,7,30),SolarDate(1405,8,30),SolarDate(1405,9,30)),values.map{Solar.parts(LocalDate.ofEpochDay(it.date))})
        assertThrows(IllegalArgumentException::class.java){MeetingRecurrence.expand(Meeting(repeat="DAILY",until=LocalDate.now().plusDays(367).toEpochDay()))}
        assertThrows(IllegalArgumentException::class.java){MeetingRecurrence.expand(Meeting(start=600,end=540))}
    }
    @Test fun meetingStoreRequiresACompleteValidTimeRangeAndSortsHoursRegardlessOfInsertOrder() {
        assertThrows(IllegalArgumentException::class.java){store.saveMeeting(Meeting(title="بدون ساعت",start=null,end=null))}
        assertThrows(IllegalArgumentException::class.java){store.saveMeeting(Meeting(title="ناقص",start=600,end=null))}
        assertThrows(IllegalArgumentException::class.java){store.saveMeeting(Meeting(title="برعکس",start=600,end=540))}
        store.saveMeeting(Meeting(title="عصر",start=900,end=960));store.saveMeeting(Meeting(title="صبح",start=480,end=540))
        assertEquals(listOf("صبح","عصر"),store.snapshot().meetings.map{it.title})
    }
    @Test fun accountBasedAssignmentAndApprovalMetadataSurviveDatabaseAndEditorEncoding() {
        val deputy=id();val chief=id()
        val t=Resolution(title="گزارش",owner="احمدی",assignee=deputy,status=Status.REVIEW,submitted=1234,approved=5678,approvedBy=chief)
        val key=store.saveTask(t,emptyList());assertEquals(t.copy(id=key,created=store.snapshot().tasks.single().created),store.snapshot().tasks.single());assertEquals(t,decodeTask(t.encode()))
    }
    @Test fun deputyDoneActionRequestsApprovalAndOnlyChiefCanApproveAReview() {
        val task=Resolution(title="گزارش",owner="احمدی",assignee=id())
        OrgAccess.set(context,SharedSession("https://test.invalid","","","","معاون","deputy",task.assignee!!))
        assertEquals(Status.REVIEW,OrgAccess.targetStatus(context,task,Status.DONE));assertFalse(Status.DONE in OrgAccess.statuses(context,task))
        val key=store.saveTask(task,emptyList());store.status(key,Status.DONE);assertEquals(Status.REVIEW,store.snapshot().tasks.single().status)
        OrgAccess.set(context,SharedSession("https://test.invalid","","","","رئیس","manager",id()))
        assertEquals(Status.DONE,OrgAccess.targetStatus(context,task.copy(status=Status.REVIEW),Status.DONE))
    }
    @Test fun revokedTaskPurgesPendingChildReportsAndCannotBecomeAConflictOrResurrect() {
        val shared=SharedStore(store);shared.bind("https://test.invalid",id())
        val remote=id();val person=id();val p=JSONObject().put("title","کار").put("description","").put("owner","احمدی").put("due",LocalDate.now().toEpochDay()).put("priority","NORMAL").put("status","WAITING").put("meeting",JSONObject.NULL).put("reminders",org.json.JSONArray()).taskOrganization(Resolution(assignee=person))
        shared.receive(JSONObject().put("kind","task").put("id",remote).put("revision",1).put("deleted",false).put("payload",p).put("view","first"))
        val local=store.snapshot().tasks.single().id;store.addFollowUp(local,"گزارش ارسال‌نشده")
        shared.receive(JSONObject().put("kind","task").put("id",remote).put("revision",2).put("deleted",true).put("revoked",true).put("payload",JSONObject()))
        assertTrue(store.snapshot().tasks.isEmpty());assertTrue(store.snapshot().followUps.isEmpty());assertTrue(shared.prepare().isEmpty());assertTrue(shared.conflicts().isEmpty());assertEquals(0,shared.inventory().length())
    }
    @Test fun sameRevisionProjectionChangeRemovesAndRestoresMeetingLink() {
        val shared=SharedStore(store);shared.bind("https://test.invalid",id());val meeting=id();val task=id()
        val m=JSONObject().put("title","جلسه").put("notes","").put("date",LocalDate.now().toEpochDay()).meetingOrganization(Meeting())
        shared.receive(JSONObject().put("kind","meeting").put("id",meeting).put("revision",1).put("deleted",false).put("payload",m))
        val p=JSONObject().put("title","کار").put("description","").put("owner","احمدی").put("due",LocalDate.now().toEpochDay()).put("priority","NORMAL").put("status","WAITING").put("meeting",meeting).put("reminders",org.json.JSONArray()).taskOrganization(Resolution())
        val record=JSONObject().put("kind","task").put("id",task).put("revision",1).put("deleted",false).put("payload",p).put("view","with-meeting")
        shared.receive(record);assertNotNull(store.snapshot().tasks.single().meetingId)
        p.put("meeting",JSONObject.NULL);record.put("view","without-meeting");shared.receive(record);assertNull(store.snapshot().tasks.single().meetingId)
        p.put("meeting",meeting);record.put("view","restored");shared.receive(record);assertNotNull(store.snapshot().tasks.single().meetingId)
    }
    @Test fun searchFindsPersianParticipantNameAcrossAllDatesAndNormalizesArabicCharacters() {
        val user=CompanyUser(id(),"علی احمدی","deputy")
        val m=Meeting(title="جلسه بودجه",attendees=listOf(user.id))
        assertTrue(meetingSearch(m,listOf(user),"علي احمدی"));assertTrue(meetingSearch(m,listOf(user),"بودجه احمدی"));assertFalse(meetingSearch(m,listOf(user),"رضایی"))
    }
    @Test fun backupV6IncludesCalendarAndAssignmentButNoCompanyDirectoryOrCredentials() {
        val meeting=store.saveMeeting(Meeting(title="جلسه",start=540,end=600,location="دفتر",attendees=listOf(id())))
        store.saveTask(Resolution(title="گزارش",owner="احمدی",meetingId=meeting,assignee=id(),status=Status.REVIEW),emptyList())
        val out=ByteArrayOutputStream();Backup.write(context,out,store)
        ZipInputStream(out.toByteArray().inputStream()).use{z->z.nextEntry;val p=JSONObject(z.readBytes().toString(Charsets.UTF_8));assertEquals(6,p.getInt("version"));assertFalse(p.has("directory"));assertFalse(p.has("session"))}
        Backup.read(context,out.toByteArray().inputStream()).use{assertEquals(store.snapshot(),it.data)}
    }
    @Test fun clearingOrganizationDataKeepsServerBindingButRemovesAllRecordsAndPendingWrites() {
        val shared=SharedStore(store);shared.bind("https://test.invalid",id());shared.principal(id())
        store.saveMeeting(Meeting(title="جلسه"));store.saveTask(Resolution(title="کار",owner="احمدی"),emptyList());shared.prepare()
        shared.clearScopedData();assertTrue(store.snapshot().tasks.isEmpty());assertTrue(store.snapshot().meetings.isEmpty());assertTrue(shared.prepare().isEmpty());assertTrue(shared.principal().isEmpty());assertTrue(shared.binding().isNotBlank())
    }
    @Test fun genuineVersionFiveDatabaseUpgradesWithoutDroppingRecordsOrAnUnsentChange() {
        val name="organization-v5.db";context.deleteDatabase(name)
        val db=context.openOrCreateDatabase(name,0,null)
        listOf(
            "CREATE TABLE meetings(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,date INTEGER NOT NULL,notes TEXT NOT NULL)",
            "CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT,meeting_id INTEGER REFERENCES meetings(id) ON DELETE SET NULL,title TEXT NOT NULL,description TEXT NOT NULL,owner TEXT NOT NULL,due INTEGER NOT NULL,priority TEXT NOT NULL,status TEXT NOT NULL)",
            "CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id INTEGER REFERENCES tasks(id) ON DELETE CASCADE,at INTEGER NOT NULL,delivered INTEGER NOT NULL DEFAULT 0,UNIQUE(task_id,at))",
            "CREATE TABLE recordings(id INTEGER PRIMARY KEY AUTOINCREMENT,meeting_id INTEGER,path TEXT,created INTEGER,seconds INTEGER,transcript TEXT,draft_session TEXT NOT NULL DEFAULT '')",
            "CREATE TABLE due_changes(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id INTEGER,old_due INTEGER,new_due INTEGER,changed_at INTEGER,kind TEXT,reason TEXT)",
            "CREATE TABLE follow_ups(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id INTEGER,text TEXT,created INTEGER)",
            "CREATE TABLE notification_tokens(task_id INTEGER PRIMARY KEY,token TEXT)",
            "CREATE TABLE backlog(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT,description TEXT,owner TEXT,created INTEGER)"
        ).forEach(db::execSQL)
        SharedStore.create(db)
        db.execSQL("INSERT INTO meetings VALUES(1,'جلسهٔ قبلی',20000,'یادداشت')")
        db.execSQL("INSERT INTO tasks VALUES(1,1,'مصوبهٔ قبلی','','احمدی',20001,'NORMAL','WAITING')")
        val deletedRemote=id()
        val legacyPayload=JSONObject().put("title","مصوبهٔ حذف‌شده").put("description","").put("owner","احمدی").put("due",20001).put("priority","NORMAL").put("status","WAITING").put("meeting",JSONObject.NULL).put("reminders",org.json.JSONArray())
        db.execSQL("INSERT INTO tasks VALUES(2,NULL,'مصوبهٔ حذف‌شده','','احمدی',20001,'NORMAL','WAITING')")
        db.execSQL("INSERT INTO sync_objects(kind,local_id,remote_id,revision,payload) VALUES('task',2,?,1,?)",arrayOf(deletedRemote,legacyPayload.toString()))
        db.execSQL("DELETE FROM tasks WHERE id=2")
        db.version=5;db.close()
        val upgraded=Store(context,name)
        try {
            val data=upgraded.snapshot();assertEquals(9,upgraded.readableDatabase.version);assertEquals("جلسهٔ قبلی",data.meetings.single().title);assertNull(data.tasks.single().assignee)
            val pending=SharedStore(upgraded).prepare();assertEquals(3,pending.size)
            val deleted=pending.single{it.operation.getBoolean("deleted")}.operation
            assertTrue(deleted.getJSONObject("payload").has("assignee"));assertTrue(deleted.getJSONObject("payload").has("submitted"));assertEquals(deletedRemote,deleted.getString("recordId"))
        } finally {upgraded.close();context.deleteDatabase(name)}
    }
}
