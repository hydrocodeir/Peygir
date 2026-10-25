package ir.peygir.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Own temporary database, audio directory and preferences; never restores the user's database. */
class DeviceReleaseTest {
    private fun isolated(block: (Context,Store)->Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val token="device-release-${UUID.randomUUID()}"
        val directory=File(base.cacheDir,token).apply{mkdirs()}
        val context=object: ContextWrapper(base) {
            override fun getApplicationContext(): Context=this
            override fun getFilesDir()=File(directory,"files").apply{mkdirs()}
            override fun getCacheDir()=File(directory,"cache").apply{mkdirs()}
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences=base.getSharedPreferences("$token-$name",mode)
        }
        val store=Store(context,"$token.db")
        try {block(context,store)} finally {
            store.close();base.deleteDatabase("$token.db")
            listOf("settings","drafts","review_widgets").forEach{base.deleteSharedPreferences("$token-$it")}
            directory.deleteRecursively()
        }
    }
    @Test fun backupRoundTripUsesRealSqliteZipAndPrivateAudioStorage()=isolated {context,store->
        val meeting=store.saveMeeting(Meeting(title="جلسهٔ آزمون دستگاه"))
        val day=LocalDate.now().plusDays(3).toEpochDay()
        val task=store.saveTask(Resolution(meetingId=meeting,title="مصوبهٔ آزمون دستگاه",owner="مسئول آزمون",due=day),listOf(System.currentTimeMillis()+3600000))
        store.addFollowUp(task,"منتظر تأیید مالی است")
        store.writableDatabase.execSQL("INSERT INTO backlog(title,description,owner,created) VALUES(?,?,?,?)",arrayOf<Any>("legacy entry","","",1L))
        val audio=File(File(context.filesDir,"recordings").apply{mkdirs()},"test.wav").apply{writeBytes(AudioCapture.wavHeader(320)+ByteArray(320))}
        val bytes=audio.readBytes()
        store.saveRecording(Recording(meetingId=meeting,path=audio.path,created=System.currentTimeMillis(),seconds=1,transcript="متن آزمون"))
        val before=store.snapshot()
        val archive=ByteArrayOutputStream().also{Backup.write(context,it,store)}.toByteArray()
        store.deleteMeeting(meeting)
        Backup.read(context,archive.inputStream()).use{Backup.restore(context,it,store)}
        val after=store.snapshot()
        assertEquals(before.copy(recordings=after.recordings),after)
        assertArrayEquals(bytes,File(after.recordings.single().path).readBytes())
    }
    @Test fun quickDatesAndEightOClockDefaultRespectIsolatedSettings()=isolated {context,_->
        val today=LocalDate.now().toEpochDay()
        WidgetDays.jump(context,101,1);assertEquals(today+1,WidgetDays.selected(context,101))
        WidgetDays.jump(context,101,7);assertEquals(today+7,WidgetDays.selected(context,101))
        assertEquals(today,WidgetDays.selected(context,102))
        assertTrue(Reminders.dailyEnabled(context));assertEquals(listOf(java.time.LocalTime.of(8,0)),Reminders.dailyTimes(context))
    }
    @Test fun schemaTwoUpgradeKeepsDataAndAcceptsTimestampedFollowUps()=isolated {context,store->
        val task=store.saveTask(Resolution(title="مصوبهٔ پیش از ارتقا",owner="علی احمدی"),emptyList())
        val before=store.snapshot();val name=store.databaseName!!
        store.writableDatabase.execSQL("DROP TABLE follow_ups");store.writableDatabase.execSQL("DROP TABLE notification_tokens");store.writableDatabase.execSQL("DROP TABLE backlog")
        store.writableDatabase.version=2;store.close()
        Store(context,name).use {upgraded->
            assertEquals(before,upgraded.snapshot());assertEquals(4,upgraded.readableDatabase.version)
            upgraded.addFollowUp(task,"نتیجهٔ پیگیری پس از ارتقا")
            assertTrue(upgraded.snapshot().followUps.single().created>0)
        }
    }
}
