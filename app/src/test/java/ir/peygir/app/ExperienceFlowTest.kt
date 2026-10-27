package ir.peygir.app

import android.app.Application
import android.content.DialogInterface
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class ExperienceFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: Store
    @Before fun setup() {val c=RuntimeEnvironment.getApplication();Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null);c.deleteDatabase("peygir.db");c.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(c,null);store=Store.get(c)}
    @After fun close(){store.close()}
    @Test fun monthlyCalendarCreatesTheMeetingWithTheChosenDateAndStartTime() {
        val day=LocalDate.now().let{if(Solar.parts(it).day>1) it.minusDays(1) else it.plusDays(1)}.toEpochDay()
        compose.setContent{PeygirApp()}
        compose.waitUntil(15000){compose.onAllNodesWithTag("meeting_calendar").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("meeting_mode_MONTH").performClick()
        compose.onNodeWithTag("meeting_day_$day").performClick()
        compose.runOnIdle{val dialog=ShadowDialog.getLatestDialog() as android.app.TimePickerDialog;dialog.updateTime(14,30);dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()}
        compose.waitUntil(15000){compose.onAllNodesWithText("عنوان جلسه *").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("meeting_start_time").assertTextContains("شروع: ۱۴:۳۰")
        compose.onNodeWithTag("meeting_end_time").assertTextContains("پایان: ۱۵:۳۰")
        compose.onNodeWithText("عنوان جلسه *").performTextInput("جلسه از تقویم")
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000){store.snapshot().meetings.size==1}
        val saved=store.snapshot().meetings.single()
        assertEquals(day,saved.date);assertEquals(870,saved.start);assertEquals(930,saved.end)
    }
    @Test fun slotDraftPrefillsTimesAndDiscardDoesNotCarryFieldsIntoTheNextBlankForm() {
        compose.setContent{PeygirApp()}
        compose.waitUntil(15000){compose.onAllNodesWithTag("meeting_calendar").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("meeting_mode_DAY").performClick()
        val y=126f*compose.activity.resources.displayMetrics.density
        compose.onNodeWithTag("meeting_timeline_scroll").performTouchInput{click(androidx.compose.ui.geometry.Offset(width/2f,y))}
        compose.waitUntil(15000){compose.onAllNodesWithText("عنوان جلسه *").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("meeting_start_time").assertTextContains("شروع: ۰۹:۱۵")
        compose.onNodeWithTag("meeting_end_time").assertTextContains("پایان: ۱۰:۱۵")
        compose.onNodeWithText("عنوان جلسه *").performTextInput("پیش‌نویس موقت")
        compose.onNodeWithContentDescription("بستن").performClick()
        compose.onNodeWithContentDescription("جلسهٔ تازه").performClick()
        compose.onNodeWithTag("meeting_start_time").assertTextContains("ساعت شروع")
        compose.onNodeWithTag("meeting_end_time").assertTextContains("ساعت پایان")
        compose.onNodeWithText("پیش‌نویس موقت").assertDoesNotExist()
        compose.runOnIdle{assertTrue(store.snapshot().meetings.isEmpty())}
    }
    @Test fun homeStartsAtMeetingsThenSwipesThroughFollowupTasksAndReviewWithoutAGlobalAddButton() {
        compose.setContent{PeygirApp()}
        compose.waitUntil(15000){compose.onAllNodesWithTag("meeting_calendar").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("section_2").assertIsDisplayed()
        compose.onNodeWithText("ثبت مصوبه",substring=false).assertDoesNotExist()
        compose.onNodeWithTag("section_swipe_2").performTouchInput{swipeRight()}
        compose.onNodeWithTag("section_0").assertIsDisplayed()
        compose.onNodeWithTag("section_swipe_0").performTouchInput{swipeRight()}
        compose.onNodeWithTag("section_1").assertIsDisplayed()
        compose.onNodeWithTag("section_swipe_1").performTouchInput{swipeRight()}
        compose.onNodeWithTag("section_3").assertIsDisplayed()
        compose.onNodeWithText("ثبت مصوبه",substring=false).assertDoesNotExist()
    }
    @Test fun reviewShowsOnlyResolutionsAndTasksHaveCompactDropdownAndModalSearch() {
        val c=RuntimeEnvironment.getApplication();val day=LocalDate.now().toEpochDay()
        store.saveMeeting(Meeting(title="جلسهٔ مخفی در مرور"))
        store.saveTask(Resolution(title="مصوبهٔ امروز",owner="احمدی",due=day),emptyList())
        store.saveTask(Resolution(title="مصوبهٔ تمام‌شده",owner="رضایی",due=day,status=Status.DONE),emptyList())
        compose.setContent{PeygirApp()}
        compose.waitUntil(15000){compose.onAllNodesWithTag("meeting_calendar").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        compose.onNodeWithText("مصوبهٔ امروز").assertIsDisplayed()
        compose.onNodeWithText("جلسهٔ مخفی در مرور").assertDoesNotExist()
        compose.onNode(hasText("مصوبات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("تعداد مصوبات: ۱").assertIsDisplayed()
        compose.onNodeWithTag("task_search_field").assertDoesNotExist()
        compose.onNodeWithTag("task_filter_button").performClick();compose.onNodeWithTag("task_filter_done").performClick()
        compose.onNodeWithText("مصوبهٔ امروز").assertDoesNotExist();compose.onNodeWithText("مصوبهٔ تمام‌شده").assertIsDisplayed()
        compose.onNodeWithContentDescription("جست‌وجوی مصوبات").performClick()
        compose.onNodeWithTag("task_search_field").performTextInput("احمدی")
        compose.onNodeWithText("مصوبهٔ امروز").assertIsDisplayed()
    }
    @Test fun meetingCannotBeSavedWithoutTimesAndValidTimesAreCommitted() {
        var saved: Meeting?=null
        compose.setContent{PeygirTheme("light"){MeetingEditor(Meeting(title="جلسهٔ تازه"),false,{}, {saved=it})}}
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.runOnIdle{assertNull(saved)}
        compose.onNodeWithText("ساعت شروع و پایان جلسه الزامی است",substring=true).assertExists()
        compose.onNodeWithTag("meeting_start_time").performScrollTo().performClick()
        compose.runOnIdle{val d=ShadowDialog.getLatestDialog() as android.app.TimePickerDialog;d.updateTime(14,30);d.getButton(DialogInterface.BUTTON_POSITIVE).performClick()}
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.runOnIdle{assertEquals(870,saved?.start);assertEquals(930,saved?.end)}
    }
}
