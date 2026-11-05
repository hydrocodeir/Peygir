package ir.peygir.app

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.RemoteMessage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class MeetingAlertsTest {
    private val c: Context get()=RuntimeEnvironment.getApplication()
    private lateinit var store: Store
    private lateinit var manager: NotificationManager
    private val day=LocalDate.of(2027,2,10)
    private val now get()=day.atTime(10,0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    @Before fun setup() {
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        c.deleteDatabase("peygir.db");store=Store.get(c)
        c.getSharedPreferences("settings",0).edit().clear().putBoolean("daily",false).commit()
        c.getSharedPreferences("shared_private",0).edit().clear().commit();c.getSharedPreferences("push_private",0).edit().clear().commit();OrgAccess.set(c,null)
        manager=c.getSystemService(NotificationManager::class.java);manager.cancelAll()
    }
    @After fun cleanup(){manager.cancelAll();store.close();c.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(c,null)}
    @Test fun meetingsHaveNoAlarmByDefaultAndLocalOverridesCanEnableOrDisableIt() {
        val id=store.saveMeeting(Meeting(title="جلسه",date=day.toEpochDay(),start=610,end=670))
        assertTrue(MeetingAlerts.plans(store.snapshot(),UserOptions.read(c).meetingLead).isEmpty())
        store.syncDb{MeetingAlerts.save(it,id,15)}
        val plan=MeetingAlerts.plans(store.snapshot(),-1).single()
        assertEquals(now-5*60_000,plan.at)
        store.syncDb{MeetingAlerts.save(it,id,-1)}
        assertTrue(MeetingAlerts.plans(store.snapshot(),15).isEmpty())
        store.syncDb{MeetingAlerts.save(it,id,null)}
        assertEquals(now-5*60_000,MeetingAlerts.plans(store.snapshot(),15).single().at)
    }
    @Test fun localAlarmPostsOnceAndEditingOrDeletingCancelsItsStaleNotification() {
        val id=store.saveMeeting(Meeting(title="جلسهٔ نزدیک",date=day.toEpochDay(),start=610,end=670,alertMinutes=15))
        MeetingAlerts.process(c,store,store.snapshot(),now)
        val notice=manager.activeNotifications.single()
        assertEquals("meeting:$id",notice.tag);assertEquals("یادآوری جلسه - جلسهٔ نزدیک",notice.notification.extras.getString("android.title"))
        val open=shadowOf(notice.notification.contentIntent).savedIntent
        assertEquals(id,open.getLongExtra("meeting_id",0))
        MeetingAlerts.process(c,store,store.snapshot(),now);assertEquals(1,manager.activeNotifications.size)
        val m=store.snapshot().meetings.single();store.saveMeeting(m.copy(start=720,end=780))
        MeetingAlerts.process(c,store,store.snapshot(),now);assertTrue(manager.activeNotifications.isEmpty())
        MeetingAlerts.process(c,store,store.snapshot(),now+115*60_000);assertEquals(1,manager.activeNotifications.size)
        store.deleteMeeting(id);MeetingAlerts.process(c,store,store.snapshot(),now+115*60_000)
        assertTrue(manager.activeNotifications.isEmpty());assertTrue(store.syncDb{MeetingAlerts.overrides(it)}.isEmpty())
    }
    @Test fun earliestMeetingAndTaskShareTheExistingAlarmDispatcher() {
        val previousZone=TimeZone.getDefault()
        try {for(zone in listOf("UTC","Asia/Tehran","America/New_York")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val tomorrow=LocalDate.now().plusDays(1)
            val meeting=store.saveMeeting(Meeting(title="فردا",date=tomorrow.toEpochDay(),start=540,end=600,alertMinutes=15))
            val expected=tomorrow.atTime(8,45).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val alarms=shadowOf(c.getSystemService(android.app.AlarmManager::class.java))
            Reminders.schedule(c)
            assertEquals("Meeting must be the earliest alarm in $zone",expected,alarms.nextScheduledAlarm!!.triggerAtTime)
            val earlier=expected-60_000
            // Keep the automatic progress alarm later than both events under test.
            // A default task due tomorrow can legitimately have an earlier midpoint in UTC.
            val task=store.saveTask(Resolution(title="کار",owner="احمدی",due=tomorrow.plusDays(7).toEpochDay()),listOf(earlier))
            assertTrue(DeadlineProgress.at(store.snapshot().tasks.single())!!>expected)
            Reminders.schedule(c)
            assertEquals("Manual reminder must precede the meeting in $zone",earlier,alarms.nextScheduledAlarm!!.triggerAtTime)
            store.deleteTask(task);store.deleteMeeting(meeting);Reminders.cancel(c)
        }} finally {TimeZone.setDefault(previousZone)}
    }
    @Test fun progressAlarmCanPrecedeBothMeetingAndManualReminderInEveryDeviceZone() {
        val previousZone=TimeZone.getDefault()
        try {for(zone in listOf("UTC","Asia/Tehran","America/New_York")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val due=LocalDate.now(ZoneId.of("Asia/Tehran")).plusDays(2).toEpochDay()
            val task=store.saveTask(Resolution(title="پیشرفت",owner="احمدی",due=due),emptyList())
            val snapshot=store.snapshot()
            val expected=DeadlineProgress.at(snapshot.tasks.single())!!
            assertTrue(expected>System.currentTimeMillis())
            val meetingDay=java.time.Instant.ofEpochMilli(expected).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1)
            val meeting=store.saveMeeting(Meeting(title="پس از پیگیری",date=meetingDay.toEpochDay(),start=540,end=600,alertMinutes=15))
            val manualAt=meetingDay.atTime(8,44).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            store.saveTask(snapshot.tasks.single(),listOf(manualAt))
            assertTrue(manualAt>expected)
            Reminders.schedule(c)
            val alarms=shadowOf(c.getSystemService(android.app.AlarmManager::class.java))
            assertEquals("Progress reminder must win in $zone",expected,alarms.nextScheduledAlarm!!.triggerAtTime)
            c.getSharedPreferences("settings",0).edit().putBoolean("progress_enabled",false).commit()
            Reminders.schedule(c)
            assertEquals("Disabling progress must expose the next manual reminder in $zone",manualAt,alarms.nextScheduledAlarm!!.triggerAtTime)
            c.getSharedPreferences("settings",0).edit().putBoolean("progress_enabled",true).commit()
            store.deleteTask(task);store.deleteMeeting(meeting);Reminders.cancel(c)
        }} finally {TimeZone.setDefault(previousZone)}
    }
    @Test fun ordinaryUpdatesCanBeMutedWithoutSuppressingSyncOrImportantEvents() {
        c.getSharedPreferences("shared_private",0).edit().putString("session","test-only").putBoolean("alerts",true).commit()
        c.getSharedPreferences("settings",0).edit().putString("push_updates","essential").commit()
        OrgAccess.set(c,SharedSession("https://unit.invalid","","","","کاربر","deputy","u","company-a"))
        val service=Robolectric.buildService(PeygirMessagingService::class.java).create()
        fun event(kind: String,id: String=kind)=RemoteMessage.Builder("server").setData(mapOf("userId" to "u","company" to "company-a","kind" to kind,"recordId" to kind,"eventId" to id)).build()
        try {
            service.get().onMessageReceived(event("meeting"));service.get().onMessageReceived(event("task"));assertTrue(manager.activeNotifications.isEmpty())
            assertNotNull(c.getSystemService(android.app.job.JobScheduler::class.java).getPendingJob(7002))
            listOf("invitation","assignment","review").forEach{service.get().onMessageReceived(event(it))}
            assertEquals(3,manager.activeNotifications.size)
            c.getSharedPreferences("settings",0).edit().putString("push_updates","all").commit()
            service.get().onMessageReceived(event("meeting"));assertEquals(3,manager.activeNotifications.size)
            service.get().onMessageReceived(event("meeting","fresh-edit"));assertEquals(4,manager.activeNotifications.size)
            manager.cancelAll()
            store.saveMeeting(Meeting(title="جلسهٔ خودم",date=day.toEpochDay(),start=610,end=670,alertMinutes=15))
            c.getSharedPreferences("settings",0).edit().putString("push_updates","essential").commit()
            MeetingAlerts.process(c,store,store.snapshot(),now);assertEquals(1,manager.activeNotifications.size)
            val id=store.saveTask(Resolution(title="موعد",owner="احمدی"),emptyList())
            store.writableDatabase.execSQL("INSERT INTO reminders(task_id,at) VALUES(?,?)",arrayOf(id,System.currentTimeMillis()-1000))
            Reminders.process(c)
            assertTrue(manager.activeNotifications.any{it.notification.channelId=="follow_up"})
        } finally {service.destroy()}
    }
    @Test fun reminderOverridesStayLocalAcrossSyncAndDisappearWithCompanyData() {
        val id=store.saveMeeting(Meeting(title="جلسه",alertMinutes=15))
        val shared=SharedStore(store);shared.bind("https://unit.invalid",UUID.randomUUID().toString())
        val pending=shared.prepare().single();val payload=pending.operation.getJSONObject("payload")
        assertFalse(payload.has("localAlert"));assertFalse(payload.has("alertMinutes"))
        val record=org.json.JSONObject().put("id",pending.operation.getString("recordId")).put("kind","meeting").put("revision",1).put("deleted",false).put("payload",payload).put("actor","test").put("changed",1)
        shared.receive(record,pending,true)
        assertTrue(shared.prepare().isEmpty())
        store.saveMeeting(store.snapshot().meetings.single().copy(alertMinutes=30))
        assertTrue(shared.prepare().isEmpty());assertEquals(30,store.snapshot().meetings.single().alertMinutes)
        shared.receive(org.json.JSONObject(record.toString()).put("revision",2).put("payload",org.json.JSONObject(payload.toString()).put("title","تغییر سرور")))
        assertEquals(30,store.snapshot().meetings.single().alertMinutes)
        shared.clearScopedData(detachBinding=true)
        assertTrue(store.syncDb{MeetingAlerts.overrides(it)}.isEmpty())
        assertTrue(store.snapshot().meetings.isEmpty())
    }
}
