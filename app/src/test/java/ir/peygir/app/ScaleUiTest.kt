package ir.peygir.app

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class ScaleUiTest {
    @get:Rule val compose=createComposeRule()
    private val f=ScaleFixture
    private val c get()=RuntimeEnvironment.getApplication()
    @Before fun setup(){c.getSharedPreferences("settings",0).edit().clear().commit();OrgAccess.set(c,SharedSession("https://test.invalid","","","","مدیر","manager",f.users.first().id,"scale"));Organization.members.value=f.users;Organization.contacts.value=emptyList()}
    @After fun clear(){OrgAccess.set(c,null);Organization.members.value=emptyList();Organization.contacts.value=emptyList();c.getSharedPreferences("shared_private",0).edit().clear().commit()}
    @Test fun fiveHundredTasksScrollLazilyFilterFromTheTopAndSearchAcrossTheWholeDataset() {
        var filter by mutableStateOf("all");var opened=0L
        compose.setContent{PeygirTheme("light"){TaskListScreen(f.data,f.day,filter,{filter=it},{opened=it.id},{_,_->})}}
        val ordered=f.tasks.sortedWith(Presentation.order(f.day,"manager"))
        compose.onNodeWithContentDescription("تعداد مصوبات: ۵۰۰").assertIsDisplayed()
        assertTrue(compose.onAllNodes(hasTestTagPrefix("task_card_")).fetchSemanticsNodes().size<30)
        compose.onNodeWithTag("task_list").performScrollToIndex(499)
        compose.onNodeWithTag("task_card_${ordered.last().id}").assertIsDisplayed()
        compose.onNodeWithTag("task_filter_button").performClick();compose.onNodeWithTag("task_filter_active").performClick()
        val first=f.tasks.filter{it.status==Status.ACTIVE}.sortedWith(Presentation.order(f.day,"manager")).first()
        compose.onNodeWithTag("task_card_${first.id}").assertIsDisplayed()
        compose.onNodeWithContentDescription("تعداد مصوبات: ۱۲۵").assertIsDisplayed()
        compose.onNodeWithContentDescription("جست‌وجوی مصوبات").performClick()
        compose.onNodeWithTag("task_search_field").performTextInput("گزارش 500")
        compose.onNodeWithTag("task_search_results").onChildren().assertCountEquals(1)
        compose.onNodeWithTag("task_card_500").performClick()
        compose.runOnIdle{assertEquals(500L,opened)}
    }
    @Test fun denseDailyAndWeeklyMeetingsOpenAReadableListWithoutLosingAnyMeeting() {
        var opened=0L
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(f.data,{opened=it},{},initialDay=f.day,clock={LocalDate.ofEpochDay(f.day).atTime(9,0)})}}
        compose.onNodeWithTag("meeting_dense_${f.day}_1").performClick()
        for(id in 1L..6L) {
            compose.onNodeWithTag("dense_meeting_list").performScrollToNode(hasTestTag("dense_meeting_$id"))
            compose.onNodeWithTag("dense_meeting_$id").assertIsDisplayed()
        }
        compose.onNodeWithTag("dense_meeting_6").performClick();compose.runOnIdle{assertEquals(6L,opened)}
        compose.onNodeWithTag("meeting_mode_WEEK").performClick()
        compose.onNodeWithTag("meeting_dense_${f.day}_1").assertExists()
        compose.onNodeWithTag("meeting_days_pager").performTouchInput{swipeLeft()}
        compose.onNodeWithTag("meeting_days_pager").assertIsDisplayed()
    }
    @Test fun monthAndMeetingSearchFindPastAndFutureMeetingsByParticipantOrTitle() {
        var opened=0L
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(f.data,{opened=it},{},initialDay=f.day,initialMode="MONTH")}}
        compose.onNodeWithTag("meeting_day_marker_${f.day}",useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("meeting_search_button").performClick()
        compose.onNodeWithTag("meeting_search_field").performTextInput("پروژه 200")
        compose.onNodeWithTag("meeting_search_results").onChildren().assertCountEquals(1)
        compose.onNodeWithTag("meeting_card_200").performClick();compose.runOnIdle{assertEquals(200L,opened)}
        compose.onNodeWithTag("meeting_search_button").performClick()
        compose.onNodeWithTag("meeting_search_field").performTextReplacement(f.users.last().name)
        val expected=f.meetings.filter{meetingSearch(it,f.users,f.users.last().name)}.sortedWith(MeetingSchedule.order)
        compose.onNodeWithContentDescription("تعداد نتایج در همهٔ تاریخ‌ها: ${expected.size.fa()}").assertExists()
        compose.onNodeWithTag("meeting_search_results").performScrollToNode(hasTestTag("meeting_card_${expected.last().id}"))
        compose.onNodeWithTag("meeting_card_${expected.last().id}").assertIsDisplayed()
    }
    @Test fun linkedMeetingChooserSearchesTwoHundredMeetingsInTheDefaultHoldMode() {
        var saved: Resolution?=null
        compose.setContent{PeygirTheme("light"){TaskEditor(Resolution(title="کار جدید",owner=f.users.first().name),f.meetings,emptyList(),false,{},{t,_->saved=t},null)}}
        compose.onNodeWithTag("task_editor_more").performScrollTo().performClick()
        compose.onNodeWithText("جلسه (اختیاری)",substring=false).performScrollTo().performClick()
        compose.onNodeWithTag("linked_meeting_query").performTextInput("پروژهٔ ۲۰۰")
        compose.onNodeWithTag("linked_meeting_200").performClick()
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.runOnIdle{assertEquals(200L,saved!!.meetingId)}
    }
    @Test fun accountsCanBrowseAndSearchAllFiftyPeopleWithoutBuildingAllCards() {
        c.getSharedPreferences("shared_private",0).edit().putString("session","test-only").commit()
        compose.setContent{PeygirTheme("light"){CompanyAccountsPanel{JSONObject().put("ok",true)}}}
        compose.onNodeWithText("مدیریت حساب‌ها").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("کاربر جدید",substring=false).fetchSemanticsNodes().any{!it.config.contains(SemanticsProperties.Disabled)}}
        assertTrue(compose.onAllNodes(hasTestTagPrefix("account_" ) and hasAnyDescendant(hasText("کاربر",substring=true))).fetchSemanticsNodes().size<30)
        compose.onNodeWithTag("account_list").performScrollToIndex(49)
        compose.onNodeWithTag("account_${f.users.last().id}").assertIsDisplayed()
        compose.onNodeWithTag("account_search").performTextInput("person50")
        compose.onNodeWithTag("account_${f.users.last().id}").assertIsDisplayed()
        compose.onNodeWithContentDescription("تعداد حساب‌ها: ۱").assertIsDisplayed()
    }
    @Test fun dailyReviewAndFollowupStayCompactAndEveryTodaysTaskIsReachable() {
        var review by mutableStateOf(false)
        compose.setContent{PeygirTheme("light"){if(review) DailyReviewScreen(f.data,f.day,f.day,{},{},{_,_->},{},{},{},{}) else DashboardScreen(f.data,f.day,{},{},{_,_->},{},{})}}
        assertTrue(compose.onAllNodes(hasTestTagPrefix("task_card_")).fetchSemanticsNodes().size<=32)
        compose.runOnIdle{review=true}
        val expected=ReviewDays.forDay(f.data,f.day,false).tasks
        compose.onNodeWithTag("review_day_${f.day}").performScrollToNode(hasTestTag("task_card_${expected.last().id}"))
        compose.onNodeWithTag("task_card_${expected.last().id}").assertIsDisplayed()
    }
    private fun hasTestTagPrefix(prefix: String)=SemanticsMatcher("tag starts with $prefix"){it.config.getOrElse(SemanticsProperties.TestTag){""}.startsWith(prefix)}
}
