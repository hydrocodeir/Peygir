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
import androidx.compose.runtime.*
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class OrganizationUiTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=RuntimeEnvironment.getApplication()
    private val deputy=CompanyUser(UUID.randomUUID().toString(),"علی احمدی","deputy")
    @Before fun setup() {context.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(context,null);Organization.members.value=listOf(deputy)}
    @After fun cleanup() {context.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(context,null);Organization.members.value=emptyList()}
    @Test fun calendarSearchFindsOldMeetingByParticipantAndSwitchesDayWeekMonth() {
        val meeting=Meeting(id=1,title="جلسه بودجه",date=LocalDate.now().minusMonths(3).toEpochDay(),attendees=listOf(deputy.id))
        compose.setContent {PeygirTheme("light"){MeetingCalendarScreen(Snapshot(meetings=listOf(meeting)),{},{},clock={java.time.LocalDate.now().atTime(7,30)})}}
        compose.onNodeWithText("جلسه بودجه").assertDoesNotExist()
        compose.onNodeWithContentDescription("جست‌وجوی جلسات").performClick()
        compose.onNodeWithTag("meeting_search_field").performTextInput("احمدی")
        compose.onNodeWithText("جلسه بودجه").assertExists()
        compose.onNodeWithContentDescription("پاک کردن جست‌وجو").performClick()
        compose.onNodeWithContentDescription("بستن جست‌وجوی جلسات").performClick()
        compose.onNodeWithTag("meeting_mode_WEEK").performClick();compose.onNodeWithTag("meeting_mode_DAY").performClick();compose.onNodeWithTag("meeting_mode_MONTH").performClick()
        compose.onNodeWithTag("meeting_day_${LocalDate.now().toEpochDay()}").assertExists()
    }
    @Test fun dailyTimeGridScrollsToEveningAndOpensTheCorrectMeeting() {
        val day=LocalDate.now().toEpochDay();var opened=0L
        val data=Snapshot(meetings=listOf(Meeting(id=40,title="جلسهٔ عصر",date=day,start=1080,end=1140)))
        compose.setContent {PeygirTheme("light"){MeetingCalendarScreen(data,{opened=it},{},initialDay=day,clock={java.time.LocalDate.now().atTime(7,30)})}}
        compose.onNodeWithTag("meeting_mode_DAY").assertIsSelected()
        compose.onNodeWithTag("meeting_mode_DAY").performClick()
        compose.onNodeWithTag("meeting_card_40").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle{assertEquals(40L,opened)}
    }
    @Test fun weeklySwipeTurnsWholeWeeksAndReturnsWithoutSwitchingMode() {
        val friday=LocalDate.of(2026,10,30).toEpochDay()
        val data=Snapshot(meetings=listOf(Meeting(id=1,title="جلسهٔ جمعه",date=friday),Meeting(id=2,title="جلسهٔ هفته بعد",date=friday+7)))
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(data,{},{},initialDay=friday,clock={java.time.LocalDate.now().atTime(7,30)})}}
        compose.onNodeWithTag("meeting_mode_WEEK").performClick()
        compose.onNodeWithTag("meeting_week_day_$friday").assertIsSelected()
        compose.onNodeWithText("جلسهٔ جمعه",substring=false).assertIsDisplayed()
        compose.onNodeWithTag("meeting_days_pager").performTouchInput{swipeRight()}
        compose.onNodeWithTag("meeting_day_page_${friday+7}").assertIsDisplayed()
        compose.onNodeWithTag("meeting_week_day_${friday+7}").assertIsSelected()
        compose.onNodeWithTag("meeting_week_day_$friday").assertIsNotDisplayed()
        compose.onNodeWithTag("meeting_mode_WEEK").assertIsSelected()
        compose.onNodeWithText("جلسهٔ هفته بعد",substring=false).assertIsDisplayed()
        compose.onNodeWithText("جلسهٔ جمعه",substring=false).assertIsNotDisplayed()
        compose.onNodeWithTag("meeting_days_pager").performTouchInput{swipeLeft()}
        compose.onNodeWithTag("meeting_day_page_$friday").assertIsDisplayed()
        compose.onNodeWithTag("meeting_week_day_$friday").assertIsSelected()
    }
    @Test fun modalSearchFindsAllDatesClosesOnSelectionAndKeepsCalendarDayOnDismissal() {
        val today=LocalDate.now().toEpochDay();var opened: Long?=null
        val old=Meeting(id=7,title="جلسهٔ ماه قبل",date=today-35,attendees=listOf(deputy.id))
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(meetings=listOf(old)),{opened=it},{},clock={java.time.LocalDate.now().atTime(7,30)})}}
        compose.onNodeWithTag("meeting_search_field").assertDoesNotExist()
        compose.onNodeWithTag("meeting_mode_WEEK").performClick()
        compose.onNodeWithTag("meeting_days_pager").performTouchInput{swipeRight()}
        compose.onNodeWithContentDescription("جست‌وجوی جلسات").performClick()
        compose.onNodeWithTag("meeting_search_modal").assertIsDisplayed()
        compose.onNodeWithTag("meeting_search_field").performTextInput("احمدی")
        compose.onNodeWithText("جلسهٔ ماه قبل").assertIsDisplayed()
        compose.onNodeWithContentDescription("بستن جست‌وجوی جلسات").performClick()
        compose.onNodeWithTag("meeting_search_modal").assertDoesNotExist()
        compose.onNodeWithTag("meeting_week_day_${today+7}").assertIsSelected()
        compose.onNodeWithContentDescription("جست‌وجوی جلسات").performClick()
        compose.onNodeWithText("جلسهٔ ماه قبل").performClick()
        compose.runOnIdle{assertEquals(7L,opened)}
        compose.onNodeWithTag("meeting_search_modal").assertDoesNotExist()
    }
    @Test fun weekDayTapKeepsWeeklyModeAndNewMeetingUsesThatDay() {
        val friday=LocalDate.of(2026,10,30).toEpochDay();val thursday=friday-1
        var added: Long?=null
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(),{},{added=it},initialDay=friday,clock={java.time.LocalDate.now().atTime(7,30)})}}
        compose.onNodeWithTag("meeting_mode_WEEK").performClick()
        compose.onNodeWithTag("meeting_week_day_$thursday").performClick()
        compose.onNodeWithTag("meeting_mode_WEEK").assertIsSelected()
        compose.onNodeWithTag("meeting_day_page_$thursday").assertIsDisplayed()
        compose.onNodeWithContentDescription("جلسهٔ تازه").performClick()
        compose.runOnIdle{assertEquals(thursday,added)}
    }
    @Test fun monthlySelectionDailySwipesAndModeChangesKeepOneSelectedDate() {
        val today=LocalDate.now().toEpochDay();val target=if(Solar.parts(LocalDate.now()).day>1) today-1 else today+7
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(),{},{},initialMode="MONTH",clock={java.time.LocalDate.now().atTime(7,30)})}}
        compose.onNodeWithTag("meeting_day_$target").performClick()
        compose.onNodeWithTag("meeting_day_$target").assertIsSelected()
        compose.onNodeWithTag("meeting_mode_DAY").performClick()
        compose.onNodeWithTag("meeting_day_page_$target").assertIsDisplayed()
        compose.onNodeWithTag("meeting_days_pager").performTouchInput{swipeRight()}
        compose.onNodeWithTag("meeting_day_page_${target+1}").assertIsDisplayed()
        compose.onNodeWithContentDescription("روز قبل").performClick()
        compose.onNodeWithTag("meeting_day_page_$target").assertIsDisplayed()
        compose.onNodeWithTag("meeting_mode_WEEK").performClick()
        compose.onNodeWithTag("meeting_week_day_$target").assertIsSelected()
        compose.onNodeWithTag("meeting_mode_MONTH").performClick()
        compose.onNodeWithTag("meeting_day_$target").assertIsSelected()
    }
    @Test fun assigneePickerStoresAccountIdAndFillsResponsibleName() {
        context.getSharedPreferences("shared_private",0).edit().putString("session","test-only").commit()
        var saved: Resolution?=null
        compose.setContent {PeygirTheme("light"){TaskEditor(Resolution(title="گزارش"),emptyList(),emptyList(),false,{}, {t,_->saved=t},null)}}
        compose.onNodeWithText("مسئول انجام *").performTextInput("احمدی")
        compose.onNodeWithText(deputy.name,substring=false).performClick()
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.runOnIdle{assertEquals(deputy.id,saved?.assignee);assertEquals(deputy.name,saved?.owner)}
    }
    @Test fun deputyDetailsExposeProgressAndReviewButNoEditableTitleOrDeadline() {
        OrgAccess.set(context,SharedSession("https://test.invalid","","","",deputy.name,"deputy",deputy.id))
        var savedNote="";var reported: Status?=null
        val t=Resolution(id=1,title="گزارش فروش",owner=deputy.name,assignee=deputy.id)
        compose.setContent {PeygirTheme("light"){DeputyTaskDetails(t,Snapshot(tasks=listOf(t)),false,{}, {reported=it},{text,done->savedNote=text;done()},"")}}
        compose.onNodeWithText("عنوان مصوبه *").assertDoesNotExist();compose.onNodeWithText("مسئول انجام *").assertDoesNotExist();compose.onNodeWithText("تمدید مهلت").assertDoesNotExist()
        compose.onNodeWithText("ثبت نتیجهٔ پیگیری").performClick()
        compose.onNodeWithText("نتیجهٔ پیگیری در یک خط").performScrollTo().performTextInput("منتظر تأیید مالی")
        compose.onNodeWithText("ثبت گزارش پیشرفت").performClick()
        compose.runOnIdle{assertEquals("منتظر تأیید مالی",savedNote)}
        compose.onNodeWithText("ارسال برای تأیید",substring=false).performClick()
        compose.runOnIdle{assertEquals(Status.REVIEW,reported)}
    }
}
