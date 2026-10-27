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
import java.io.IOException
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class SharedStoreTest {
    private lateinit var a: Store;private lateinit var b: Store
    private lateinit var sa: SharedStore;private lateinit var sb: SharedStore
    private val cloud=MemoryCloud()
    @Before fun setup() {
        val c=RuntimeEnvironment.getApplication();c.deleteDatabase("sync-a.db");c.deleteDatabase("sync-b.db")
        a=Store(c,"sync-a.db");b=Store(c,"sync-b.db");sa=SharedStore(a);sb=SharedStore(b)
        val workspace=UUID.randomUUID().toString();sa.bind("https://sync.example.test",workspace);sb.bind("https://sync.example.test",workspace)
    }
    @After fun close() {a.close();b.close()}
    @Test fun oneServerAndWorkspaceCannotBeReboundToAnotherCompanyWithoutExplicitDetachment() {
        val context=RuntimeEnvironment.getApplication();context.deleteDatabase("company-binding.db")
        val store=Store(context,"company-binding.db")
        try {
            val shared=SharedStore(store);store.saveTask(Resolution(title="متعلق به شرکت اول",owner="احمدی"),emptyList())
            shared.bind("https://peygir.test","same-workspace","company-a")
            assertEquals(sharedBinding("https://peygir.test","same-workspace","company-a"),shared.binding())
            assertThrows(IllegalStateException::class.java){shared.bind("https://peygir.test","same-workspace","company-b")}
            assertEquals("متعلق به شرکت اول",store.snapshot().tasks.single().title)
            shared.clearScopedData(detachBinding=true)
            assertTrue(shared.binding().isEmpty());assertTrue(store.snapshot().tasks.isEmpty())
            shared.bind("https://peygir.test","same-workspace","company-b")
            assertEquals(sharedBinding("https://peygir.test","same-workspace","company-b"),shared.binding())
            assertEquals("https://peygir.test|legacy",sharedBinding("https://peygir.test","legacy"))
        }finally{store.close()}
    }
    private fun send(s: SharedStore)=SyncEngine.exchange(s,cloud)
    private fun task(owner: String="احمدی")=a.saveTask(Resolution(title="گزارش فروش",owner=owner,due=LocalDate.now().plusDays(3).toEpochDay()),listOf(System.currentTimeMillis()+3600000))
    @Test fun twoDatabasesExchangeMeetingTasksStatusFollowupAndDueHistoryWithDifferentLocalIds() {
        val temporary=b.saveMeeting(Meeting(title="temporary"));b.deleteMeeting(temporary)
        val meeting=a.saveMeeting(Meeting(title="شورای مدیریت",notes="جلسهٔ آزمایشی"))
        a.saveTask(Resolution(title="گزارش فروش",owner="علی احمدی",meetingId=meeting),listOf(System.currentTimeMillis()+3600000))
        send(sa);send(sb)
        val received=b.snapshot();assertEquals("شورای مدیریت",received.meetings.single().title)
        assertNotEquals(meeting,received.meetings.single().id);assertEquals(received.meetings.single().id,received.tasks.single().meetingId)
        b.status(received.tasks.single().id,Status.ACTIVE);b.addFollowUp(received.tasks.single().id,"منتظر تأیید مالی است")
        send(sb);send(sa)
        assertEquals(Status.ACTIVE,a.snapshot().tasks.single().status);assertEquals("منتظر تأیید مالی است",a.snapshot().followUps.single().text)
        val t=b.snapshot().tasks.single();b.saveTask(t.copy(due=t.due+4),b.snapshot().reminders.map{it.at},DueChangeKind.EXTEND,"تمدید با مدیر")
        send(sb);send(sa)
        assertEquals(b.snapshot().tasks.single().due,a.snapshot().tasks.single().due)
        assertEquals("تمدید با مدیر",a.snapshot().history.single().reason);assertEquals(1,a.snapshot().tasks.single().delayCount)
    }
    @Test fun reminderDeliveryStaysLocalWhileSnoozeTimeIsShared() {
        task();send(sa);send(sb)
        b.delivered(b.snapshot().reminders.map{it.id});assertEquals(0,sb.counts().first)
        assertFalse(a.snapshot().reminders.single().delivered)
        val t=b.snapshot().tasks.single();val next=System.currentTimeMillis()+7200000
        b.saveTask(t,b.snapshot().reminders.map{it.at}+next);send(sb);send(sa)
        assertEquals(2,a.snapshot().reminders.size);assertFalse(a.snapshot().reminders.any{it.delivered})
        assertTrue(b.snapshot().reminders.first{it.at!=next}.delivered)
    }
    @Test fun offlineChangesSurviveReopenAndLostNetworkReceiptDoesNotDuplicateRecords() {
        task();cloud.loseResponse=true
        assertThrows(IOException::class.java){send(sa)}
        assertEquals(1,sa.counts().first)
        a.close();a=Store(RuntimeEnvironment.getApplication(),"sync-a.db");sa=SharedStore(a)
        send(sa);send(sb)
        assertEquals(1,cloud.records.size);assertEquals(1,b.snapshot().tasks.size);assertEquals(0,sa.counts().first)
    }
    @Test fun editsDuringAnInflightUploadAreNotOverwrittenByItsAcknowledgement() {
        task();cloud.afterCommit={a.status(a.snapshot().tasks.single().id,Status.DONE)}
        send(sa);send(sb)
        assertEquals(Status.DONE,a.snapshot().tasks.single().status);assertEquals(Status.DONE,b.snapshot().tasks.single().status)
    }
    @Test fun editsToDifferentFieldsMergeAndSameFieldConflictCanBeResolvedEitherWay() {
        task();send(sa);send(sb)
        a.saveTask(a.snapshot().tasks.single().copy(title="عنوان تازه"),a.snapshot().reminders.map{it.at})
        b.status(b.snapshot().tasks.single().id,Status.ACTIVE)
        send(sa);send(sb);send(sa)
        assertEquals("عنوان تازه",b.snapshot().tasks.single().title);assertEquals(Status.ACTIVE,a.snapshot().tasks.single().status)
        a.saveTask(a.snapshot().tasks.single().copy(owner="رضایی"),a.snapshot().reminders.map{it.at})
        b.saveTask(b.snapshot().tasks.single().copy(owner="محمدی"),b.snapshot().reminders.map{it.at})
        send(sa);send(sb)
        assertEquals(1,sb.conflicts().size);assertEquals("محمدی",b.snapshot().tasks.single().owner)
        val conflict=sb.conflicts().single();sb.resolve(conflict.kind,conflict.localId,true);send(sb);send(sa)
        assertEquals("محمدی",a.snapshot().tasks.single().owner);assertTrue(sb.conflicts().isEmpty())
        a.status(a.snapshot().tasks.single().id,Status.DONE);b.status(b.snapshot().tasks.single().id,Status.WAITING)
        send(sa);send(sb)
        val second=sb.conflicts().single();sb.resolve(second.kind,second.localId,false)
        assertEquals(Status.DONE,b.snapshot().tasks.single().status);assertEquals(0,sb.counts().first)
    }
    @Test fun meetingDeletionKeepsTasksAndTaskDeletionDoesNotResurrect() {
        val mid=a.saveMeeting(Meeting(title="جلسه"));a.saveTask(Resolution(title="کار",owner="احمدی",meetingId=mid),emptyList())
        send(sa);send(sb);a.deleteMeeting(mid);send(sa);send(sb)
        assertTrue(b.snapshot().meetings.isEmpty());assertNull(b.snapshot().tasks.single().meetingId)
        b.addFollowUp(b.snapshot().tasks.single().id,"یادداشت ارسال‌شده");send(sb);send(sa)
        a.deleteTask(a.snapshot().tasks.single().id);send(sa);send(sb);send(sa)
        assertTrue(b.snapshot().tasks.isEmpty());assertTrue(b.snapshot().followUps.isEmpty());assertEquals(0,sb.counts().first)
    }
    @Test fun changingWorkspaceOrRestoringBackupRequiresExplicitDetach() {
        task();send(sa)
        assertThrows(IllegalStateException::class.java){sa.bind("https://another.example.test",UUID.randomUUID().toString())}
        assertThrows(IllegalStateException::class.java){a.replaceAll(Snapshot())}
        sa.detach();assertEquals(1,a.snapshot().tasks.size);a.replaceAll(Snapshot());assertTrue(a.snapshot().tasks.isEmpty())
    }
    @Test fun schemaFourMigrationKeepsDataAndQueuesExistingRowsOnlyWhenConnected() {
        sa.detach();task();a.addFollowUp(a.snapshot().tasks.single().id,"یادداشت قبل از ارتقا")
        val before=a.snapshot();val db=a.writableDatabase
        val triggers=db.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger' AND name LIKE 'sync_%'",null).use{c->buildList{while(c.moveToNext()) add(c.getString(0))}}
        triggers.forEach{db.execSQL("DROP TRIGGER $it")};listOf("sync_dirty","sync_objects","sync_meta").forEach{db.execSQL("DROP TABLE $it")};db.version=4;a.close()
        a=Store(RuntimeEnvironment.getApplication(),"sync-a.db");sa=SharedStore(a)
        assertEquals(6,a.readableDatabase.version);assertEquals(before,a.snapshot())
        sa.bind("https://sync.example.test",UUID.randomUUID().toString());assertEquals(2,sa.prepare().size)
    }
    @Test fun reminderSetsMergeWithoutLosingEitherPhonesNewTimes() {
        val base=JSONObject().put("reminders",JSONArray(listOf(1L,2L)))
        val local=JSONObject().put("reminders",JSONArray(listOf(1L,3L)))
        val remote=JSONObject().put("reminders",JSONArray(listOf(1L,2L,4L)))
        assertEquals("[1,3,4]",mergeJson(base,local,remote)!!.getJSONArray("reminders").toString())
    }
    @Test fun unsubmittedFollowupSurvivesAConcurrentRemoteTaskDeletionUntilTheUserChooses() {
        task();send(sa);send(sb)
        val t=b.snapshot().tasks.single();b.addFollowUp(t.id,"یادداشت ارسال‌نشده")
        val remote=JSONObject(cloud.records.values.single().toString()).put("revision",2).put("deleted",true)
        sb.receive(remote)
        assertEquals(1,b.snapshot().tasks.size);assertEquals("یادداشت ارسال‌نشده",b.snapshot().followUps.single().text)
        val conflict=sb.conflicts().single();assertEquals("task",conflict.kind)
        sb.resolve(conflict.kind,conflict.localId,false)
        assertTrue(b.snapshot().tasks.isEmpty());assertTrue(b.snapshot().followUps.isEmpty());assertEquals(0,sb.counts().first)
    }
    @Test fun synchronizationDoesNotInvalidateAnActiveNotificationUnlessItsDueOrCompletionChanged() {
        val id=task();send(sa);send(sb);a.activateNotification(id,"token-before-sync",emptyList())
        a.saveTask(a.snapshot().tasks.single().copy(title="عنوان تازه"),a.snapshot().reminders.map{it.at});send(sa)
        assertTrue(a.notificationAction(id,"token-before-sync",true))
        a.activateNotification(id,"token-before-due-change",emptyList())
        val t=b.snapshot().tasks.single();b.saveTask(t.copy(due=t.due+2),b.snapshot().reminders.map{it.at});send(sb);send(sa)
        assertFalse(a.notificationAction(id,"token-before-due-change",true))
    }
    private class MemoryCloud: SharedTransport {
        val records=linkedMapOf<String,JSONObject>();private val receipts=mutableMapOf<String,JSONObject>();private val events=mutableListOf<JSONObject>()
        var loseResponse=false;var afterCommit: (()->Unit)?=null
        override fun post(path: String,body: JSONObject): JSONObject {
            val operations=body.getJSONArray("operations");val results=JSONArray()
            for(i in 0 until operations.length()) {
                val op=operations.getJSONObject(i);val operation=op.getString("id");val id=op.getString("recordId");val previous=records[id]
                val result=receipts[operation] ?: if((previous?.getLong("revision") ?: 0)==op.getLong("baseRevision")) {
                    val r=JSONObject().put("id",id).put("kind",op.getString("kind")).put("revision",op.getLong("baseRevision")+1).put("payload",op.getJSONObject("payload")).put("deleted",op.getBoolean("deleted")).put("actor","test").put("changed",System.currentTimeMillis())
                    records[id]=r;events+=JSONObject(r.toString()).put("seq",events.size+1)
                    JSONObject().put("opId",operation).put("ok",true).put("record",r).also {receipts[operation]=it}
                } else JSONObject().put("opId",operation).put("ok",false).put("record",previous)
                results.put(result)
            }
            afterCommit?.also{afterCommit=null;it()}
            if(loseResponse) {loseResponse=false;throw IOException("lost response after commit")}
            val cursor=body.getLong("cursor");val changes=events.filter{it.getLong("seq")>cursor}
            return JSONObject().put("results",results).put("changes",JSONArray(changes)).put("cursor",events.size).put("hasMore",false)
        }
    }
}
