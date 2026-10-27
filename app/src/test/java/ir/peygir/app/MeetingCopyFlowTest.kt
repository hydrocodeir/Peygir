package ir.peygir.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp-mdpi")
class MeetingCopyFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: Store
    private val day=LocalDate.now().toEpochDay()
    @Before fun setup() {
        val c=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        c.deleteDatabase("peygir.db");c.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(c,null);store=Store.get(c)
    }
    @After fun cleanup(){store.close()}
    private fun open(id: Long) {
        val request=mutableStateOf(id)
        compose.setContent{PeygirApp(requestMeeting=request.value,consume={request.value=0})}
        compose.waitUntil(15000){compose.onAllNodesWithContentDescription("گزینه‌های جلسه").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun copyPreservesPeopleDurationAndOptionalFieldsWithoutCopyingTasksAudioOrSeries() {
        val source=store.saveMeeting(Meeting(title="شورای اصلی",date=day-1,notes="دستور قبلی",start=555,end=645,location="سالن اصلی",attendees=listOf("a","b"),repeat="WEEKLY",until=day+13,series="original-series"))
        store.saveTask(Resolution(title="مصوبهٔ اصلی",owner="احمدی",meetingId=source,due=day),listOf(System.currentTimeMillis()+3_600_000))
        val file=File(RuntimeEnvironment.getApplication().filesDir,"recordings/source.wav").apply{parentFile!!.mkdirs();writeBytes(ByteArray(100))}
        store.saveRecording(Recording(meetingId=source,path=file.absolutePath,created=System.currentTimeMillis(),seconds=2))
        val before=store.snapshot()
        open(source);compose.onNodeWithContentDescription("گزینه‌های جلسه").performClick();compose.onNodeWithTag("meeting_copy").performClick()
        compose.onNodeWithTag("meeting_start_time").assertTextContains("شروع: ۰۹:۱۵")
        compose.onNodeWithTag("meeting_end_time").assertTextContains("پایان: ۱۰:۴۵")
        compose.onNodeWithText("عنوان جلسه *").performTextReplacement("شورای مشابه")
        assertEquals(1,store.snapshot().meetings.size)
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000){store.snapshot().meetings.size==2}
        val after=store.snapshot();val copied=after.meetings.single{it.id!=source}
        assertEquals(before.meetings.single(),after.meetings.single{it.id==source})
        assertEquals("شورای مشابه",copied.title);assertEquals(day,copied.date)
        assertEquals(listOf("a","b"),copied.attendees);assertEquals(555,copied.start);assertEquals(645,copied.end)
        assertEquals("دستور قبلی",copied.notes);assertEquals("سالن اصلی",copied.location)
        assertEquals("NONE",copied.repeat);assertNull(copied.series);assertNull(copied.until)
        assertEquals(before.tasks,after.tasks);assertEquals(before.recordings,after.recordings);assertEquals(before.reminders,after.reminders);assertTrue(file.exists())
    }
    @Test fun cancelledCopyReturnsToOriginalAndTheNextBlankFormDoesNotInheritItsData() {
        val source=store.saveMeeting(Meeting(title="اصل محفوظ",date=day,start=555,end=645,attendees=listOf("a")))
        val before=store.snapshot();open(source)
        compose.onNodeWithContentDescription("گزینه‌های جلسه").performClick();compose.onNodeWithTag("meeting_copy").performClick()
        compose.onNodeWithText("عنوان جلسه *").performTextReplacement("کپی ثبت‌نشده")
        compose.onNodeWithContentDescription("بستن").performClick()
        compose.onNodeWithContentDescription("گزینه‌های جلسه").assertExists();assertEquals(before,store.snapshot())
        compose.runOnIdle{compose.activity.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithContentDescription("جلسهٔ تازه").performClick()
        compose.onNodeWithTag("meeting_start_time").assertTextContains("ساعت شروع")
        compose.onNodeWithTag("meeting_end_time").assertTextContains("ساعت پایان")
        compose.runOnIdle{assertEquals("",compose.onNodeWithText("عنوان جلسه *").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)}
        assertEquals(before,store.snapshot())
    }
}
