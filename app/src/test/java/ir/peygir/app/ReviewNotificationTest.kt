package ir.peygir.app

import android.app.*
import android.content.Context
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class ReviewNotificationTest {
    private lateinit var context: Context
    private lateinit var store: Store
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db");store=Store.get(context)
        context.getSharedPreferences("settings",0).edit().clear().commit()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }
    @After fun close() {store.close()}
    @Test fun defaultsAreEightAndEnabledButExistingChoicesArePreserved() {
        assertEquals(listOf(LocalTime.of(8,0)),Reminders.dailyTimes(context));assertTrue(Reminders.dailyEnabled(context))
        context.getSharedPreferences("settings",0).edit().putString("times","09:30,16:00").putBoolean("daily",false).commit()
        assertEquals(listOf(LocalTime.of(9,30),LocalTime.of(16,0)),Reminders.dailyTimes(context));assertFalse(Reminders.dailyEnabled(context))
    }
    @Test fun nextAndDueTimesRespectLocalDayAndTimezone() {
        val before=ZonedDateTime.of(2026,10,23,7,59,0,0,ZoneId.of("Asia/Tehran"))
        val eight=before.withHour(8).withMinute(0).toInstant().toEpochMilli()
        assertNull(Reminders.dueDaily(before,listOf(LocalTime.of(8,0))))
        assertEquals(eight,Reminders.nextDaily(before,listOf(LocalTime.of(8,0))))
        assertEquals(eight,Reminders.dueDaily(before.plusMinutes(1),listOf(LocalTime.of(8,0))))
        assertEquals(eight+Duration.ofDays(1).toMillis(),Reminders.nextDaily(before.plusMinutes(1),listOf(LocalTime.of(8,0))))
    }
    @Test fun bodyContainsTodaysTasksAndCompletedCountButOnlyCountsOlderOpenWork() {
        val day=LocalDate.now().toEpochDay()
        val data=Snapshot(tasks=listOf(Resolution(id=1,title="امروز فعال",owner="احمدی",due=day),Resolution(id=2,title="امروز تمام",owner="رضایی",due=day,status=Status.DONE),Resolution(id=3,title="عنوان معوق خصوصی",owner="کریمی",due=day-1),Resolution(id=4,title="فردای نامرتبط",owner="قاسمی",due=day+1)))
        val body=Reminders.reviewBody(data,day)
        assertTrue(body.contains("امروز فعال"));assertTrue(body.contains("امروز تمام"));assertTrue(body.contains("۱ معوق"));assertTrue(body.contains("۱ انجام‌شده"))
        assertFalse(body.contains("عنوان معوق خصوصی"));assertFalse(body.contains("فردای نامرتبط"))
    }
    private fun makeDue() {context.getSharedPreferences("settings",0).edit().putString("times","00:00").commit()}
    @Test fun reviewIsNotRepeatedAndClickOpensTodaysReviewEvenOnEmptyDay() {
        makeDue();Reminders.process(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        val notification=shadowOf(manager).allNotifications.single()
        assertEquals(Reminders.REVIEW_CHANNEL,notification.channelId)
        assertTrue(notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("امروز مصوبه‌ای سررسید ندارد"))
        val request=WidgetLaunch.from(shadowOf(notification.contentIntent).savedIntent)!!
        assertEquals(WidgetLaunch.REVIEW,request.kind);assertEquals(LocalDate.now().toEpochDay(),request.day)
        manager.cancelAll();Reminders.process(context);assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertNotNull(shadowOf(context.getSystemService(AlarmManager::class.java)).nextScheduledAlarm)
    }
    @Test fun deniedReviewChannelIsRetriedAfterPermissionAndDoesNotMarkDeliveredEarly() {
        makeDue();Reminders.channels(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(false);Reminders.process(context)
        assertEquals(0,context.getSharedPreferences("settings",0).getLong("last_review",0))
        shadowOf(manager).setNotificationsEnabled(true);Reminders.process(context)
        assertEquals(1,shadowOf(manager).allNotifications.size)
        assertTrue(context.getSharedPreferences("settings",0).getLong("last_review",0)>0)
    }
}
