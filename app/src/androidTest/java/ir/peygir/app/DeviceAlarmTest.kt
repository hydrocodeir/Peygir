package ir.peygir.app

import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
import java.io.File

/** Host invokes setup, inspect (while screen is locked), then cleanup in separate processes. */
class DeviceAlarmTest {
    @Test fun alarmPhase() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val store=Store.get(context)
        val file=File(context.cacheDir,"device-alarm.json")
        val args=InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Host-driven test: phase argument required",args.getString("phase") in setOf("setup","inspect","cleanup"))
        when(args.getString("phase")) {
            "setup" -> {
                assertTrue("Notifications permission/channel disabled",Reminders.allowed(context))
                assertTrue("Exact alarm access missing",Reminders.exact(context))
                val now=System.currentTimeMillis()
                val first=now+40000;val second=now+80000
                val id=store.saveTask(Resolution(title=DeviceSmokeTest.PREFIX+"یادآوری واقعی",owner="مسئول آزمایشی"),listOf(first,second))
                val done=store.saveTask(Resolution(title=DeviceSmokeTest.PREFIX+"انجام‌شده",owner="مسئول آزمایشی",status=Status.DONE),listOf(first+5000))
                file.writeText(JSONObject().put("task",id).put("done",done).put("firstAt",first).put("secondAt",second).toString())
                Reminders.schedule(context)
            }
            "inspect" -> {
                val state=JSONObject(file.readText());val id=state.getLong("task");val done=state.getLong("done")
                val alarms=store.snapshot().reminders.filter{it.taskId==id}
                val delivered=alarms.count{it.delivered}
                val notifications=context.getSystemService(NotificationManager::class.java).activeNotifications
                val active=notifications.any{it.id==id.toInt()}
                val doneActive=notifications.any{it.id==done.toInt()}
                val out=JSONObject().put("delivered",delivered).put("taskNotificationActive",active).put("doneNotificationActive",doneActive).put("checkedAt",System.currentTimeMillis())
                File(context.cacheDir,"device-alarm-inspect-${args.getString("expected")}.json").writeText(out.toString())
                assertEquals(args.getString("expected")!!.toInt(),delivered)
                assertTrue("Real alarm did not post notification",active)
                assertFalse("Completed task posted a notification",doneActive)
            }
            "cleanup" -> {
                val state=JSONObject(file.readText())
                listOf(state.getLong("task"),state.getLong("done")).forEach {store.deleteTask(it);context.getSystemService(NotificationManager::class.java).cancel(it.toInt())}
                Reminders.schedule(context)
            }
            else -> error("Supply phase=setup/inspect/cleanup")
        }
    }
}
