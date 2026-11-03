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
import java.time.ZoneId
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ReservationProgressTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var store: Store
    private val deputy=UUID.randomUUID().toString()
    private val chief=UUID.randomUUID().toString()
    @Before fun setup() {context.deleteDatabase("reservation-progress.db");store=Store(context,"reservation-progress.db");OrgAccess.set(context,null)}
    @After fun close() {store.close();OrgAccess.set(context,null)}
    private fun role(id: String,role: String) {OrgAccess.set(context,SharedSession("https://test.invalid","","","","کاربر",role,id))}
    @Test fun reservationBlocksTaskCreationAndConversionWithExistingTasksButCanBeConfirmed() {
        val id=store.saveMeeting(Meeting(title="رزرو اتاق",reserved=true))
        assertTrue(store.snapshot().meetings.single().reserved)
        assertThrows(IllegalArgumentException::class.java){store.saveTask(Resolution(title="مصوبه",owner="مسئول",meetingId=id),emptyList())}
        assertTrue(store.snapshot().tasks.isEmpty())
        store.saveMeeting(store.snapshot().meetings.single().copy(reserved=false))
        store.saveTask(Resolution(title="مصوبه",owner="مسئول",meetingId=id),emptyList())
        assertThrows(IllegalArgumentException::class.java){store.saveMeeting(store.snapshot().meetings.single().copy(reserved=true))}
        assertFalse(store.snapshot().meetings.single().reserved)
    }
    @Test fun reservationSurvivesCopyRecurrenceAndSharedPayload() {
        val m=Meeting(title="رزرو تکراری",reserved=true,repeat="WEEKLY",until=LocalDate.now().plusDays(14).toEpochDay())
        store.saveMeetingSeries(m)
        assertTrue(store.snapshot().meetings.all{it.reserved})
        assertTrue(MeetingCopies.decode(MeetingCopies.encode(MeetingCopies.draft(m))).reserved)
        val shared=SharedStore(store);shared.bind("https://test.invalid",UUID.randomUUID().toString())
        assertTrue(shared.prepare().all{it.operation.getJSONObject("payload").getBoolean("reserved")})
    }
    @Test fun halfwayAlarmIsAutomaticExactAndDeliveredOnlyOnceIncludingAfterReopen() {
        val now=System.currentTimeMillis()
        val due=LocalDate.now(ZoneId.of("Asia/Tehran")).toEpochDay()
        val end=LocalDate.ofEpochDay(due).plusDays(1).atStartOfDay(ZoneId.of("Asia/Tehran")).toInstant().toEpochMilli()
        val id=store.saveTask(Resolution(title="پیگیری",owner="مسئول",due=due,created=now-(end-now)-10000),emptyList())
        val t=store.snapshot().tasks.single()
        assertEquals(t.created+(end-t.created)/2,DeadlineProgress.at(t))
        var delivered=0
        DeadlineProgress.process(context,store,store.snapshot(),now){delivered++;true}
        DeadlineProgress.process(context,store,store.snapshot(),now){delivered++;true}
        store.close();store=Store(context,"reservation-progress.db")
        DeadlineProgress.process(context,store,store.snapshot(),now){delivered++;true}
        assertEquals(1,delivered)
        assertNull(DeadlineProgress.at(t.copy(status=Status.DONE)))
        assertNull(DeadlineProgress.at(t.copy(status=Status.REVIEW)))
        store.saveTask(t.copy(title="ویرایش عنوان",created=1),emptyList())
        assertEquals(t.created,store.snapshot().tasks.single{it.id==id}.created)
    }
    @Test fun failedNotificationCanRetryAndDoneTasksNeverNotify() {
        val now=System.currentTimeMillis()
        store.saveTask(Resolution(title="پیگیری",owner="مسئول",due=LocalDate.now().toEpochDay(),created=now-7*86400000),emptyList())
        var attempts=0
        DeadlineProgress.process(context,store,store.snapshot(),now){attempts++;false}
        DeadlineProgress.process(context,store,store.snapshot(),now){attempts++;true}
        assertEquals(2,attempts)
        store.status(store.snapshot().tasks.single().id,Status.DONE)
        assertNull(DeadlineProgress.next(context,store,store.snapshot(),now))
    }
    @Test fun extensionRequiresAssigneeAndChiefApprovalWhileRejectionKeepsTheOriginalDue() {
        val due=LocalDate.now().plusDays(4).toEpochDay()
        store.saveTask(Resolution(title="تحویل گزارش",owner="مسئول",assignee=deputy,due=due),emptyList())
        val original=store.snapshot().tasks.single()
        role(chief,"manager")
        assertThrows(IllegalArgumentException::class.java){DeadlineProgress.request(store,context,original,"نیمه‌کاره","منتظر داده",due+2)}
        role(deputy,"deputy")
        DeadlineProgress.request(store,context,original,"۵۰ درصد پیشرفت","منتظر داده",due+2)
        var pending=store.snapshot().tasks.single()
        assertEquals(due,pending.due)
        assertEquals("PENDING",JSONObject(pending.extension!!).getString("status"))
        assertThrows(IllegalArgumentException::class.java){DeadlineProgress.decide(store,context,pending,true)}
        role(chief,"manager");DeadlineProgress.decide(store,context,pending,false)
        val rejected=store.snapshot().tasks.single()
        assertEquals(due,rejected.due);assertEquals("REJECTED",JSONObject(rejected.extension!!).getString("status"))
        role(deputy,"deputy");DeadlineProgress.request(store,context,rejected,"۷۰ درصد پیشرفت","دریافت دادهٔ تکمیلی",due+3)
        pending=store.snapshot().tasks.single()
        role(chief,"manager");DeadlineProgress.decide(store,context,pending,true)
        val approved=store.snapshot().tasks.single()
        assertEquals(due+3,approved.due)
        assertEquals(chief,JSONObject(approved.extension!!).getString("decidedBy"))
        assertEquals(DueChangeKind.EXTEND,store.snapshot().history.single().kind)
        assertEquals(approved,decodeTask(approved.encode()))
    }
}
