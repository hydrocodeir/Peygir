package ir.peygir.app

import android.app.*
import android.content.*
import com.google.firebase.messaging.RemoteMessage
import org.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class CompanyProfilesTest {
    private val c get()=RuntimeEnvironment.getApplication()
    private val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
    private val credentials: (Context)->SharedCredentials={SharedCredentials(it){key}}
    private fun session(company: String,user: String="same-user",role: String="manager",host: String="https://unit.invalid")=SharedSession(host,"private-$company-$user","workspace-$company","شرکت $company",user,role,user,company)
    private fun account(id: String,s: SharedSession): Context {
        val scoped=Accounts.context(c,id);credentials(scoped).save(s);OrgAccess.set(scoped,s);SharedStore(Store.get(scoped)).bind(s.endpoint,s.workspace,s.company);SharedStore(Store.get(scoped)).principal(s.userId);Accounts.register(c,id,s);return scoped
    }
    @Before fun setup(){c.getSharedPreferences("company_profiles",0).edit().clear().commit();c.getSharedPreferences("shared_private",0).edit().clear().commit();Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null);c.deleteDatabase("peygir.db")}
    @After fun cleanup(){Accounts.contexts(c).forEach{Store.get(it).close()};c.getSharedPreferences("company_profiles",0).edit().clear().commit();Organization.members.value=emptyList();Organization.contacts.value=emptyList()}
    @Test fun addAndReauthenticate() {
        val a=session("company-a");val b=session("company-b",role="deputy")
        SharedSync.connect(c,a,credentials);val first=Accounts.active(c)
        Store.get(c).saveTask(Resolution(title="کار آفلاین الف",owner="الف"),emptyList())
        SharedSync.connect(c,b,credentials);val second=Accounts.active(c)
        assertNotEquals(first,second);assertEquals(2,Accounts.list(c).size);assertTrue(Store.get(c).snapshot().tasks.isEmpty())
        SharedSync.connect(c,a,credentials);assertEquals(first,Accounts.active(c));assertEquals(2,Accounts.list(c).size)
        assertEquals("کار آفلاین الف",Store.get(c).snapshot().tasks.single().title)
        assertEquals(a.token,credentials(Accounts.context(c,first)).read()!!.token);assertEquals(b.token,credentials(Accounts.context(c,second)).read()!!.token)
    }
    @Test fun logoutOnlyOneCompany() {
        val a=account(UUID.randomUUID().toString(),session("company-a"));val b=account(UUID.randomUUID().toString(),session("company-b"))
        Store.get(a).saveTask(Resolution(title="الف",owner="الف"),emptyList());Store.get(b).saveTask(Resolution(title="ب",owner="ب"),emptyList());Accounts.select(c,Accounts.id(a))
        SharedSync.disconnect(a)
        assertNull(Accounts.profile(c,Accounts.id(a)));assertFalse(credentials(a).connected());assertTrue(Store.get(a).snapshot().tasks.isEmpty())
        assertEquals(Accounts.id(b),Accounts.active(c));assertTrue(credentials(b).connected());assertEquals("ب",Store.get(b).snapshot().tasks.single().title);assertEquals(1,SharedStore(Store.get(b)).counts().first)
    }
    @Test fun legacyMigrationKeepsQueue() {
        val s=session("old");credentials(c).save(s);OrgAccess.set(c,s)
        val store=Store.get(c);val task=store.saveTask(Resolution(title="ارسال‌نشده",owner="احمدی"),emptyList());SharedStore(store).bind(s.endpoint,s.workspace,s.company)
        c.getSharedPreferences("settings",0).edit().putString(DictationMode.PREFERENCE,"keyboard").commit()
        val queued=SharedStore(store).prepare().single().operation.toString()
        Accounts.initialize(c){credentials(it).read()}
        assertEquals(Accounts.LEGACY,Accounts.active(c));assertSame(store,Store.get(c));assertEquals(task,Store.get(c).snapshot().tasks.single().id)
        assertEquals(queued,SharedStore(Store.get(c)).prepare().single().operation.toString());assertEquals("keyboard",Accounts.context(c).getSharedPreferences("settings",0).getString(DictationMode.PREFERENCE,null))
        Accounts.initialize(c){credentials(it).read()};assertEquals(1,Accounts.list(c).size)
    }
    @Test fun databaseAndQueueIsolation() {
        val a=account(UUID.randomUUID().toString(),session("company-a"));val b=account(UUID.randomUUID().toString(),session("company-b",role="deputy"))
        val aStore=Store.get(a);val bStore=Store.get(b)
        aStore.saveTask(Resolution(title="فقط الف",owner="فرد الف"),emptyList());bStore.saveTask(Resolution(title="فقط ب",owner="فرد ب"),emptyList())
        assertEquals(1L,aStore.snapshot().tasks.single().id);assertEquals(1L,bStore.snapshot().tasks.single().id)
        val aOp=SharedStore(aStore).prepare().single().operation.toString()
        Accounts.select(c,Accounts.id(a));assertEquals("manager",OrgAccess.role(c));Accounts.select(c,Accounts.id(b));assertEquals("deputy",OrgAccess.role(c))
        assertEquals("فقط الف",aStore.snapshot().tasks.single().title);assertEquals("فقط ب",Store.get(c).snapshot().tasks.single().title)
        assertEquals(aOp,SharedStore(aStore).prepare().single().operation.toString());assertNotEquals(credentials(a).read()!!.token,credentials(b).read()!!.token)
        assertTrue(Organization.contactNames(aStore).contains("فرد الف"));assertFalse(Organization.contactNames(bStore).contains("فرد الف"))
        assertNotEquals(a.filesDir,b.filesDir);assertEquals(Accounts.id(a),Accounts.id(a.applicationContext))
    }
    @Test fun switchDuringBackgroundSync() {
        val a=account(UUID.randomUUID().toString(),session("company-a"));val b=account(UUID.randomUUID().toString(),session("company-b"));Accounts.select(c,Accounts.id(a))
        val arrived=CountDownLatch(1);val resume=CountDownLatch(1)
        val transport=SharedTransport {_,_->arrived.countDown();assertTrue(resume.await(10,TimeUnit.SECONDS));JSONObject().put("results",JSONArray()).put("changes",JSONArray()).put("cursor",0).put("hasMore",false).put("protocol",2).put("users",JSONArray().put(JSONObject().put("id","a-only").put("name","فرد الف").put("role","deputy").put("active",1)))}
        var ok=false;val thread=Thread{ok=SharedSync.perform(a,credentials){transport}};thread.start();assertTrue(arrived.await(10,TimeUnit.SECONDS))
        Accounts.select(c,Accounts.id(b))
        val fast=SharedTransport{_,_->JSONObject().put("results",JSONArray()).put("changes",JSONArray()).put("cursor",0).put("hasMore",false).put("protocol",2)}
        assertTrue("A delayed server must not hold the other account's sync lock",SharedSync.perform(b,credentials){fast})
        // FCM delivery must not wait on the same account's potentially slow network request.
        val controller=Robolectric.buildService(PeygirMessagingService::class.java).create()
        try {
            controller.get().onMessageReceived(RemoteMessage.Builder("sender").setData(mapOf("userId" to "same-user","company" to "company-a","accountRoute" to Accounts.route(a),"kind" to "assignment","eventId" to "during-sync","recordId" to "record")).build())
            assertTrue(shadowOf(c.getSystemService(NotificationManager::class.java)).allNotifications.any{it.extras.getString("android.title").orEmpty().startsWith("شرکت company-a - مصوبه")})
        } finally {controller.destroy()}
        resume.countDown();thread.join(10000);assertFalse(thread.isAlive);assertTrue(ok)
        assertEquals(Accounts.id(b),Accounts.active(c));assertTrue(Organization.members.value.isEmpty());assertEquals("a-only",Organization.users(Store.get(a)).single().id)
        assertTrue(Organization.users(Store.get(b)).isEmpty())
        Store.get(a).publishPeople(listOf(CompanyUser("late-person","دیررس","deputy")),listOf("مخاطب دیررس"))
        assertTrue(Organization.members.value.isEmpty());assertFalse(Organization.contacts.value.contains("مخاطب دیررس"))
    }
    @Test fun revokeOneCompany() {
        val a=account(UUID.randomUUID().toString(),session("company-a"));val b=account(UUID.randomUUID().toString(),session("company-b"));Accounts.select(c,Accounts.id(b))
        Store.get(a).saveTask(Resolution(title="الف",owner="الف"),emptyList());Store.get(b).saveTask(Resolution(title="ب",owner="ب"),emptyList())
        assertFalse(SharedSync.perform(a,credentials){SharedTransport{_,_->throw SharedHttpError(401,"حساب غیرفعال است")}})
        assertTrue(Store.get(a).snapshot().tasks.isEmpty());assertEquals("ب",Store.get(b).snapshot().tasks.single().title);assertTrue(credentials(b).connected());assertEquals(Accounts.id(b),Accounts.active(c))
    }
    @Test fun overlappingServerRoutes() {
        val a=account(UUID.randomUUID().toString(),session("same"));val b=account(UUID.randomUUID().toString(),session("same",host="https://another.invalid"))
        val base=mapOf("userId" to "same-user","company" to "same")
        assertNull(Accounts.pushTarget(c,base,null));assertEquals(Accounts.id(b),Accounts.id(Accounts.pushTarget(c,base+mapOf("accountRoute" to Accounts.route(b)),null)!!))
        assertNull(Accounts.pushTarget(c,base+mapOf("accountRoute" to "unknown"),null))
        a.getSharedPreferences("push_private",0).edit().putString("sender","one").commit();b.getSharedPreferences("push_private",0).edit().putString("sender","two").commit()
        assertNull(Accounts.pushTarget(c,base+mapOf("accountRoute" to Accounts.route(a)),"two"))
    }
    @Test fun inactiveCompanyPush() {
        val a=account(UUID.randomUUID().toString(),session("company-a"));val b=account(UUID.randomUUID().toString(),session("company-b"));Accounts.select(c,Accounts.id(a));credentials(b).alerts(true)
        val controller=Robolectric.buildService(PeygirMessagingService::class.java).create();val manager=c.getSystemService(NotificationManager::class.java);manager.cancelAll()
        val message=RemoteMessage.Builder("sender").setData(mapOf("userId" to "same-user","company" to "company-b","accountRoute" to Accounts.route(b),"kind" to "assignment","eventId" to "same-event","recordId" to "record")).build()
        try {
            controller.get().onMessageReceived(message)
            val n=shadowOf(manager).allNotifications.single();assertTrue(n.extras.getString("android.title")!!.startsWith("شرکت company-b -"))
            assertEquals(Accounts.id(b),shadowOf(n.contentIntent).savedIntent.getStringExtra(Accounts.EXTRA));assertEquals(1,Accounts.unread(c,Accounts.id(b)))
            controller.get().onMessageReceived(message);assertEquals(1,shadowOf(manager).allNotifications.size);assertEquals(1,Accounts.unread(c,Accounts.id(b)))
            Accounts.cancelNotifications(a);assertEquals(1,shadowOf(manager).allNotifications.size)
        }finally{controller.destroy();manager.cancelAll()}
    }
    @Test fun reminderActionIsolation() {
        val a=account(UUID.randomUUID().toString(),session("company-a"));val b=account(UUID.randomUUID().toString(),session("company-b"));Accounts.select(c,Accounts.id(b))
        for(ctx in listOf(a,b)) {ctx.getSharedPreferences("settings",0).edit().putBoolean("daily",false).commit();Store.get(ctx).saveTask(Resolution(title="کار",owner="مسئول"),emptyList())}
        assertTrue(Store.get(a).activateNotification(1,"unique-action-token",emptyList()))
        val intent=Accounts.intent(a,Intent(c,ReminderActionReceiver::class.java)).setAction(ReminderActionReceiver.DONE).putExtra("task_id",1L).putExtra("token","unique-action-token")
        assertTrue(Reminders.handleAction(c,intent));assertEquals(Status.DONE,Store.get(a).snapshot().tasks.single().status);assertEquals(Status.WAITING,Store.get(b).snapshot().tasks.single().status);assertEquals(Accounts.id(b),Accounts.active(c))
        assertFalse(Reminders.handleAction(c,Intent(c,ReminderActionReceiver::class.java).setAction(ReminderActionReceiver.DONE).putExtra("task_id",1L).putExtra("token","unique-action-token")))
    }
    @Test fun pinnedWidgetIsolation() {
        val a=account(UUID.randomUUID().toString(),session("company-a"));val b=account(UUID.randomUUID().toString(),session("company-b"));Accounts.select(c,Accounts.id(a))
        WidgetAccounts.bind(c,71,Accounts.id(a));WidgetAccounts.bind(c,72,Accounts.id(b));Accounts.select(c,Accounts.id(b))
        assertEquals(Accounts.id(a),Accounts.id(WidgetAccounts.context(c,71)!!));assertEquals(Accounts.id(b),Accounts.id(WidgetAccounts.context(c,72)!!))
        Accounts.remove(c,Accounts.id(a));assertNull(WidgetAccounts.context(c,71));assertEquals(Accounts.id(b),Accounts.id(WidgetAccounts.context(c,72)!!))
        assertNull(Accounts.fromIntent(c,Accounts.intent(a,Intent())))
    }
}
