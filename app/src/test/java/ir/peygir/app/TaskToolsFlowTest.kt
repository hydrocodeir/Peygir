package ir.peygir.app

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class TaskToolsFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: Store
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db");store=Store.get(context)
        context.getSharedPreferences("settings",0).edit().clear().putBoolean("daily",false).commit()
    }
    @After fun close() {store.close()}
    private fun start() {
        compose.setContent {PeygirApp(initialSection=0)}
        compose.waitUntil(15000) {compose.onAllNodesWithText("برای پیگیری").fetchSemanticsNodes().isNotEmpty()}
    }
    private fun open(title: String) {start();compose.onNodeWithText(title,substring=false).performClick()}
    private fun edit() {compose.onNodeWithContentDescription("گزینه‌های مصوبه").performClick();compose.onNodeWithText("ویرایش",substring=false).performClick()}
    @Test fun firstAndLastNameSuggestionsCanBeSelectedWithoutCreatingAContact() {
        store.saveTask(Resolution(title="گزارش",owner="علی احمدی"),emptyList())
        store.saveTask(Resolution(title="قرارداد",owner="رضا احمدی"),emptyList())
        start();compose.onNodeWithText("ثبت مصوبه",useUnmergedTree=true).performClick()
        val field=compose.onNodeWithText("مسئول انجام *")
        field.performTextInput("احمدی")
        compose.onNodeWithTag("person_suggestion_علی احمدی").assertExists()
        compose.onNodeWithTag("person_suggestion_رضا احمدی").assertExists()
        field.performTextClearance();field.performTextInput("علی")
        compose.onNodeWithTag("person_suggestion_رضا احمدی").assertDoesNotExist()
        compose.onNodeWithTag("person_suggestion_علی احمدی").performClick()
        field.assertTextContains("علی احمدی")
        assertEquals(2,store.snapshot().tasks.size)
    }
    @Test fun ownerDialogShowsOnlyMatchingOpenTasksAndCanFilterOverdueWork() {
        val day=LocalDate.now().toEpochDay()
        val active=store.saveTask(Resolution(title="گزارش فروش",owner="علی احمدی",due=day+1),emptyList())
        val late=store.saveTask(Resolution(title="قرارداد عقب‌افتاده",owner="آقای علي احمدي",due=day-1),emptyList())
        val other=store.saveTask(Resolution(title="مسئول دیگر",owner="رضا احمدی",due=day-2),emptyList())
        val done=store.saveTask(Resolution(title="پایان‌یافته",owner="علی احمدی",due=day,status=Status.DONE),emptyList())
        open("گزارش فروش")
        compose.onNodeWithTag("owner_open_tasks").performScrollTo().performClick()
        compose.onNodeWithTag("owner_task_$active").assertExists();compose.onNodeWithTag("owner_task_$late").assertExists()
        compose.onNodeWithTag("owner_task_$other").assertDoesNotExist();compose.onNodeWithTag("owner_task_$done").assertDoesNotExist()
        compose.onNode(hasText("معوق",substring=false) and hasAnyAncestor(hasTestTag("owner_tasks"))).performClick()
        compose.onNodeWithTag("owner_task_$active").assertDoesNotExist();compose.onNodeWithTag("owner_task_$late").assertExists()
        compose.onNodeWithContentDescription("بستن مصوبات مسئول").performClick()
        compose.onNodeWithTag("task_summary").assertExists()
    }
    @Test fun followUpSavesAndReopensWhileUnsavedEditorFieldsRemainIntact() {
        store.saveTask(Resolution(title="گزارش فروش",owner="علی احمدی"),emptyList())
        open("گزارش فروش")
        edit()
        compose.onNodeWithText("عنوان مصوبه *").performTextClearance()
        compose.onNodeWithText("عنوان مصوبه *").performTextInput("عنوان ثبت‌نشده")
        compose.onNodeWithTag("task_editor_more").performScrollTo().performClick()
        compose.onNodeWithText("ثبت نتیجهٔ پیگیری").performScrollTo().performClick()
        compose.onNodeWithText("نتیجهٔ کوتاه پیگیری").performTextInput("منتظر تأیید مالی است")
        compose.onNodeWithText("ثبت نتیجه",substring=false).performScrollTo().performClick()
        compose.waitUntil(15000) {store.snapshot().followUps.size==1 && compose.onAllNodesWithText("آخرین پیگیری:",substring=true).fetchSemanticsNodes().isNotEmpty()}
        assertEquals("گزارش فروش",store.snapshot().tasks.single().title)
        compose.onNodeWithText("عنوان مصوبه *").assertTextContains("عنوان ثبت‌نشده")
        compose.onNodeWithContentDescription("بستن").performClick()
        compose.onNodeWithText("گزارش فروش",substring=false).performClick()
        compose.onNodeWithText("منتظر تأیید مالی است").assertExists()
    }
    @Test fun shareButtonOpensChooserWithVisibleTitleOwnerAndDueDateOnly() {
        store.saveTask(Resolution(title="گزارش فروش",owner="علی احمدی",description="شرح خصوصی"),emptyList())
        open("گزارش فروش")
        compose.onNodeWithContentDescription("گزینه‌های مصوبه").performClick();compose.onNodeWithText("اشتراک‌گذاری خلاصهٔ مصوبه").performClick()
        val chooser=shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER,chooser.action)
        @Suppress("DEPRECATION") val target=chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(TaskShare.text(store.snapshot().tasks.single()),target.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(target.getStringExtra(Intent.EXTRA_TEXT)!!.contains("شرح خصوصی"))
    }
    @Test fun backgroundNotificationActionRefreshesOpenEditorWithoutDiscardingTypedTitle() {
        val id=store.saveTask(Resolution(title="گزارش فروش",owner="علی احمدی"),emptyList())
        open("گزارش فروش")
        edit()
        compose.onNodeWithText("عنوان مصوبه *").performTextClearance()
        compose.onNodeWithText("عنوان مصوبه *").performTextInput("عنوان ثبت‌نشده")
        compose.onNodeWithTag("task_editor_more").performScrollTo().performClick()
        store.activateNotification(id,"test-action",emptyList())
        compose.runOnIdle {assertTrue(Reminders.handleAction(compose.activity,Intent().setAction(ReminderActionReceiver.DONE).putExtra("task_id",id).putExtra("token","test-action")))}
        compose.waitUntil(15000) {runCatching {compose.onNodeWithTag("task_status_DONE").assertIsSelected();true}.getOrDefault(false)}
        compose.onNodeWithText("عنوان مصوبه *").assertTextContains("عنوان ثبت‌نشده")
    }
}
