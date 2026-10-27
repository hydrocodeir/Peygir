package ir.peygir.app

import android.app.Application
import android.app.NotificationManager
import com.google.firebase.messaging.RemoteMessage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class PushServiceTest {
    @Test fun companyScopedPushRejectsOtherAndMissingCompaniesEvenWhenUserIdsMatch() {
        val c=RuntimeEnvironment.getApplication()
        fun select(company: String){OrgAccess.set(c,SharedSession("https://test.invalid","","","","معاون","deputy","same-user",company))}
        select("company-a");c.getSharedPreferences("shared_private",0).edit().putString("session","test-only").putBoolean("alerts",true).commit()
        c.getSharedPreferences("push_private",0).edit().clear().commit()
        val controller=Robolectric.buildService(PeygirMessagingService::class.java).create();val service=controller.get();val manager=c.getSystemService(NotificationManager::class.java);manager.cancelAll()
        fun message(company: String?)=RemoteMessage.Builder("sender").setData(buildMap{put("userId","same-user");put("eventId","same-event");put("recordId","record-1");put("kind","task");if(company!=null)put("company",company)}).build()
        try {
            service.onMessageReceived(message("company-b"));service.onMessageReceived(message(null));assertTrue(shadowOf(manager).allNotifications.isEmpty())
            service.onMessageReceived(message("company-a"));assertEquals(1,shadowOf(manager).allNotifications.size)
            manager.cancelAll();select("company-b");service.onMessageReceived(message("company-b"));assertEquals(1,shadowOf(manager).allNotifications.size)
        }finally{controller.destroy();c.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(c,null);manager.cancelAll()}
    }
    @Test fun genericPushIsAccountScopedAndDuplicateEventsDoNotReappearAfterDismissal() {
        val c=RuntimeEnvironment.getApplication()
        OrgAccess.set(c,SharedSession("https://test.invalid","","","","معاون","deputy","current-user"))
        c.getSharedPreferences("shared_private",0).edit().putString("session","test-only").putBoolean("alerts",true).commit()
        c.getSharedPreferences("push_private",0).edit().clear().commit()
        val controller=Robolectric.buildService(PeygirMessagingService::class.java).create();val service=controller.get()
        val manager=c.getSystemService(NotificationManager::class.java)
        fun message(user: String)=RemoteMessage.Builder("sender").setData(mapOf("userId" to user,"eventId" to "event-1","recordId" to "record-1","kind" to "task")).build()
        try {
            service.onMessageReceived(message("another-user"));assertTrue(shadowOf(manager).allNotifications.isEmpty())
            service.onMessageReceived(message("current-user"));assertEquals(1,shadowOf(manager).allNotifications.size)
            val n=shadowOf(manager).allNotifications.single();assertEquals("مصوبهٔ سازمانی به‌روز شد",n.extras.getString("android.title"));assertFalse(n.extras.toString().contains("another-user"))
            manager.cancelAll();service.onMessageReceived(message("current-user"));assertTrue(shadowOf(manager).allNotifications.isEmpty())
        } finally {controller.destroy();c.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(c,null)}
    }
}
