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
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w360dp-h740dp-mdpi")
class SimpleExperienceTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=RuntimeEnvironment.getApplication()
    private val today=LocalDate.now().toEpochDay()
    @Before fun setup(){OrgAccess.set(context,null);context.getSharedPreferences("shared_private",0).edit().clear().commit();Organization.members.value=emptyList()}
    @After fun cleanup(){OrgAccess.set(context,null);Organization.members.value=emptyList()}
    @Test fun taskCardShowsEssentialFieldsAndKeepsSecondaryMetadataOutOfTheList() {
        val task=Resolution(id=1,title="گزارش فروش",owner="احمدی",due=today-1,description="شرح تکمیلی",priority=Priority.HIGH,delayCount=3)
        compose.setContent{PeygirTheme("light"){TaskCard(task,"جلسهٔ مرتبط",today,{},{})}}
        compose.onNodeWithText("گزارش فروش").assertExists();compose.onNodeWithText("احمدی",substring=true).assertExists()
        compose.onNodeWithText("معوق",substring=true).assertExists();compose.onNodeWithText(Status.WAITING.label).assertExists()
        compose.onNodeWithText("شرح تکمیلی").assertDoesNotExist();compose.onNodeWithText("جلسهٔ مرتبط").assertDoesNotExist();compose.onNodeWithText("مهم").assertDoesNotExist();compose.onNodeWithText("تغییر موعد:",substring=true).assertDoesNotExist()
    }
    @Test fun summaryShowsLatestResultAndRevealsHistoryAndDescriptionOnlyOnRequest() {
        val task=Resolution(id=1,title="گزارش",owner="احمدی",due=today-1,description="شرح کامل")
        val data=Snapshot(tasks=listOf(task),followUps=listOf(FollowUp(1,1,"نتیجه قدیمی",100),FollowUp(2,1,"آخرین نتیجه",200)))
        var edited=false;var changed: Status?=null
        compose.setContent{PeygirTheme("light"){TaskSummary(task,data,false,{}, {changed=it},{_,_->},edit={edited=true})}}
        compose.onNodeWithText("آخرین نتیجه").assertExists();compose.onNodeWithText("نتیجه قدیمی").assertDoesNotExist();compose.onNodeWithText("شرح کامل").assertDoesNotExist()
        compose.onNodeWithTag("task_primary_action").assertTextContains("انجام شد")
        compose.onNodeWithTag("task_summary_more").performClick();compose.onNodeWithText("شرح کامل").assertExists();compose.onNodeWithText("نتیجه قدیمی").assertExists()
        compose.onNodeWithContentDescription("گزینه‌های مصوبه").performClick();compose.onNodeWithText("ویرایش",substring=false).performClick()
        compose.runOnIdle{assertTrue(edited);assertNull(changed)}
    }
    @Test fun meetingDetailsKeepMetadataAndCopyDeleteActionsBehindDisclosureOrMenu() {
        Organization.members.value=(1..4).map{CompanyUser("p$it","معاون شماره $it","deputy")}
        val m=Meeting(id=1,title="جلسه",date=today,location="محل کامل",notes="یادداشت کامل",attendees=(1..4).map{"p$it"})
        var copied=false
        compose.setContent{PeygirTheme("light"){MeetingDetail(Snapshot(meetings=listOf(m)),1,today,{}, {_,_->},{},{},{},copy={copied=true})}}
        compose.onNodeWithText("یادداشت کامل").assertDoesNotExist();compose.onNodeWithText("محل کامل",substring=true).assertDoesNotExist();compose.onNodeWithTag("meeting_copy").assertDoesNotExist()
        compose.onNodeWithTag("meeting_detail_more").performClick();compose.onNodeWithText("یادداشت کامل").assertExists();compose.onNodeWithText("محل کامل",substring=true).assertExists()
        compose.onNodeWithContentDescription("گزینه‌های جلسه").performClick();compose.onNodeWithTag("meeting_copy").performClick();compose.runOnIdle{assertTrue(copied)}
    }
    @Test fun managerSeesApprovalsBeforeOtherWork() {
        OrgAccess.set(context,SharedSession("https://test.invalid","","","","رئیس","manager","chief"))
        val data=Snapshot(tasks=listOf(Resolution(id=1,title="معوق",due=today-1),Resolution(id=2,title="تأیید لازم",due=today+2,status=Status.REVIEW),Resolution(id=3,title="انجام‌شده",status=Status.DONE)))
        compose.setContent{PeygirTheme("light"){DashboardScreen(data,today,{},{},{_,_->},{},{})}}
        val review=compose.onNodeWithTag("task_card_2").fetchSemanticsNode().boundsInRoot
        val late=compose.onNodeWithTag("task_card_1").fetchSemanticsNode().boundsInRoot
        assertTrue(review.top<late.top);compose.onNodeWithText("انجام‌شده",substring=false).assertDoesNotExist()
    }
    @Test fun assistantGetsAFastMeetingShortcutOnTheFollowupPage() {
        OrgAccess.set(context,SharedSession("https://test.invalid","","","","دستیار","assistant","assistant"))
        var added=false
        compose.setContent{PeygirTheme("light"){DashboardScreen(Snapshot(),today,{},{},{_,_->},{},{},addMeeting={added=true})}}
        compose.onNodeWithText("ثبت جلسه",substring=false).performClick();compose.runOnIdle{assertTrue(added)}
    }
    @Test fun deputyHasOneApprovalRequestActionAndNoEditingOrDeletion() {
        OrgAccess.set(context,SharedSession("https://test.invalid","","","","معاون","deputy","a"))
        val task=Resolution(id=1,title="کار من",assignee="a",due=today)
        var state: Status?=null
        compose.setContent{PeygirTheme("light"){TaskSummary(task,Snapshot(tasks=listOf(task)),false,{}, {state=it},{_,_->})}}
        compose.onNodeWithTag("task_primary_action").assertTextContains("ارسال برای تأیید").performClick()
        compose.runOnIdle{assertEquals(Status.REVIEW,state)}
        compose.onNodeWithContentDescription("گزینه‌های مصوبه").performClick();compose.onNodeWithText("ویرایش",substring=false).assertDoesNotExist();compose.onNodeWithText("حذف مصوبه").assertDoesNotExist()
    }
    @Test fun aSaveFailureStaysVisibleAfterScrollingDeepIntoMoreDetails() {
        val task=Resolution(id=1,title="گزارش",owner="احمدی",description=(1..60).joinToString("\n"){"شرح طولانی $it"})
        val error=androidx.compose.runtime.mutableStateOf("")
        compose.setContent{PeygirTheme("light"){TaskSummary(task,Snapshot(tasks=listOf(task)),false,{}, {},{_,_->},feedback=error.value)}}
        compose.onNodeWithTag("task_summary_more").performClick()
        compose.onNodeWithText("اولویت: عادی").performScrollTo()
        compose.runOnIdle{error.value="ثبت ناموفق بود؛ دوباره تلاش کنید."}
        compose.onNodeWithText("ثبت ناموفق بود؛ دوباره تلاش کنید.").assertIsDisplayed()
        compose.onNodeWithTag("task_primary_action").assertIsDisplayed()
    }
}
