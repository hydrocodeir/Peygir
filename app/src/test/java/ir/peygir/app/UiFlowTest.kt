package ir.peygir.app

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class UiFlowTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var store: Store
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db")
        context.getSharedPreferences("settings",0).edit().clear().commit()
        context.getSharedPreferences("drafts",0).edit().clear().commit()
        store=Store.get(context)
    }
    private fun start() {
        compose.setContent {PeygirApp(initialSection=0)}
        try {compose.waitUntil(15000) {compose.onAllNodesWithText("برای پیگیری").fetchSemanticsNodes().isNotEmpty()}}
        catch(e: Exception) {println(compose.onRoot(useUnmergedTree=true).printToString());throw e}
    }
    @Test fun startsPersianRtlWithEmptyDashboardAndNavigation() {
        start()
        assertEquals(LayoutDirection.Rtl,compose.onNodeWithText("برای پیگیری").fetchSemanticsNode().layoutInfo.layoutDirection)
        compose.onNode(hasText("جلسات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithTag("meeting_calendar").assertIsDisplayed()
        compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("daily_pager").assertIsDisplayed()
        compose.onNodeWithText("مخزن",useUnmergedTree=true).assertDoesNotExist()
    }
    @Test fun mainSectionsSwipeInRtlAndDailyPagesStayIndependent() {
        start();compose.onNode(hasText("جلسات",substring=false) and hasClickAction()).performClick()
        val today=LocalDate.now().toEpochDay()
        compose.onNodeWithTag("meeting_mode_WEEK").performClick()
        compose.onNodeWithTag("meeting_days_pager").performTouchInput {swipeRight()}
        compose.onNodeWithTag("section_2").assertIsDisplayed()
        compose.onNodeWithTag("meeting_day_page_${today+7}").assertIsDisplayed()
        compose.onNodeWithTag("section_swipe_2").performTouchInput {swipeRight()}
        compose.onNodeWithTag("section_0").assertIsDisplayed()
        compose.onNodeWithTag("section_swipe_0").performTouchInput {swipeRight()}
        compose.onNodeWithTag("section_1").assertIsDisplayed()
        compose.onNodeWithTag("section_swipe_1").performTouchInput {swipeRight()}
        compose.onNodeWithTag("section_3").assertIsDisplayed()
        compose.onNodeWithTag("daily_pager").performTouchInput {swipeRight()}
        compose.onNodeWithTag("review_day_${today+1}").assertIsDisplayed()
        compose.onNodeWithTag("section_swipe_3").performTouchInput {swipeRight()}
        compose.onNodeWithTag("section_3").assertIsDisplayed()
        compose.onNodeWithTag("section_swipe_3").performTouchInput {swipeLeft()}
        compose.onNodeWithTag("section_1").assertIsDisplayed()
        compose.onNode(hasText("جلسات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithTag("meeting_week_day_${today+7}").assertIsSelected()
        compose.onNodeWithTag("meeting_mode_DAY").performClick()
        compose.onNodeWithTag("meeting_days_pager").performTouchInput {swipeLeft()}
        compose.onNodeWithTag("meeting_day_page_${today+6}").assertIsDisplayed()
        compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("review_day_${today+1}").assertIsDisplayed()
    }
    @Test fun searchSurvivesSectionAndSettingsNavigationAndCanBeCleared() {
        store.saveTask(Resolution(title="قرارداد خرید",owner="احمدی"),emptyList())
        store.saveTask(Resolution(title="گزارش فروش",owner="رضایی"),emptyList())
        start();compose.onNode(hasText("مصوبات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("جست‌وجوی مصوبات").performClick()
        compose.onNodeWithTag("task_search_field").performTextInput("احمدی")
        compose.onNode(hasText("قرارداد خرید") and hasAnyAncestor(hasTestTag("task_search_modal"))).assertIsDisplayed()
        compose.onNode(hasText("گزارش فروش") and hasAnyAncestor(hasTestTag("task_search_modal"))).assertDoesNotExist()
        compose.onNodeWithContentDescription("بستن جست‌وجوی مصوبات").performClick()
        compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("تنظیمات").performClick()
        compose.onNodeWithContentDescription("بازگشت").performClick()
        compose.onNode(hasText("مصوبات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("جست‌وجوی مصوبات").performClick()
        compose.onNodeWithTag("task_search_field").assertTextContains("احمدی")
        compose.onNode(hasText("گزارش فروش") and hasAnyAncestor(hasTestTag("task_search_modal"))).assertDoesNotExist()
        compose.onNodeWithContentDescription("پاک‌کردن جست‌وجو").performClick()
        compose.onNodeWithTag("task_search_field").performTextInput("رضایی")
        compose.onNode(hasText("گزارش فروش") and hasAnyAncestor(hasTestTag("task_search_modal"))).assertIsDisplayed()
    }
    @Test fun solarMonthArrowsCrossNowruzWithoutChangingSelection() {
        val initial=Solar.toDate(1405,1,15)!!
        var chosen: LocalDate?=null
        compose.setContent {PeygirTheme(mode="light") {SolarPicker(initial,{}, {chosen=it})}}
        compose.onNodeWithContentDescription("ماه قبل").performClick()
        compose.onNodeWithText("اسفند ۱۴۰۴").assertIsDisplayed()
        compose.onNodeWithContentDescription("ماه بعد").performClick()
        compose.onNodeWithText("فروردین ۱۴۰۵").assertIsDisplayed()
        compose.onNodeWithContentDescription("ماه بعد").performClick()
        compose.onNodeWithText("اردیبهشت ۱۴۰۵").assertIsDisplayed()
        compose.onNodeWithText("انتخاب",substring=false).performClick()
        assertEquals(initial,chosen)
    }
    @Test fun solarPickerDisablesNavigationOutsideSupportedYears() {
        compose.setContent {PeygirTheme(mode="light") {SolarPicker(Solar.toDate(1200,1,1)!!,{}, {})}}
        compose.onNodeWithContentDescription("ماه قبل").assertIsNotEnabled()
        compose.onNodeWithContentDescription("ماه بعد").performClick()
        compose.onNodeWithText("اردیبهشت ۱۲۰۰").assertIsDisplayed()
        compose.onNodeWithContentDescription("ماه قبل").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("ماه قبل").assertIsNotEnabled()
    }
    @Test fun createsTaskWithOwnerAndSolarDateThenChangesStatus() {
        start()
        compose.onNodeWithText("ثبت مصوبه",useUnmergedTree=true).performClick()
        compose.onNodeWithText("عنوان مصوبه *").performTextInput("تهیه گزارش ماهانه")
        compose.onNodeWithText("مسئول انجام *").performTextInput("آقای احمدی")
        compose.onNodeWithText("سررسید - ${Solar.label(LocalDate.now().plusDays(1))}").performClick()
        compose.onNodeWithText("تاریخ؛ مانند ۱۴۰۵/۰۸/۱۵").performTextClearance()
        compose.onNodeWithText("تاریخ؛ مانند ۱۴۰۵/۰۸/۱۵").performTextInput("۱۴۰۵/۰۸/۱۵")
        compose.onNodeWithText("انتخاب",substring=false).performClick()
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().tasks.isNotEmpty()}
        compose.waitUntil(15000) {compose.onAllNodesWithText("مصوبهٔ تازه").fetchSemanticsNodes().isEmpty()}
        assertEquals("آقای احمدی",store.snapshot().tasks.single().owner)
        assertEquals(Solar.toDate(1405,8,15)!!.toEpochDay(),store.snapshot().tasks.single().due)
        compose.onNode(hasText("مصوبات",substring=false) and hasClickAction()).performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("تهیه گزارش ماهانه").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(15000) {compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).fetchSemanticsNodes().isEmpty()}
        compose.mainClock.advanceTimeBy(6000)
        compose.onNode(hasText("مصوبات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("وضعیت و گزینه‌های تهیه گزارش ماهانه").performClick()
        compose.onNode(hasText("در حال انجام") and hasAnyAncestor(isPopup())).performSemanticsAction(SemanticsActions.OnClick) {it()}
        compose.waitUntil(15000) {store.snapshot().tasks.single().status==Status.ACTIVE}
    }
    @Test fun createsMeetingAndPersistsNotes() {
        start()
        compose.onNode(hasText("جلسات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithContentDescription("جلسهٔ تازه").performClick()
        compose.onNodeWithText("عنوان جلسه *").performTextInput("شورای راهبری")
        compose.onNodeWithTag("meeting_start_time").performScrollTo().performClick()
        compose.runOnIdle {val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.TimePickerDialog;dialog.updateTime(9,0);dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()}
        compose.onNodeWithTag("meeting_more_details").performScrollTo().performClick()
        compose.onNodeWithText("یادداشت جلسه").performScrollTo().performTextInput("بررسی برنامهٔ فصل آینده")
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().meetings.isNotEmpty()}
        assertEquals("بررسی برنامهٔ فصل آینده",store.snapshot().meetings.single().notes)
        compose.waitUntil(15000) {compose.onAllNodesWithText("شورای راهبری").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("شورای راهبری").performClick()
        compose.onNodeWithContentDescription("گزینه‌های جلسه").performClick();compose.onNodeWithText("ویرایش جلسه").assertIsDisplayed()
    }
    @Test fun deletesMeetingFromListOnlyAfterConfirmationAndKeepsLinkedTask() {
        val meeting=store.saveMeeting(Meeting(title="جلسهٔ حذف از فهرست"))
        store.saveTask(Resolution(title="مصوبهٔ محفوظ",owner="احمدی",meetingId=meeting),listOf(System.currentTimeMillis()+3600000))
        val before=store.snapshot()
        start();compose.onNode(hasText("جلسات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithTag("meeting_mode_MONTH").performClick()
        compose.onNodeWithContentDescription("گزینه‌های جلسهٔ جلسهٔ حذف از فهرست").performClick();compose.onNodeWithText("حذف جلسهٔ جلسهٔ حذف از فهرست").performClick()
        compose.onNodeWithText("حذف جلسه؟").assertIsDisplayed()
        assertEquals(1,store.snapshot().meetings.size)
        compose.onNodeWithText("انصراف",substring=false).performClick()
        assertEquals(before,store.snapshot())
        compose.onNodeWithContentDescription("گزینه‌های جلسهٔ جلسهٔ حذف از فهرست").performClick();compose.onNodeWithText("حذف جلسهٔ جلسهٔ حذف از فهرست").performClick()
        compose.onNodeWithText("حذف",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().meetings.isEmpty() && compose.onAllNodesWithText("جلسه‌ای در این بازه نیست").fetchSemanticsNodes().isNotEmpty()}
        val after=store.snapshot()
        assertEquals(before.tasks.single().copy(meetingId=null),after.tasks.single())
        assertEquals(before.reminders,after.reminders)
    }
    @Test fun deletesFromDetailsAndEditorThenReturnsToMeetingList() {
        store.saveMeeting(Meeting(title="حذف از جزئیات"))
        store.saveMeeting(Meeting(title="حذف از ویرایش"))
        start();compose.onNode(hasText("جلسات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithText("حذف از جزئیات",substring=false).performScrollTo().performClick()
        compose.onNodeWithContentDescription("گزینه‌های جلسه").performClick();compose.onNodeWithText("حذف جلسه",substring=false).performClick()
        compose.onNodeWithText("حذف",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().meetings.size==1 && compose.onAllNodesWithTag("meeting_calendar").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("حذف از ویرایش",substring=false).performScrollTo().performClick()
        compose.onNodeWithContentDescription("گزینه‌های جلسه").performClick();compose.onNodeWithText("ویرایش جلسه",substring=false).performClick()
        compose.onNodeWithText("عنوان جلسه *").performTextReplacement("ویرایش ثبت‌نشده")
        compose.onNodeWithContentDescription("گزینه‌های فرم").performClick();compose.onNodeWithText("حذف جلسه",substring=false).performClick()
        compose.onNodeWithText("انصراف",substring=false).performClick()
        compose.onNodeWithText("ویرایش ثبت‌نشده",substring=false).assertExists()
        compose.onNodeWithContentDescription("گزینه‌های فرم").performClick();compose.onNodeWithText("حذف جلسه",substring=false).performClick()
        compose.onNodeWithText("حذف",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().meetings.isEmpty() && compose.onAllNodesWithTag("meeting_calendar").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("عنوان جلسه *").assertDoesNotExist()
    }
    private fun enterProposalText(text: String) {
        compose.onNodeWithContentDescription("گزینه‌های فرم").performClick();compose.onNodeWithText("ورود چند مصوبه از متن",substring=false).performClick()
        compose.onNodeWithText("ورود چند مصوبه از متن").performClick()
        compose.onNodeWithText("متن ورودی",substring=false).performTextReplacement(text)
        compose.onNodeWithText("استخراج مصوبات",substring=false).performScrollTo().performClick()
    }
    @Test fun cancelledProposalNeverReturnsOnNextRegistration() {
        val text="تهیه گزارش فروش؛ مسئول: آقای احمدی؛ مهلت: ۱۴۰۵/۰۸/۱۵"
        RuntimeEnvironment.getApplication().getSharedPreferences("drafts",0).edit().putString("prepared",text).putString("text",text).commit()
        start();compose.onNodeWithText("ثبت مصوبه").performClick()
        compose.onNodeWithText("تهیه گزارش فروش",substring=false).assertDoesNotExist()
        enterProposalText(text)
        compose.onNodeWithText("آقای احمدی",substring=false).assertExists()
        assertTrue(store.snapshot().tasks.isEmpty())
        compose.onNodeWithContentDescription("بستن").performClick()
        compose.onNodeWithText("ثبت مصوبه").performClick()
        assertEquals("",compose.onNodeWithText("عنوان مصوبه *").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        assertEquals("",compose.onNodeWithText("مسئول انجام *").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
        compose.onNodeWithText("تهیه گزارش فروش",substring=false).assertDoesNotExist()
        enterProposalText(text)
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().tasks.size==1}
        compose.waitUntil(15000) {compose.onAllNodesWithText("مصوبهٔ تازه").fetchSemanticsNodes().isEmpty()}
    }
    @Test fun registrationStartsEmptyAfterCancellingEachInputMode() {
        start()
        repeat(2) {
            compose.onNodeWithText("ثبت مصوبه").performClick()
            compose.onNodeWithText("عنوان مصوبه *").performTextReplacement("عنوان موقت")
            compose.onNodeWithText("مسئول انجام *").performTextReplacement("مسئول موقت")
            compose.onNodeWithContentDescription("بستن").performClick()
            compose.onNodeWithText("ثبت مصوبه").performClick()
            assertEquals("",compose.onNodeWithText("عنوان مصوبه *").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            assertEquals("",compose.onNodeWithText("مسئول انجام *").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            compose.onNodeWithContentDescription("بستن").performClick()
        }
        assertTrue(store.snapshot().tasks.isEmpty())
    }
    @Test fun startupDoesNotReportRoutineLoadCancellationAsDataFailure() {
        start();compose.onNodeWithText("خواندن اطلاعات ناموفق بود",substring=true).assertDoesNotExist()
        compose.onNodeWithText("اطلاعات خوانده نشد.").assertDoesNotExist()
    }
    @Test fun inputMethodsShareTheSameTaskForm() {
        start();compose.onNodeWithText("ثبت مصوبه").performClick()
        compose.onNodeWithTag("input_write").assertDoesNotExist()
        compose.onNodeWithTag("input_voice").assertDoesNotExist()
        compose.onNodeWithContentDescription("ورود صوتی - عنوان مصوبه").assertIsDisplayed()
        compose.onNodeWithContentDescription("ورود صوتی - مسئول انجام").assertIsDisplayed()
        compose.onNodeWithText("عنوان مصوبه *").assertExists()
        compose.onNodeWithTag("input_photo").assertDoesNotExist()
        compose.onNodeWithText("گرفتن عکس").assertDoesNotExist()
        compose.onNodeWithText("انتخاب عکس").assertDoesNotExist()
        compose.onNodeWithText("Mistral آنلاین").assertDoesNotExist()
        compose.onNodeWithText("مسئول انجام *").assertExists()
        compose.onNodeWithText("اولویت",substring=false).assertDoesNotExist()
        compose.onNodeWithTag("task_editor_more").performScrollTo().performClick()
        compose.onNodeWithText("فوری",substring=false).assertExists()
    }
    @Test fun batchMovesToNextProposalOnlyAfterExplicitSave() {
        val text="گزارش فروش؛ مسئول: احمدی؛ مهلت: ۱۴۰۵/۰۸/۱۵\nارسال قرارداد؛ مسئول: رضایی؛ مهلت: ۱۴۰۵/۰۸/۱۶"
        start();compose.onNodeWithText("ثبت مصوبه").performClick();enterProposalText(text)
        compose.waitUntil(15000) {compose.onAllNodesWithText("گزارش فروش").fetchSemanticsNodes().isNotEmpty()}
        assertTrue("No task should be persisted before explicit save: ${store.snapshot().tasks}",store.snapshot().tasks.isEmpty())
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().tasks.size==1 && compose.onAllNodesWithText("ارسال قرارداد").fetchSemanticsNodes().isNotEmpty()}
        compose.waitUntil(15000) {compose.onAllNodes(hasText("ذخیره") and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().tasks.size==2}
        compose.waitUntil(15000) {compose.onAllNodesWithText("مصوبهٔ تازه").fetchSemanticsNodes().isEmpty()}
        assertEquals(setOf("احمدی","رضایی"),store.snapshot().tasks.map{it.owner}.toSet())
    }
    @Test fun dailyReviewTurnsPagesAndCreatesForSelectedDay() {
        val tomorrow=LocalDate.now().plusDays(1).toEpochDay()
        store.saveTask(Resolution(title="کار فردا",owner="احمدی",due=tomorrow),emptyList())
        start();compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("روز بعد").performClick()
        compose.onNodeWithTag("review_day_$tomorrow").assertIsDisplayed()
        compose.onNodeWithText("کار فردا").assertIsDisplayed()
        compose.onNode(hasText("مصوبات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("review_day_$tomorrow").assertIsDisplayed()
        compose.onNodeWithContentDescription("ثبت مصوبه برای این روز").performClick()
        compose.onNodeWithText("سررسید - ${Solar.label(LocalDate.ofEpochDay(tomorrow))}").assertExists()
    }
    @Test fun dailyReviewSupportsHorizontalPaging() {
        start();compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        val today=LocalDate.now().toEpochDay()
        compose.onNodeWithTag("daily_pager").performTouchInput {swipeLeft()}
        compose.waitUntil(15000) {compose.onAllNodesWithTag("review_day_$today").fetchSemanticsNodes().none{it.boundsInRoot.width>400f}}
        compose.onNodeWithText("امروز",substring=false).performClick()
        compose.onNodeWithTag("review_day_$today").assertIsDisplayed()
    }
    @Test fun reviewExcludesAudioAndTheEditorArchiveOffersPlaybackWithoutStartingRecognition() {
        store.saveRecording(Recording(path="/private/review.wav",created=System.currentTimeMillis(),seconds=12))
        start();compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        compose.onNodeWithText("یادداشت صوتی",substring=false).assertDoesNotExist()
        compose.onNodeWithText("پیگیری",useUnmergedTree=true).performClick()
        compose.onNodeWithText("ثبت مصوبه",substring=false).performClick()
        compose.onNodeWithContentDescription("گزینه‌های فرم").performClick();compose.onNodeWithText("صداهای ذخیره‌شده",substring=false).performClick()
        compose.onNodeWithText("صداهای ذخیره‌شده").performScrollTo().performClick()
        compose.onNodeWithContentDescription("پخش صدا").assertExists()
        compose.onNodeWithText("تبدیل به متن",substring=false).assertExists()
        compose.onNodeWithText("در حال تبدیل صدا به متن…").assertDoesNotExist()
        assertTrue(store.snapshot().tasks.isEmpty())
    }
    @Test fun reschedulesTaskAndShowsItsHistory() {
        store.saveTask(Resolution(title="مصوبهٔ معوق",owner="احمدی",due=LocalDate.now().minusDays(2).toEpochDay()),emptyList())
        start();compose.onNodeWithText("مصوبهٔ معوق",substring=false).performClick()
        compose.onNodeWithContentDescription("گزینه‌های مصوبه").performClick();compose.onNodeWithText("تمدید مهلت",substring=false).performClick()
        compose.onNodeWithText("دلیل (اختیاری)").performTextInput("نیاز به فرصت بیشتر")
        compose.onNodeWithText("سه روز",substring=false).performClick()
        compose.onNodeWithText("ثبت تغییر موعد").performClick()
        compose.waitUntil(15000) {store.snapshot().history.size==1}
        compose.waitUntil(15000) {compose.onAllNodesWithText("جزئیات مصوبه").fetchSemanticsNodes().isEmpty()}
        assertEquals(1,store.snapshot().tasks.single().delayCount)
        compose.onNodeWithText("مصوبهٔ معوق",substring=false).performClick()
        compose.onNodeWithTag("task_summary_more").performScrollTo().performClick()
        compose.onNodeWithText("دلیل: نیاز به فرصت بیشتر").performScrollTo().assertIsDisplayed()
    }
    @Test fun overdueAndCompletedFiltersSelectCorrectTasks() {
        val today=LocalDate.now().toEpochDay()
        store.saveTask(Resolution(title="کار عقب‌افتاده",owner="احمدی",due=today-1),emptyList())
        store.saveTask(Resolution(title="کار تمام‌شده",owner="رضایی",due=today-5,status=Status.DONE),emptyList())
        start()
        compose.onNodeWithText("معوق",substring=false).performClick()
        compose.onNodeWithText("کار عقب‌افتاده").assertIsDisplayed()
        compose.onNodeWithText("کار تمام‌شده").assertDoesNotExist()
        compose.onNodeWithTag("task_filter_button").performClick()
        compose.onNodeWithTag("task_filter_done").performClick()
        compose.onNodeWithText("کار تمام‌شده").assertIsDisplayed()
        compose.onNodeWithText("کار عقب‌افتاده").assertDoesNotExist()
    }
    @Test fun widgetTaskShortcutUsesSelectedDayAndDefaultsToPreviousDayReminder() {
        val day=LocalDate.now().plusDays(5).toEpochDay()
        val request=androidx.compose.runtime.mutableStateOf<WidgetLaunch?>(WidgetLaunch(WidgetLaunch.NEW_TASK,day))
        compose.setContent {PeygirApp(widgetLaunch=request.value,consume={request.value=null})}
        compose.waitUntil(15000) {compose.onAllNodesWithText("مصوبهٔ تازه").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("سررسید - ${Solar.label(LocalDate.ofEpochDay(day))}").assertExists()
        compose.onNodeWithText("عنوان مصوبه *").performTextInput("از ویجت")
        compose.onNodeWithText("مسئول انجام *").performTextInput("احمدی")
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().tasks.size==1}
        val data=store.snapshot();assertEquals(day,data.tasks.single().due)
        assertEquals(LocalDate.ofEpochDay(day-1),java.time.Instant.ofEpochMilli(data.reminders.single().at).atZone(java.time.ZoneId.systemDefault()).toLocalDate())
    }
    @Test fun widgetMeetingShortcutOpensFreshMeetingOnSelectedDay() {
        val day=LocalDate.now().minusDays(3).toEpochDay()
        val request=androidx.compose.runtime.mutableStateOf<WidgetLaunch?>(WidgetLaunch(WidgetLaunch.NEW_MEETING,day))
        compose.setContent {PeygirApp(widgetLaunch=request.value,consume={request.value=null})}
        compose.waitUntil(15000) {compose.onAllNodesWithText("عنوان جلسه *").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("عنوان جلسه *").performTextInput("جلسه از ویجت")
        compose.onNodeWithTag("meeting_start_time").performScrollTo().performClick()
        compose.runOnIdle {val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog() as android.app.TimePickerDialog;dialog.updateTime(9,0);dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()}
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().meetings.size==1}
        assertEquals(day,store.snapshot().meetings.single().date)
    }
    @Test fun widgetReviewShortcutCanChangeDayWhileAppIsAlreadyOpen() {
        val today=LocalDate.now().toEpochDay()
        val request=androidx.compose.runtime.mutableStateOf<WidgetLaunch?>(WidgetLaunch(WidgetLaunch.REVIEW,today+3))
        compose.setContent {PeygirApp(widgetLaunch=request.value,consume={request.value=null})}
        compose.waitUntil(15000) {compose.onAllNodesWithTag("review_day_${today+3}").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle {request.value=WidgetLaunch(WidgetLaunch.REVIEW,today-2)}
        compose.waitUntil(15000) {compose.onAllNodesWithTag("review_day_${today-2}").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun convertedVoiceUsesTitleAndOwnerKeepsManualDueAndDeletesTheSourceOnSave() {
        val context=RuntimeEnvironment.getApplication()
        val audio=java.io.File(java.io.File(context.filesDir,"recordings").apply{mkdirs()},"voice-ui.wav").apply{writeBytes(ByteArray(100))}
        val transcript="عنوان تهیه گزارش فروش مسئول آقای احمدی مهلت ۱۴۰۵/۰۸/۱۵"
        store.saveRecording(Recording(path=audio.absolutePath,created=System.currentTimeMillis(),seconds=2,transcript=transcript))
        val request=androidx.compose.runtime.mutableStateOf<WidgetLaunch?>(WidgetLaunch(WidgetLaunch.NEW_TASK,LocalDate.now().toEpochDay()))
        compose.setContent {PeygirApp(widgetLaunch=request.value,consume={request.value=null})}
        compose.waitUntil(15000){compose.onAllNodesWithText("عنوان مصوبه *").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("گزینه‌های فرم").performClick();compose.onNodeWithText("صداهای ذخیره‌شده",substring=false).performClick()
        compose.onNodeWithText("صداهای ذخیره‌شده").performScrollTo().performClick()
        compose.onNodeWithTag("task_editor_more").performScrollTo().performClick()
        compose.onNodeWithText("مهم",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("افزودن متن",substring=false).performScrollTo().performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("تهیه گزارش فروش",substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("ورود چند مصوبه از متن").assertDoesNotExist()
        compose.onNodeWithText("سررسید - ${Solar.label(LocalDate.now())}").assertExists()
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().tasks.size==1}
        assertEquals("آقای احمدی",store.snapshot().tasks.single().owner)
        assertEquals(Priority.HIGH,store.snapshot().tasks.single().priority)
        assertEquals(LocalDate.now().toEpochDay(),store.snapshot().tasks.single().due)
        assertTrue(store.snapshot().recordings.isEmpty());assertFalse(audio.exists())
    }

}
