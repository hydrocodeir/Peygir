package ir.peygir.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w360dp-h740dp-mdpi")
class SimpleExperienceFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: Store
    @Before fun setup(){val c=RuntimeEnvironment.getApplication();Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null);c.deleteDatabase("peygir.db");c.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(c,null);store=Store.get(c)}
    @After fun cleanup(){store.close();OrgAccess.set(RuntimeEnvironment.getApplication(),null)}
    private fun open(id: Long) {
        val request=mutableStateOf(id)
        compose.setContent{PeygirApp(requestTask=request.value,consume={request.value=0})}
        compose.waitUntil(15000){compose.onAllNodesWithTag("task_summary").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun readFirstThenEditKeepsHiddenTaskFieldsAndTheOriginalIdentity() {
        val id=store.saveTask(Resolution(title="مصوبهٔ اصلی",owner="احمدی",description="شرح محفوظ",due=LocalDate.now().toEpochDay()+7,priority=Priority.HIGH),emptyList())
        open(id)
        compose.onNodeWithText("عنوان مصوبه *").assertDoesNotExist();compose.onNodeWithText("شرح محفوظ").assertDoesNotExist()
        compose.onNodeWithContentDescription("گزینه‌های مصوبه").performClick();compose.onNodeWithText("ویرایش",substring=false).performClick()
        compose.onNodeWithText("عنوان مصوبه *").performTextReplacement("عنوان تازه")
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000){store.snapshot().tasks.single().title=="عنوان تازه"}
        assertEquals(id,store.snapshot().tasks.single().id);assertEquals("شرح محفوظ",store.snapshot().tasks.single().description);assertEquals(Priority.HIGH,store.snapshot().tasks.single().priority)
    }
    @Test fun managerCanApproveFromTheSinglePrimaryAction() {
        OrgAccess.set(compose.activity,SharedSession("https://test.invalid","","","","رئیس","manager","chief"))
        val id=store.saveTask(Resolution(title="گزارش آماده",owner="احمدی",assignee="a",status=Status.REVIEW),emptyList())
        open(id);compose.onNodeWithTag("task_primary_action").assertTextContains("تأیید نهایی انجام").performClick()
        compose.waitUntil(15000){store.snapshot().tasks.single().status==Status.DONE}
        assertEquals(id,store.snapshot().tasks.single().id)
    }
    @Test fun managerSeesAnApprovalBadgeOnTheDefaultMeetingsPage() {
        OrgAccess.set(compose.activity,SharedSession("https://test.invalid","","","","رئیس","manager","chief"))
        store.saveTask(Resolution(title="تأیید لازم",owner="احمدی",assignee="a",status=Status.REVIEW),emptyList())
        compose.setContent{PeygirApp()}
        compose.waitUntil(15000){compose.onAllNodesWithTag("meeting_calendar").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("approval_badge",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithText("پیگیری",useUnmergedTree=true).performClick()
        compose.onNodeWithText("تأییدها",substring=false).performClick()
        compose.onNodeWithTag("task_filter_button").assertTextContains("منتظر تأیید")
    }
    @Test fun settingsHideTechnicalFieldsButKeepPermissionProblemsVisible() {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication() as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        compose.setContent{PeygirTheme("light"){SettingsScreen("light",{},{})}}
        compose.onNodeWithText("اعلان‌ها خاموش است",substring=true).assertIsDisplayed()
        compose.onNodeWithTag("dynamic_colors").assertDoesNotExist();compose.onNodeWithText("فضای سازمانی شرکت").assertDoesNotExist()
        compose.onNodeWithTag("settings_appearance").performScrollTo().performClick();compose.onNodeWithTag("dynamic_colors").assertExists()
        compose.onNodeWithTag("settings_appearance").performScrollTo().performClick();compose.onNodeWithTag("dynamic_colors").assertDoesNotExist()
        compose.onNodeWithTag("settings_about").performScrollTo().performClick()
        compose.onNodeWithText("اعلان‌ها خاموش است",substring=true).assertIsDisplayed()
    }
}
