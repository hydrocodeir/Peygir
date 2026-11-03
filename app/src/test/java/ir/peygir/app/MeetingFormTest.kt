package ir.peygir.app

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp-mdpi")
class MeetingFormTest {
    @get:Rule val compose=createComposeRule()
    private val day=LocalDate.now().toEpochDay()
    private val members=listOf(CompanyUser("a","علی احمدی","deputy"),CompanyUser("b","سمیه رضایی","deputy"))
    @Before fun setup(){val c=RuntimeEnvironment.getApplication();c.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(c,null);Organization.members.value=members}
    @After fun cleanup(){OrgAccess.set(RuntimeEnvironment.getApplication(),null);Organization.members.value=emptyList()}
    @Test fun reservationCheckboxIsLabeledAndSaved() {
        var saved: Meeting?=null
        compose.setContent{PeygirTheme("light"){MeetingEditor(Meeting(id=7,title="رزرو شورا"),false,{}, {saved=it})}}
        compose.onNodeWithText("ثبت به‌صورت رزرو").performScrollTo().performClick()
        compose.onNodeWithText("ثبت به‌صورت رزرو").assertIsOn()
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.runOnIdle{assertTrue(saved!!.reserved)}
    }
    @Test fun optionalFieldsStartCollapsedAndKeepTheirValuesThroughClosingAndReopening() {
        var saved: Meeting?=null
        val source=Meeting(id=7,title="شورا",date=day,notes="یادداشت قبلی",location="سالن",attendees=listOf("a","b"))
        compose.setContent{PeygirTheme("light"){MeetingEditor(source,false,{}, {saved=it})}}
        compose.onNodeWithText("یادداشت جلسه").assertDoesNotExist();compose.onNodeWithText("محل یا لینک جلسه").assertDoesNotExist()
        compose.onNodeWithTag("meeting_people").assertIsDisplayed()
        compose.onNodeWithTag("meeting_more_details").performClick()
        compose.onNodeWithText("یادداشت جلسه").performScrollTo().performTextReplacement("دستور جدید")
        compose.onNodeWithTag("meeting_more_details").performScrollTo().performClick()
        compose.onNodeWithText("یادداشت جلسه").assertDoesNotExist()
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.runOnIdle{assertEquals(source.copy(notes="دستور جدید"),saved)}
    }
    @Test fun sharedParticipantConflictIsVisibleAndSavingWithItRemainsPossible() {
        var saved: Meeting?=null
        val draft=Meeting(id=7,title="جدید",date=day,attendees=listOf("a"))
        val other=Meeting(id=8,title="جلسهٔ بودجه",date=day,start=570,end=630,attendees=listOf("a"))
        compose.setContent{PeygirTheme("light"){MeetingEditor(draft,false,{}, {saved=it},allMeetings=listOf(draft,other))}}
        compose.onNodeWithTag("meeting_conflicts").assertIsDisplayed()
        compose.onNodeWithText("جلسهٔ بودجه",substring=true).assertExists()
        compose.onNodeWithText("ثبت با تداخل",substring=false).performClick()
        compose.runOnIdle{assertEquals(draft,saved)}
    }
    @Test fun remoteUpdatesToCollapsedFieldsAreKeptWhenSavingTheMeeting() {
        val original=Meeting(id=7,title="جلسه",date=day,notes="قدیمی",location="الف",attendees=listOf("a"))
        val current=androidx.compose.runtime.mutableStateOf(original)
        var saved: Meeting?=null
        compose.setContent{PeygirTheme("light"){MeetingEditor(current.value,false,{}, {saved=it})}}
        val updated=original.copy(notes="یادداشت همگام‌شده",location="ب",end=630,attendees=listOf("a","b"))
        compose.runOnIdle{current.value=updated}
        compose.onNodeWithText("یادداشت جلسه").assertDoesNotExist()
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.runOnIdle{assertEquals(updated,saved)}
    }
    @Test fun removingSharedParticipantImmediatelyClearsTheConflictWarning() {
        val draft=Meeting(id=7,title="جدید",date=day,attendees=listOf("a","b"))
        val other=Meeting(id=8,title="دیگر",date=day,start=570,end=630,attendees=listOf("a"))
        compose.setContent{PeygirTheme("light"){MeetingEditor(draft,false,{}, {},allMeetings=listOf(other))}}
        compose.onNodeWithTag("meeting_conflicts").assertExists()
        compose.onNodeWithText("علی احمدی",substring=false).performClick()
        compose.onNodeWithTag("meeting_conflicts").assertDoesNotExist()
        compose.onNodeWithText("ذخیره",substring=false).assertExists()
    }
    @Test fun deputyNeverSeesCopyEvenWhenADetailCallbackIsSupplied() {
        OrgAccess.set(RuntimeEnvironment.getApplication(),SharedSession("https://test.invalid","","","","معاون","deputy","a"))
        var copied=false
        compose.setContent{PeygirTheme("light"){MeetingDetail(Snapshot(meetings=listOf(Meeting(id=1,title="دعوت"))),1,day,{}, {_,_->},{},{},copy={copied=true})}}
        compose.onNodeWithTag("meeting_copy").assertDoesNotExist();compose.runOnIdle{assertFalse(copied)}
    }
}
