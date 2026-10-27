package ir.peygir.app

import android.app.AlarmManager
import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class FirstRunTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val c get()=RuntimeEnvironment.getApplication()
    @Before fun setup() {
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        c.deleteDatabase("peygir.db")
        listOf("first_run","shared_private","organization_access").forEach{c.getSharedPreferences(it,0).edit().clear().commit()}
    }
    @After fun close() {Store.get(c).close()}
    @Test fun freshInstallReachesMandatoryLoginAndShowsOnlyTheThreeCredentialsFields() {
        compose.setContent{PeygirEntry {Text("APP CONTENT")}}
        compose.waitUntil(15000){compose.onAllNodesWithText("نام کاربری").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("نشانی سرویس").assertExists();compose.onNodeWithText("رمز ورود").assertExists()
        compose.onNodeWithText("ورود",substring=false).assertIsNotEnabled()
        compose.onNodeWithText("APP CONTENT").assertDoesNotExist()
        compose.onNodeWithContentDescription("بستن").assertDoesNotExist()
        assertFalse(c.getSharedPreferences("first_run",0).getBoolean("complete",false))
    }
    @Test fun upgradeWithExistingDataSkipsSetupAndKeepsRecords() {
        val store=Store.get(c);store.saveMeeting(Meeting(title="جلسهٔ قبلی"))
        compose.setContent{PeygirEntry {Text("APP CONTENT")}}
        compose.waitUntil(15000){compose.onAllNodesWithText("APP CONTENT").fetchSemanticsNodes().isNotEmpty()}
        assertEquals("جلسهٔ قبلی",store.snapshot().meetings.single().title)
        assertTrue(c.getSharedPreferences("first_run",0).getBoolean("complete",false))
    }
    @Test @Config(sdk=[35]) fun deniedPermissionsCanBeDeferredAndExactAccessUsesTheSystemScreenBeforeLogin() {
        shadowOf(c as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        compose.setContent{PeygirTheme("light"){FirstRunScreen(autoAsk=false){}}}
        compose.onNodeWithTag("first_run_permissions").assertIsDisplayed()
        compose.onNodeWithTag("first_run_later").performClick()
        compose.onNodeWithText("هشدارها و یادآوری‌های دقیق").assertExists()
        compose.onNodeWithTag("first_run_allow").performClick()
        val request=shadowOf(compose.activity).nextStartedActivity
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,request.action)
        assertEquals("package:${c.packageName}",request.data.toString())
        compose.onNodeWithTag("first_run_later").performClick()
        compose.onNodeWithText("نام کاربری").assertExists()
        assertEquals(FirstRun.LOGIN,FirstRun.stage(c))
    }
    @Test @Config(sdk=[35]) fun firstNotificationRequestUsesRuntimePermissionAndDoesNotRepeatAfterRecomposition() {
        shadowOf(c as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val generation=androidx.compose.runtime.mutableStateOf(0)
        compose.setContent{PeygirTheme("light"){androidx.compose.runtime.key(generation.value){FirstRunScreen{}}}}
        compose.waitForIdle()
        val first=shadowOf(compose.activity).lastRequestedPermission
        assertNotNull(first);assertTrue(first.requestedPermissions.contains(android.Manifest.permission.POST_NOTIFICATIONS));assertTrue(FirstRun.asked(c))
        compose.runOnIdle{generation.value++};compose.waitForIdle()
        assertSame(first,shadowOf(compose.activity).lastRequestedPermission)
    }
}
