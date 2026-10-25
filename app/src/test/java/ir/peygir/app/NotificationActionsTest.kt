package ir.peygir.app

import android.app.*
import android.content.Context
import android.content.Intent
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class NotificationActionsTest {
    private lateinit var context: Context
    private lateinit var store: Store
    private lateinit var manager: NotificationManager
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db");store=Store.get(context)
        context.getSharedPreferences("settings",0).edit().clear().putBoolean("daily",false).commit()
        manager=context.getSystemService(NotificationManager::class.java);manager.cancelAll()
        shadowOf(manager).setNotificationsEnabled(true)
    }
    @After fun close() {store.close()}
    private fun post(title: String = "مصوبهٔ آزمایشی"): Pair<Long,Notification> {
        val id=store.saveTask(Resolution(title=title,owner="علی احمدی"),listOf(System.currentTimeMillis()+60000))
        store.writableDatabase.execSQL("UPDATE reminders SET at=? WHERE task_id=?",arrayOf(System.currentTimeMillis()-100,id))
        Reminders.process(context)
        return id to shadowOf(manager).getNotification(id.toInt())!!
    }
    private fun action(notification: Notification, label: String): Intent=shadowOf(notification.actions.single{it.title.toString()==label}.actionIntent).savedIntent
    @Test fun postedActionsHaveExplicitImmutableDistinctPendingIntents() {
        val (id,n)=post()
        assertEquals(2,n.actions.size)
        val done=action(n,"انجام شد");val later=action(n,"یک ساعت دیگر یادآوری کن")
        assertEquals(ReminderActionReceiver::class.java.name,done.component!!.className)
        assertEquals(id,done.getLongExtra("task_id",0));assertNotEquals(done.data,later.data)
        assertNotEquals(done.action,later.action);assertTrue(done.getStringExtra("token")!!.isNotBlank())
        assertEquals(PendingIntent.FLAG_IMMUTABLE,shadowOf(n.actions[0].actionIntent).flags and PendingIntent.FLAG_IMMUTABLE)
    }
    @Test fun snoozeAddsExactlyOneHourAndPreservesDueStatusOtherRemindersAndHistory() {
        val (id,n)=post();val task=store.snapshot().tasks.single()
        val laterAt=System.currentTimeMillis()+7_200_000
        store.saveTask(task,store.snapshot().reminders.map{it.at}+laterAt)
        val intent=action(n,"یک ساعت دیگر یادآوری کن");val before=System.currentTimeMillis()
        assertTrue(Reminders.handleAction(context,intent))
        val data=store.snapshot();assertEquals(task,data.tasks.single());assertTrue(data.history.isEmpty())
        val new=data.reminders.single{!it.delivered && it.at!=laterAt}
        assertTrue(new.at in before+3_600_000..System.currentTimeMillis()+3_600_000)
        assertTrue(data.reminders.any{it.at==laterAt && !it.delivered});assertNull(shadowOf(manager).getNotification(id.toInt()))
        assertFalse(Reminders.handleAction(context,intent));assertEquals(data,store.snapshot())
    }
    @Test fun doneCompletesTaskStopsRemindersAndIsSingleUse() {
        val (id,n)=post();val intent=action(n,"انجام شد")
        val future=System.currentTimeMillis()+7_200_000
        store.saveTask(store.snapshot().tasks.single(),store.snapshot().reminders.map{it.at}+future)
        assertTrue(Reminders.handleAction(context,intent))
        assertEquals(Status.DONE,store.snapshot().tasks.single().status)
        assertTrue(store.snapshot().reminders.filter{it.at!=future}.all{it.delivered})
        assertFalse(store.snapshot().reminders.single{it.at==future}.delivered)
        assertNull(shadowOf(manager).getNotification(id.toInt()))
        assertFalse(Reminders.handleAction(context,intent))
    }
    @Test fun pendingIntentsForDifferentTasksNeverAffectEachOther() {
        val (first,n1)=post("اول");val (second,n2)=post("دوم")
        val a=action(n1,"انجام شد");val b=action(n2,"انجام شد")
        assertNotEquals(a.data,b.data)
        assertTrue(Reminders.handleAction(context,a))
        assertEquals(Status.DONE,store.snapshot().tasks.single{it.id==first}.status)
        assertEquals(Status.WAITING,store.snapshot().tasks.single{it.id==second}.status)
        assertNotNull(shadowOf(manager).getNotification(second.toInt()))
    }
    @Test fun deletedAndSupersededActionsDoNothing() {
        val (id,old)=post();val oldAction=action(old,"انجام شد")
        store.activateNotification(id,"replacement",emptyList())
        assertFalse(Reminders.handleAction(context,oldAction));assertEquals(Status.WAITING,store.snapshot().tasks.single().status)
        store.deleteTask(id);assertFalse(Reminders.handleAction(context,oldAction))
        assertTrue(store.snapshot().tasks.isEmpty())
    }
    @Test fun restoreClearsOldTokensEvenWhenTaskIdsAreReused() {
        val (_,n)=post();val old=action(n,"انجام شد");val data=store.snapshot()
        store.replaceAll(data)
        assertFalse(Reminders.handleAction(context,old));assertEquals(data,store.snapshot())
    }
    @Test fun deniedNotificationsDoNotActivateActionsOrMarkRemindersDelivered() {
        shadowOf(manager).setNotificationsEnabled(false)
        val id=store.saveTask(Resolution(title="مسدود",owner="احمدی"),listOf(System.currentTimeMillis()+60000))
        store.writableDatabase.execSQL("UPDATE reminders SET at=? WHERE task_id=?",arrayOf(System.currentTimeMillis()-100,id))
        Reminders.process(context)
        assertFalse(store.snapshot().reminders.single().delivered)
        assertEquals(0,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM notification_tokens",null).use{it.moveToFirst();it.getInt(0)})
    }
    @Test fun oldVisibleTaskNotificationGetsActionsWithoutChangingDeliveredReminders() {
        val (id,_)=post()
        val before=store.snapshot()
        manager.notify(id.toInt(),androidx.core.app.NotificationCompat.Builder(context,"follow_up").setSmallIcon(R.drawable.ic_notification).setContentTitle("اعلان نسخهٔ قبلی").build())
        Reminders.process(context)
        val updated=shadowOf(manager).getNotification(id.toInt())!!
        assertEquals(2,updated.actions.size);assertEquals(before,store.snapshot())
        assertTrue(Reminders.handleAction(context,action(updated,"انجام شد")))
    }
}
