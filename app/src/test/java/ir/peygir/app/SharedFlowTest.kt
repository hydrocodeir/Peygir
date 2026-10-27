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
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class SharedFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun setup() {
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        compose.activity.deleteDatabase("peygir.db")
        compose.activity.getSharedPreferences("shared_private",0).edit().clear().commit()
    }
    @After fun close() {Store.get(compose.activity).close()}
    @Test fun remoteUpdatesToUntouchedFieldsKeepTheUsersUnsavedTitle() {
        val initial=Resolution(id=1,title="عنوان اولیه",owner="احمدی")
        val task=mutableStateOf(initial)
        compose.setContent{PeygirTheme("light"){TaskEditor(task.value,emptyList(),emptyList(),false,{},{_,_->},null)}}
        compose.onNodeWithText("عنوان مصوبه *").performTextClearance();compose.onNodeWithText("عنوان مصوبه *").performTextInput("ویرایش من")
        compose.runOnIdle{task.value=initial.copy(owner="رضایی",due=initial.due+2,status=Status.ACTIVE)}
        compose.onNodeWithText("عنوان مصوبه *").assertTextContains("ویرایش من")
        compose.onNodeWithText("مسئول انجام *").assertTextContains("رضایی")
        compose.onNodeWithText("سررسید - ${Solar.label(LocalDate.ofEpochDay(initial.due+2))}").assertExists()
        compose.onNodeWithTag("incoming_editor_changes").assertDoesNotExist()
    }
    @Test fun conflictingOpenEditorChangesRequireAChoiceBeforeSaving() {
        val task=mutableStateOf(Resolution(id=1,title="عنوان اولیه",owner="احمدی"))
        compose.setContent{PeygirTheme("light"){TaskEditor(task.value,emptyList(),emptyList(),false,{},{_,_->},null)}}
        compose.onNodeWithText("عنوان مصوبه *").performTextClearance();compose.onNodeWithText("عنوان مصوبه *").performTextInput("ویرایش من")
        compose.runOnIdle{task.value=task.value.copy(title="ویرایش مدیر")}
        compose.onNodeWithTag("incoming_editor_changes").assertExists();compose.onNodeWithText("ذخیره",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("ادامه با ویرایش من").performScrollTo().performClick()
        compose.onNodeWithText("عنوان مصوبه *").assertTextContains("ویرایش من");compose.onNodeWithText("ذخیره",substring=false).assertIsEnabled()
        compose.runOnIdle{task.value=task.value.copy(title="تغییر دوم مدیر")}
        compose.onNodeWithText("استفاده از تغییرات تازه").performScrollTo().performClick()
        compose.onNodeWithText("عنوان مصوبه *").assertTextContains("تغییر دوم مدیر")
    }
    @Test fun sharedLoginIsInSettingsAndRejectsAnInsecureEndpointBeforeSendingPassword() {
        compose.setContent{PeygirTheme("light"){SharedPanel{}}}
        compose.onNodeWithText("ورود به فضای مشترک").performClick()
        compose.onNodeWithText("نشانی سرویس").performTextReplacement("http://insecure.test")
        compose.onNodeWithText("رمز ورود").performTextInput("test-password")
        compose.onNodeWithText("ورود",substring=false).performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("نشانی سرویس باید HTTPS و بدون مسیر اضافی باشد.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("نشانی سرویس باید HTTPS و بدون مسیر اضافی باشد.").assertExists()
    }
}
