package ir.peygir.app

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalOptionsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val c: Context get()=RuntimeEnvironment.getApplication()
    private lateinit var store: Store
    @Before fun setup() {
        c.getSharedPreferences("settings",0).edit().clear().putBoolean("daily",false).commit()
        c.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(c,null)
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        c.deleteDatabase("peygir.db");store=Store.get(c)
    }
    @After fun cleanup(){store.close();c.getSharedPreferences("settings",0).edit().clear().commit()}
    private fun options(v: UserOptions) {v.save(c.getSharedPreferences("settings",0).edit()).commit()}
    private fun displayed(tag: String) {compose.waitUntil(5000){runCatching{compose.onNodeWithTag(tag).assertIsDisplayed()}.isSuccess}}
    @Test fun missingPreferencesExactlyPreservePreviousDefaults() {
        val v=UserOptions.read(c)
        assertEquals(UserOptions(),v);assertEquals(2,v.launchSection(0));assertEquals("DAY",v.calendar)
        assertEquals(MeetingTimeline.initialHour(985),v.focusHour(985),0f)
        assertEquals(60,v.duration);assertEquals(-1,v.meetingLead);assertEquals("all",v.updates);assertEquals("compact",v.cards)
        assertEquals(1.6f,v.fontScale(1.6f),0f)
        compose.setContent{PeygirApp(calendarClock={LocalDate.now().atTime(16,30)})}
        displayed("section_2");compose.onNodeWithTag("meeting_mode_DAY").assertIsSelected()
    }
    @Test fun preferredLandingAndLastSectionPersistButExplicitNavigationWins() {
        options(UserOptions(launch="last"));c.getSharedPreferences("settings",0).edit().putInt("last_section",0).commit()
        val explicit=mutableStateOf<Int?>(null);val generation=mutableIntStateOf(0)
        compose.setContent{key(generation.intValue){PeygirApp(initialSection=explicit.value)}}
        displayed("section_0")
        compose.runOnIdle{explicit.value=1;generation.intValue++}
        displayed("section_1")
        compose.runOnIdle{assertEquals(1,c.getSharedPreferences("settings",0).getInt("last_section",-1))}
        compose.runOnIdle{explicit.value=null;generation.intValue++}
        displayed("section_1")
        assertEquals(0,UserOptions(launch="followup").launchSection(3));assertEquals(3,UserOptions(launch="review").launchSection(2))
    }
    @Test fun weeklyCalendarAndWorkStartAreUsedInsteadOfCurrentTime() {
        options(UserOptions(calendar="WEEK",focus="work",workStart=8*60+15))
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(),{},{},clock={LocalDate.now().atTime(17,45)})}}
        compose.onNodeWithTag("meeting_mode_WEEK").assertIsSelected()
        val range=compose.onNodeWithTag("meeting_timeline_scroll").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(48f*8.25f,range.value(),1f)
        compose.onNodeWithTag("meeting_mode_DAY").performClick()
        val daily=compose.onNodeWithTag("meeting_timeline_scroll").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(56f*8.25f,daily.value(),1f)
    }
    @Test fun meetingDurationOnlyChangesTheInitialSuggestionAndCanBeEdited() {
        options(UserOptions(duration=30))
        var saved: Meeting?=null
        compose.setContent{PeygirTheme("light"){MeetingEditor(Meeting(title="جلسه"),false,{},{saved=it},initialStart=9*60)}}
        compose.onNodeWithText("پایان: ۰۹:۳۰").assertExists()
        compose.onNodeWithTag("meeting_end_time").performClick()
        compose.runOnIdle{val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.TimePickerDialog;dialog.updateTime(10,45);dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()}
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.runOnIdle{assertEquals(645,saved!!.end);assertEquals(540,saved!!.start)}
    }
    @Test fun expandedCardsAddDetailsAndCompactRemainsTheDefault() {
        val t=Resolution(id=1,title="گزارش",owner="احمدی",description="توضیحات مخصوص کارت")
        compose.setContent{PeygirTheme("light"){TaskCard(t,"جلسهٔ فروش",LocalDate.now().toEpochDay(),{}){}}}
        compose.onNodeWithText(t.description).assertDoesNotExist()
        compose.runOnIdle{options(UserOptions(cards="full"))}
        compose.onNodeWithText(t.description).assertIsDisplayed();compose.onNodeWithText("جلسه: جلسهٔ فروش").assertIsDisplayed()
        compose.runOnIdle{options(UserOptions())}
        compose.onNodeWithText(t.description).assertDoesNotExist()
    }
    @Test fun textSizeUpdatesImmediatelyAndSystemKeepsItsOriginalScale() {
        var measured=0f
        compose.setContent{CompositionLocalProvider(LocalDensity provides Density(1f,1.6f)){PeygirTheme("light"){measured=LocalDensity.current.fontScale;Text("متن")}}}
        compose.runOnIdle{assertEquals(1.6f,measured,0f);options(UserOptions(font="large"))}
        compose.runOnIdle{assertEquals(1.25f,measured,0f);options(UserOptions(font="standard"))}
        compose.runOnIdle{assertEquals(1f,measured,0f);options(UserOptions())}
        compose.runOnIdle{assertEquals(1.6f,measured,0f)}
    }
    @Test fun personalSettingsUseImmediateChoicesWithoutExtraSave() {
        compose.setContent{PeygirTheme("light"){SettingsScreen("light",{},{})}}
        compose.onNodeWithTag("settings_personal").performScrollTo().performClick()
        compose.onNodeWithTag("option_launch").performScrollTo()
        compose.onNodeWithTag("option_launch_followup").performClick()
        compose.runOnIdle{assertEquals("followup",UserOptions.read(c).launch)}
        compose.onNodeWithTag("option_calendar").performScrollTo()
        compose.onNodeWithTag("option_calendar_MONTH").performClick()
        compose.runOnIdle{assertEquals("MONTH",UserOptions.read(c).calendar)}
    }
    @Test fun allChoicesAndPerMeetingOverridesRoundTripWithoutServerCredentials() {
        val chosen=UserOptions("last","WEEK","work",480,45,15,"off","full","large",false,75,false)
        options(chosen)
        store.saveMeeting(Meeting(title="جلسه",alertMinutes=30))
        val bytes=ByteArrayOutputStream().also{Backup.write(c,it,store)}.toByteArray()
        options(UserOptions())
        Backup.read(c,bytes.inputStream()).use{p->assertEquals(chosen,p.settings.options);assertEquals(30,p.data.meetings.single().alertMinutes);Backup.restore(c,p,store)}
        assertEquals(chosen,UserOptions.read(c));assertEquals(30,store.snapshot().meetings.single().alertMinutes)
        assertThrows(IllegalArgumentException::class.java){UserOptions.from(org.json.JSONObject().put("calendar","unknown"))}
    }
    @Test fun versionSevenUpgradePreservesSharedDataAndPendingOperations() {
        val name="v7-options-${UUID.randomUUID()}.db";val old=Store(c,name)
        val shared=SharedStore(old);old.saveMeeting(Meeting(title="قدیمی"));shared.bind("https://unit.invalid",UUID.randomUUID().toString())
        val pending=shared.prepare().map{it.operation.toString()};val snapshot=old.snapshot()
        old.writableDatabase.execSQL("DROP TABLE meeting_alerts");old.writableDatabase.version=7;old.close()
        val upgraded=Store(c,name)
        try {assertEquals(10,upgraded.readableDatabase.version);assertEquals(snapshot,upgraded.snapshot());assertEquals(pending,SharedStore(upgraded).prepare().map{it.operation.toString()})}
        finally {upgraded.close();c.deleteDatabase(name)}
    }
}
