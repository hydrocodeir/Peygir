package ir.peygir.app

import android.app.NotificationManager
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

/** Exercises real OS PendingIntents and the manifest receiver on a tracked synthetic task only. */
class DeviceNotificationActionsTest {
    private fun await(check: ()->Boolean) {
        val until=SystemClock.elapsedRealtime()+8000
        while(SystemClock.elapsedRealtime()<until) {if(check()) return;Thread.sleep(50)}
        assertTrue("Notification action did not finish",check())
    }
    @Test fun realNotificationBroadcastsSnoozeAndCompleteTheSyntheticTask() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("Task notification permission/channel disabled",Reminders.allowed(context))
        val store=Store.get(context);val manager=context.getSystemService(NotificationManager::class.java)
        val id=store.saveTask(Resolution(title="آزمون پیگیر ۱.۸ • ${UUID.randomUUID().toString().take(8)}",owner="مسئول آزمایشی"),listOf(System.currentTimeMillis()+60000))
        try {
            fun makeDue() {
                store.writableDatabase.execSQL("UPDATE reminders SET at=? WHERE task_id=? AND delivered=0",arrayOf(System.currentTimeMillis()-100,id))
                Reminders.process(context)
            }
            val original=store.snapshot().tasks.single{it.id==id}
            makeDue()
            val notification=manager.activeNotifications.single{it.id==id.toInt()}.notification
            assertEquals(2,notification.actions.size)
            if(android.os.Build.VERSION.SDK_INT>=31) assertTrue(notification.actions.all{it.isAuthenticationRequired})
            val snooze=notification.actions.single{it.title.toString()=="یک ساعت دیگر یادآوری کن"}.actionIntent
            val before=System.currentTimeMillis();snooze.send()
            await {store.snapshot().reminders.any{it.taskId==id && !it.delivered && it.at>before}}
            val reminder=store.snapshot().reminders.single{it.taskId==id && !it.delivered}
            assertTrue(reminder.at in before+3_600_000..System.currentTimeMillis()+3_600_000)
            assertEquals(original,store.snapshot().tasks.single{it.id==id})
            await {manager.activeNotifications.none{it.id==id.toInt()}}
            makeDue()
            val again=manager.activeNotifications.single{it.id==id.toInt()}.notification
            again.actions.single{it.title.toString()=="انجام شد"}.actionIntent.send()
            await {store.snapshot().tasks.single{it.id==id}.status==Status.DONE && manager.activeNotifications.none{it.id==id.toInt()}}
            assertTrue(store.snapshot().reminders.filter{it.taskId==id}.all{it.delivered})
        } finally {
            store.deleteTask(id);manager.cancel(id.toInt());Reminders.schedule(context);ReviewWidget.updateAll(context)
        }
    }
}
