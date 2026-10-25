package ir.peygir.app

import android.app.Application
import androidx.activity.ComponentActivity
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
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class VoiceInputFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: Store
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db");store=Store.get(context)
        context.getSharedPreferences("settings",0).edit().clear().putBoolean("daily",false).commit()
    }
    @After fun close() {store.close()}
    @Test fun recordedTranscriptFillsRealFieldsAndApplyingItAgainRepairsManualChanges() {
        val r=Recording(id=1,path="/unused.wav",created=1,seconds=1,transcript="عنوانش تهیه گزارش فروش\nمسؤول آقای احمدی")
        compose.setContent{PeygirTheme(mode="light"){TaskEditor(Resolution(),emptyList(),emptyList(),false,{},{_,_->},null,recordings=listOf(r),recordingId=1)}}
        compose.onNodeWithText("تبدیل به متن",substring=false).performClick()
        compose.onNodeWithText("عنوان مصوبه *").assertTextContains("تهیه گزارش فروش")
        compose.onNodeWithText("مسئول انجام *").assertTextContains("آقای احمدی")
        compose.onNodeWithText("ورود چند مصوبه از متن").assertDoesNotExist()
        compose.onNodeWithText("عنوان مصوبه *").performTextClearance()
        compose.onNodeWithText("بازبینی متن گفتار").performScrollTo().performClick()
        compose.onNodeWithText("اعمال متن گفتار").performScrollTo().performClick()
        compose.onNodeWithText("عنوان مصوبه *").assertTextContains("تهیه گزارش فروش")
        compose.onNodeWithText("سررسید | ${Solar.label(LocalDate.now().plusDays(1))}").assertExists()
    }
}
