package ir.peygir.app

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.*
import java.time.LocalDate
import java.util.zip.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class BackupTest {
    private lateinit var context: Context
    private lateinit var store: Store
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db");store=Store.get(context)
        context.getSharedPreferences("settings",0).edit().clear().commit()
        File(context.filesDir,"recordings").deleteRecursively()
    }
    @After fun close() {store.close()}
    private fun seedLegacyBacklog() {store.writableDatabase.execSQL("INSERT INTO backlog(title,description,owner,created) VALUES(?,?,?,?)",arrayOf<Any>("legacy entry","legacy note","",1L))}
    private fun export(): ByteArray=ByteArrayOutputStream().also {Backup.write(context,it)}.toByteArray()
    private fun archive(change: (JSONObject)->Unit = {}, extra: Pair<String,ByteArray>? = null): ByteArray {
        val entries=linkedMapOf<String,ByteArray>()
        ZipInputStream(export().inputStream()).use {z->while(true) {val e=z.nextEntry ?: break;entries[e.name]=z.readBytes()}}
        val json=JSONObject(entries.getValue("peygir.json").toString(Charsets.UTF_8));change(json)
        entries["peygir.json"]=json.toString().toByteArray()
        extra?.let {entries[it.first]=it.second}
        return ByteArrayOutputStream().also {out->ZipOutputStream(out).use {z->entries.forEach {(name,bytes)->z.putNextEntry(ZipEntry(name));z.write(bytes);z.closeEntry()}}}.toByteArray()
    }
    @Test fun roundTripPreservesAllDataAndSettingsAndRehomesAudio() {
        val meeting=store.saveMeeting(Meeting(title="مدیریت",notes="صورت‌جلسه"))
        val day=LocalDate.now().plusDays(5).toEpochDay()
        val id=store.saveTask(Resolution(meetingId=meeting,title="گزارش",owner="احمدی",due=day),listOf(System.currentTimeMillis()+600000))
        store.delivered(store.snapshot().reminders.map{it.id})
        store.addFollowUp(id,"منتظر تأیید مالی است")
        seedLegacyBacklog()
        store.saveTask(store.snapshot().tasks.single().copy(due=day+2,status=Status.ACTIVE),store.snapshot().reminders.map{it.at},DueChangeKind.EXTEND,"تمدید آزمایشی")
        val directory=File(context.filesDir,"recordings").apply{mkdirs()}
        val audio=File(directory,"archive.wav").apply{writeBytes(AudioCapture.wavHeader(160)+ByteArray(160))}
        val audioBytes=audio.readBytes()
        store.saveRecording(Recording(meetingId=meeting,path=audio.path,created=System.currentTimeMillis(),seconds=2,transcript="متن آرشیوی"))
        context.getSharedPreferences("settings",0).edit().putString("theme","dark").putBoolean("dynamic_colors",true).putBoolean("daily",false).putString("times","10:05").commit()
        val expected=store.snapshot();val bytes=export()
        store.saveTask(Resolution(title="دادهٔ بعد از پشتیبان",owner="رضایی"),emptyList())
        context.getSharedPreferences("settings",0).edit().clear().commit()
        Backup.read(context,bytes.inputStream()).use {prepared->Backup.restore(context,prepared)}
        val actual=store.snapshot()
        assertEquals(expected.copy(recordings=actual.recordings),actual)
        assertEquals(expected.recordings.single().copy(path=actual.recordings.single().path),actual.recordings.single())
        assertArrayEquals(audioBytes,File(actual.recordings.single().path).readBytes());assertFalse(audio.exists())
        assertTrue(context.getSharedPreferences("settings",0).getBoolean("dynamic_colors",false))
        assertEquals("dark",context.getSharedPreferences("settings",0).getString("theme",null))
        assertEquals(listOf(java.time.LocalTime.of(10,5)),Reminders.dailyTimes(context));assertFalse(Reminders.dailyEnabled(context))
        assertTrue(store.saveTask(Resolution(title="بعد از بازیابی",owner="احمدی"),emptyList())>id)
    }
    @Test fun draftsCredentialsAndModelsAreExcluded() {
        val audio=File(File(context.filesDir,"recordings").apply{mkdirs()},"draft.wav").apply{writeText("private draft")}
        store.stageRecording(Recording(path=audio.path,created=1,seconds=1),"draft-session")
        context.getSharedPreferences("settings",0).edit().putString("api_key","SECRET-DO-NOT-EXPORT").putLong("last_review",123).commit()
        ZipInputStream(export().inputStream()).use {z->assertEquals("peygir.json",z.nextEntry.name);val json=z.readBytes().toString(Charsets.UTF_8);assertFalse(json.contains("SECRET"));assertFalse(json.contains("last_review"));assertEquals(0,JSONObject(json).getJSONArray("recordings").length());assertNull(z.nextEntry)}
    }
    @Test fun malformedOrFutureArchiveCannotMutateExistingData() {
        store.saveMeeting(Meeting(title="اصل"));val before=store.snapshot()
        assertThrows(Exception::class.java) {Backup.read(context,"garbage".byteInputStream())}
        assertThrows(Exception::class.java) {Backup.read(context,archive({it.put("version",999)}).inputStream())}
        assertEquals(before,store.snapshot())
    }
    @Test fun zipTraversalAndUnexpectedFilesAreRejectedAndStagingIsCleaned() {
        val directories=context.cacheDir.listFiles().orEmpty().filter{it.name.startsWith("restore-")}.map{it.name}.toSet()
        assertThrows(Exception::class.java) {Backup.read(context,archive(extra="../escape.wav" to byteArrayOf(1)).inputStream())}
        assertThrows(Exception::class.java) {Backup.read(context,archive(extra="audio/9.wav" to byteArrayOf(1)).inputStream())}
        assertEquals(directories,context.cacheDir.listFiles().orEmpty().filter{it.name.startsWith("restore-")}.map{it.name}.toSet())
    }
    @Test fun duplicateIdsAndBrokenRelationshipsAreRejectedBeforeRestore() {
        store.saveTask(Resolution(title="اصل",owner="احمدی"),emptyList());val before=store.snapshot()
        assertThrows(Exception::class.java) {Backup.read(context,archive({j->val a=j.getJSONArray("tasks");a.put(a.getJSONObject(0))}).inputStream())}
        assertThrows(Exception::class.java) {Backup.read(context,archive({it.getJSONArray("tasks").getJSONObject(0).put("meeting",999)}).inputStream())}
        assertEquals(before,store.snapshot())
    }
    @Test fun audioCorruptionIsRejected() {
        val file=File(File(context.filesDir,"recordings").apply{mkdirs()},"source.wav").apply{writeBytes(byteArrayOf(1,2,3))}
        val id=store.saveRecording(Recording(path=file.path,created=1,seconds=1))
        assertThrows(Exception::class.java) {Backup.read(context,archive(extra="audio/$id.wav" to byteArrayOf(9)).inputStream())}
        assertTrue(file.exists())
    }
    @Test fun failedDatabaseReplaceRollsBackAndRetainsOriginalAudioAndSettings() {
        val file=File(File(context.filesDir,"recordings").apply{mkdirs()},"source.wav").apply{writeText("audio")}
        store.saveRecording(Recording(path=file.path,created=1,seconds=1))
        store.saveTask(Resolution(title="اصل",owner="احمدی"),emptyList())
        val before=store.snapshot()
        context.getSharedPreferences("settings",0).edit().putLong("last_review",123).commit()
        Backup.read(context,export().inputStream()).use {prepared->
            val invalid=Backup.Prepared(prepared.data.copy(tasks=prepared.data.tasks.map{it.copy(meetingId=999)}),prepared.settings,prepared.directory)
            assertThrows(Exception::class.java) {Backup.restore(context,invalid)}
        }
        assertEquals(before,store.snapshot());assertTrue(file.exists())
        assertEquals(listOf("source.wav"),file.parentFile!!.listFiles()!!.map{it.name})
        assertEquals(123,context.getSharedPreferences("settings",0).getLong("last_review",0))
    }
    @Test fun failedCommitCallbackRollsBackTheDatabaseTransaction() {
        store.saveMeeting(Meeting(title="اصل"));val before=store.snapshot()
        assertThrows(Exception::class.java) {store.replaceAll(Snapshot()) {error("simulated failure")}}
        assertEquals(before,store.snapshot())
    }
    @Test fun versionOneBackupCanStillBeRestoredWithoutFollowUps() {
        val task=store.saveTask(Resolution(title="نسخهٔ قبلی",owner="احمدی"),emptyList())
        store.addFollowUp(task,"یادداشت تازه")
        val bytes=archive({it.put("version",1);it.remove("followUps")})
        Backup.read(context,bytes.inputStream()).use {prepared->
            assertTrue(prepared.data.followUps.isEmpty());Backup.restore(context,prepared)
        }
        assertEquals("نسخهٔ قبلی",store.snapshot().tasks.single().title)
        assertTrue(store.snapshot().followUps.isEmpty())
    }
    @Test fun versionTwoBackupCanStillBeReadWithoutBacklog() {
        store.saveTask(Resolution(title="قدیمی",owner="احمدی"),emptyList())
        val bytes=archive({it.put("version",2);it.remove("backlog")})
        Backup.read(context,bytes.inputStream()).use {prepared->
            assertTrue(prepared.data.backlog.isEmpty());assertEquals("قدیمی",prepared.data.tasks.single().title)
        }
    }
    @Test fun invalidBacklogIdsAndBlankTitlesAreRejected() {
        seedLegacyBacklog()
        assertThrows(Exception::class.java) {Backup.read(context,archive({it.getJSONArray("backlog").getJSONObject(0).put("title","")}).inputStream())}
        assertThrows(Exception::class.java) {Backup.read(context,archive({it.getJSONArray("backlog").getJSONObject(0).put("id",0)}).inputStream())}
    }
    @Test fun followUpRelationshipsAreCheckedAndOperationalTokensAreNotExported() {
        val task=store.saveTask(Resolution(title="پیگیری",owner="احمدی"),emptyList())
        store.addFollowUp(task,"نتیجه");store.activateNotification(task,"SECRET-ACTION-TOKEN",emptyList())
        assertThrows(Exception::class.java) {Backup.read(context,archive({it.getJSONArray("followUps").getJSONObject(0).put("task",999)}).inputStream())}
        ZipInputStream(export().inputStream()).use {z->z.nextEntry;val json=z.readBytes().toString(Charsets.UTF_8);assertFalse(json.contains("SECRET-ACTION-TOKEN"));assertEquals(5,JSONObject(json).getInt("version"))}
        Backup.read(context,export().inputStream()).use {Backup.restore(context,it)}
        assertFalse(store.notificationAction(task,"SECRET-ACTION-TOKEN",false))
        assertEquals("نتیجه",store.snapshot().followUps.single().text)
    }
}
