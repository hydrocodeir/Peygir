package ir.peygir.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class SettingsFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: Store
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db");store=Store.get(context)
        context.getSharedPreferences("settings",0).edit().clear().commit()
    }
    @After fun cleanup() {store.close()}
    private fun openSettings() {
        compose.setContent {PeygirApp(initialSection=0)}
        compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("تنظیمات").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("تنظیمات").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("settings_notifications").fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun dynamicPaletteIsSavedAndBackupExportUsesSystemDocumentPicker() {
        openSettings()
        compose.onNodeWithTag("settings_notifications").performClick()
        compose.onNodeWithText("ساعت مرور - ۰۸:۰۰").performScrollTo().assertExists()
        compose.onNodeWithTag("settings_appearance").performScrollTo().performClick()
        compose.onNodeWithTag("dynamic_colors").performScrollTo().performClick().assertIsOn()
        assertTrue(compose.activity.getSharedPreferences("settings",0).getBoolean("dynamic_colors",false))
        compose.onNodeWithTag("settings_backup").performScrollTo().performClick()
        compose.onNodeWithText("ذخیرهٔ فایل پشتیبان").performScrollTo().performClick()
        val request=shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_CREATE_DOCUMENT,request.intent.action);assertEquals("application/zip",request.intent.type)
        compose.runOnIdle {shadowOf(compose.activity).receiveResult(request.intent,Activity.RESULT_CANCELED,null)}
        compose.onNodeWithText("ذخیرهٔ فایل پشتیبان").assertIsEnabled()
    }
    @Test fun progressReminderSwitchPercentageAndUpdateEntryAreAccessible() {
        openSettings()
        compose.onNodeWithTag("settings_notifications").performClick()
        compose.onNodeWithTag("option_progress_enabled").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("option_progress_percent").assertIsNotEnabled()
        compose.onNodeWithTag("option_progress_enabled").performClick()
        compose.onNodeWithTag("option_progress_percent").performTextReplacement("۷۵")
        compose.onNodeWithTag("save_progress_percent").performScrollTo().performClick()
        assertEquals(75,UserOptions.read(compose.activity).progressPercent)
        compose.onNodeWithTag("option_task_reminders").performScrollTo().performClick().assertIsOff()
        assertFalse(UserOptions.read(compose.activity).taskReminders)
        compose.onNodeWithTag("settings_update").performScrollTo().performClick()
        compose.onNodeWithTag("check_app_update").performScrollTo().assertIsEnabled()
    }
    @Test fun restoreRequiresConfirmationCancelKeepsDataAndConfirmationRefreshesSettings() {
        val context=compose.activity
        store.saveTask(Resolution(title="از پشتیبان",owner="احمدی"),emptyList())
        context.getSharedPreferences("settings",0).edit().putString("theme","dark").putBoolean("dynamic_colors",true).commit()
        val file=File(context.cacheDir,"synthetic-backup.zip")
        file.outputStream().use {Backup.write(context,it)}
        store.deleteTask(store.snapshot().tasks.single().id)
        store.saveTask(Resolution(title="دادهٔ فعلی",owner="رضایی"),emptyList())
        context.getSharedPreferences("settings",0).edit().putString("theme","light").putBoolean("dynamic_colors",false).commit()
        openSettings()
        compose.onNodeWithTag("settings_backup").performScrollTo().performClick()
        fun selectBackup() {
            compose.onNodeWithText("بازیابی از فایل پشتیبان").performScrollTo().performClick()
            val request=shadowOf(compose.activity).nextStartedActivityForResult
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,request.intent.action)
            compose.runOnIdle {shadowOf(compose.activity).receiveResult(request.intent,Activity.RESULT_OK,Intent().setData(Uri.fromFile(file)))}
            compose.waitUntil(15000) {compose.onAllNodesWithText("بازیابی و جایگزینی").fetchSemanticsNodes().isNotEmpty()}
        }
        selectBackup();assertEquals("دادهٔ فعلی",store.snapshot().tasks.single().title)
        compose.onNodeWithText("انصراف").performClick();assertEquals("دادهٔ فعلی",store.snapshot().tasks.single().title)
        selectBackup();compose.onNodeWithText("بازیابی و جایگزینی").performClick()
        compose.waitUntil(15000) {store.snapshot().tasks.single().title=="از پشتیبان" && compose.onAllNodesWithText("بازیابی و جایگزینی").fetchSemanticsNodes().isEmpty()}
        // The database changes before the root settings callback replaces its composition.
        compose.waitUntil(15000) {compose.onAllNodesWithText("پشتیبان بازیابی شد.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("settings_appearance").performScrollTo().performClick()
        compose.onNodeWithTag("dynamic_colors").performScrollTo().assertIsOn()
        assertEquals("dark",context.getSharedPreferences("settings",0).getString("theme",null))
    }
}
