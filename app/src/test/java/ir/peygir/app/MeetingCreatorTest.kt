package ir.peygir.app

import android.app.Application
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class MeetingCreatorTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private lateinit var store: Store
    @Before fun setup() {context.deleteDatabase("meeting-creator.db");store=Store(context,"meeting-creator.db")}
    @After fun close() {store.close()}
    @Test fun localCreatorIsImmutableAndCopiesAreRegisteredAsNewMeetings() {
        val id=store.saveMeeting(Meeting(title="ثبت اولیه",creator="نام جعلی"))
        val original=store.snapshot().meetings.single()
        assertEquals("ثبت محلی در این گوشی",original.creator)
        store.saveMeeting(original.copy(title="ویرایش",creator="ویرایش‌کننده"))
        assertEquals(original.creator,store.snapshot().meetings.single().creator)
        val copy=MeetingCopies.draft(original)
        assertNull(copy.creator)
        assertNull(MeetingCopies.decode(MeetingCopies.encode(copy)).creator)
        store.saveMeeting(copy)
        assertTrue(store.snapshot().meetings.all{it.creator==original.creator})
        assertEquals(id,store.snapshot().meetings.first{it.title=="ویرایش"}.id)
    }
    @Test fun serverCreatorIsReadOnlySurvivesInflightEditsAndOldServerResponses() {
        val id=store.saveMeeting(Meeting(title="جلسهٔ مشترک"))
        val shared=SharedStore(store);shared.bind("https://unit.invalid",UUID.randomUUID().toString())
        val pending=shared.prepare().single()
        assertFalse(pending.operation.getJSONObject("payload").has("creator"))
        val remote=JSONObject().put("id",pending.operation.getString("recordId")).put("kind","meeting").put("revision",1).put("deleted",false)
            .put("actor","رئیس").put("changed",1).put("payload",JSONObject(pending.operation.getJSONObject("payload").toString()).put("creator","ثبت‌کنندهٔ اولیه"))
        store.saveMeeting(store.snapshot().meetings.single().copy(notes="ویرایش در حال ارسال"))
        shared.receive(remote,pending,true)
        assertEquals("ثبت‌کنندهٔ اولیه",store.snapshot().meetings.single().creator)
        assertEquals("ویرایش در حال ارسال",store.snapshot().meetings.single().notes)
        val next=shared.prepare().single()
        assertFalse(next.operation.getJSONObject("payload").has("creator"))
        val legacy=JSONObject(remote.toString()).put("revision",2).put("payload",JSONObject(next.operation.getJSONObject("payload").toString()))
        shared.receive(legacy,next,true)
        assertEquals("ثبت‌کنندهٔ اولیه",store.snapshot().meetings.single().creator)
        assertEquals(0,shared.counts().first)
        store.saveMeeting(store.snapshot().meetings.single().copy(alertMinutes=15))
        assertEquals("Local reminder override must not dirty server-owned creator",0,shared.counts().first)
        assertEquals(id,store.snapshot().meetings.single().id)
    }
    @Test fun backupAndVersionNineUpgradePreserveNamesWithoutGuessingOldCreators() {
        store.saveMeeting(Meeting(title="از پشتیبان"))
        store.writableDatabase.execSQL("UPDATE meetings SET creator=?",arrayOf("خانم رضایی"))
        val bytes=ByteArrayOutputStream().also{Backup.write(context,it,store)}.toByteArray()
        Backup.read(context,bytes.inputStream()).use{prepared->
            assertEquals("خانم رضایی",prepared.data.meetings.single().creator)
            Backup.restore(context,prepared,store)
        }
        assertEquals("خانم رضایی",store.snapshot().meetings.single().creator)
        // Recreate an actual version-nine schema without the new column.
        val db=store.writableDatabase
        val schema=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='meetings'",null).use{it.moveToFirst();it.getString(0)}
        val triggers=db.rawQuery("SELECT sql FROM sqlite_master WHERE type='trigger' AND tbl_name='meetings'",null).use{c->buildList{while(c.moveToNext()) add(c.getString(0))}}
        val columns=db.rawQuery("PRAGMA table_info(meetings)",null).use{c->buildList{while(c.moveToNext()) if(c.getString(1)!="creator") add(c.getString(1))}}.joinToString(",")
        db.execSQL("PRAGMA foreign_keys=OFF")
        db.execSQL(schema.replaceFirst("CREATE TABLE meetings","CREATE TABLE meetings_v9").replace(Regex(",\\s*creator\\s+TEXT"),""))
        db.execSQL("INSERT INTO meetings_v9($columns) SELECT $columns FROM meetings")
        db.execSQL("DROP TABLE meetings")
        db.execSQL("ALTER TABLE meetings_v9 RENAME TO meetings")
        triggers.forEach{db.execSQL(it)}
        db.execSQL("PRAGMA foreign_keys=ON")
        assertFalse(db.rawQuery("PRAGMA table_info(meetings)",null).use{c->var found=false;while(c.moveToNext()) if(c.getString(1)=="creator") found=true;found})
        store.writableDatabase.version=9;store.close()
        store=Store(context,"meeting-creator.db")
        assertEquals(10,store.readableDatabase.version)
        assertNull(store.snapshot().meetings.single().creator)
        store.saveMeeting(store.snapshot().meetings.single().copy(title="ویرایش جلسهٔ قدیمی"))
        assertNull(store.snapshot().meetings.single().creator)
        assertThrows(IllegalArgumentException::class.java){MeetingCreator.read(JSONObject().put("creator",123))}
    }
}
