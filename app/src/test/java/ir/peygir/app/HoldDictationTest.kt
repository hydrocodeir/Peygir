package ir.peygir.app

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HoldDictationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val app: Context get()=RuntimeEnvironment.getApplication()
    private class Speech(private val context: Context): FieldSpeechEngine {
        var starts=0;var stops=0;@Volatile var transcriptions=0
        var active=false
        val files=mutableListOf<File>()
        var words="گزارش فروش"
        var gate: CountDownLatch?=null
        override fun start() {check(!active);starts++;active=true;files+=File.createTempFile("speech-",".wav",context.cacheDir).apply{writeBytes(ByteArray(80))}}
        override fun stop(): File? {if(!active)return null;active=false;stops++;return files.last()}
        override fun transcribe(file: File): String {transcriptions++;gate?.await(5,TimeUnit.SECONDS);return words}
    }
    @Before fun setup() {
        app.getSharedPreferences("settings",0).edit().clear().putString(DictationMode.PREFERENCE,"hold").commit()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
    }
    @After fun cleanup() {app.getSharedPreferences("settings",0).edit().clear().commit()}
    private fun mic()=compose.onNodeWithTag("hold_mic_عنوان")
    @Test fun holdRecordsWithoutDialogAndReleaseInsertsTextThenDeletesAudio() {
        val speech=Speech(app);val value=mutableStateOf("")
        val factory: (Context)->FieldSpeechEngine={speech}
        compose.setContent{CompositionLocalProvider(LocalFieldSpeechEngine provides factory){PeygirTheme("light"){
            Box(Modifier.padding(16.dp)){VoiceTextField(value.value,{value.value=it},label={androidx.compose.material3.Text("عنوان")},voiceLabel="عنوان",modifier=Modifier.fillMaxWidth().testTag("voice_field"))}
        }}}
        mic().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val field=compose.onNodeWithTag("voice_field").fetchSemanticsNode().boundsInRoot
        assertTrue(mic().fetchSemanticsNode().boundsInRoot.center.x<field.center.x)
        mic().performTouchInput{down(center)}
        compose.runOnIdle{assertEquals(1,speech.starts);assertTrue(speech.active);assertEquals("",value.value)}
        compose.onNodeWithText("پایان",substring=false).assertDoesNotExist()
        mic().performTouchInput{advanceEventTime(1000);up()}
        compose.waitUntil(5000){value.value==speech.words}
        compose.runOnIdle{assertFalse(speech.active);assertEquals(1,speech.stops);assertTrue(speech.files.none{it.exists()})}
        mic().assertIsEnabled()
    }
    @Test fun slidingOutsideOrSystemCancelDiscardsWithoutTranscribing() {
        val speech=Speech(app);var text="";val factory: (Context)->FieldSpeechEngine={speech}
        compose.setContent{CompositionLocalProvider(LocalFieldSpeechEngine provides factory){PeygirTheme("dark"){DictationButton("عنوان"){text=it}}}}
        mic().performTouchInput{down(center);advanceEventTime(500);moveTo(Offset(-80f,-80f));up()}
        compose.runOnIdle{assertEquals("",text);assertEquals(0,speech.transcriptions);assertFalse(speech.active);assertTrue(speech.files.none{it.exists()})}
        mic().performTouchInput{down(center);advanceEventTime(500);cancel()}
        compose.runOnIdle{assertEquals(2,speech.starts);assertEquals(0,speech.transcriptions);assertTrue(speech.files.none{it.exists()})}
    }
    @Test fun removingTheFormDuringConversionPreventsLateInsertionAndCleansTheFile() {
        val speech=Speech(app).apply{gate=CountDownLatch(1)}
        val visible=mutableStateOf(true);var inserted=false;val factory: (Context)->FieldSpeechEngine={speech}
        compose.setContent{CompositionLocalProvider(LocalFieldSpeechEngine provides factory){PeygirTheme("light"){if(visible.value) DictationButton("عنوان"){inserted=true}}}}
        mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.waitUntil(5000){speech.transcriptions==1}
        mic().assertIsNotEnabled()
        compose.runOnIdle{visible.value=false}
        compose.waitForIdle()
        speech.gate!!.countDown()
        compose.waitUntil(5000){speech.files.none{it.exists()}}
        compose.runOnIdle{assertFalse(inserted);assertFalse(speech.active)}
    }
    @Test fun backgroundingWhileHeldStopsAndDiscardsRecording() {
        val speech=Speech(app);var inserted=false;val factory: (Context)->FieldSpeechEngine={speech}
        compose.setContent{CompositionLocalProvider(LocalFieldSpeechEngine provides factory){PeygirTheme("light"){DictationButton("عنوان"){inserted=true}}}}
        mic().performTouchInput{down(center)}
        compose.runOnIdle{assertTrue(speech.active)}
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        assertFalse(speech.active);assertTrue(speech.files.none{it.exists()});assertEquals(0,speech.transcriptions);assertFalse(inserted)
    }
    @Test fun maximumDurationConvertsOnceEvenIfTheFingerIsStillDown() {
        val speech=Speech(app).apply{gate=CountDownLatch(1)};var text="";val factory: (Context)->FieldSpeechEngine={speech}
        compose.setContent{CompositionLocalProvider(LocalFieldSpeechEngine provides factory){PeygirTheme("light"){DictationButton("عنوان"){text=it}}}}
        mic().performTouchInput{down(center)}
        compose.mainClock.advanceTimeBy(60_100)
        compose.waitUntil(5000){speech.transcriptions==1}
        mic().assertIsNotEnabled()
        mic().performTouchInput{up()}
        speech.gate!!.countDown()
        compose.waitUntil(5000){text==speech.words}
        compose.runOnIdle{assertEquals(1,speech.stops);assertEquals(1,speech.transcriptions);assertTrue(speech.files.none{it.exists()})}
    }
    @Test fun emptySpeechKeepsTheOriginalTextAndAllowsAnotherHold() {
        val speech=Speech(app).apply{words=""};var text="متن قبلی";val factory: (Context)->FieldSpeechEngine={speech}
        compose.setContent{CompositionLocalProvider(LocalFieldSpeechEngine provides factory){PeygirTheme("light"){DictationButton("عنوان"){text=it}}}}
        mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.waitUntil(5000){compose.onAllNodesWithText("گفتاری تشخیص داده نشد؛ دوباره بگویید.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("گفتاری تشخیص داده نشد؛ دوباره بگویید.").assertIsDisplayed()
        compose.runOnIdle{assertEquals("متن قبلی",text);speech.words="متن تازه"}
        mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.waitUntil(5000){text=="متن تازه"}
        compose.runOnIdle{assertTrue(speech.files.none{it.exists()})}
    }
    @Test fun talkBackClickHasAnExplicitEndButtonAndTapModeRemainsAvailable() {
        val speech=Speech(app);var text="";val factory: (Context)->FieldSpeechEngine={speech}
        compose.setContent{CompositionLocalProvider(LocalFieldSpeechEngine provides factory){PeygirTheme("light"){androidx.compose.material3.Surface(Modifier.fillMaxSize()){Box{DictationButton("عنوان"){text=it}}}}}}
        // Invoke the semantic action explicitly; performClick injects a touch tap on Android.
        mic().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick){it()}
        compose.onNodeWithText("پایان",substring=false).assertIsDisplayed().performClick()
        compose.waitUntil(5000){text==speech.words}
        compose.runOnIdle{app.getSharedPreferences("settings",0).edit().putString(DictationMode.PREFERENCE,"tap").commit()}
        compose.onNodeWithTag("hold_mic_عنوان").assertDoesNotExist()
        compose.onNodeWithContentDescription("ورود صوتی - عنوان").performClick()
        compose.onNodeWithText("پایان",substring=false).assertIsDisplayed()
    }
    @Test fun settingsSelectsAndPersistsModeWithoutASeparateSaveStep() {
        app.getSharedPreferences("settings",0).edit().remove(DictationMode.PREFERENCE).commit()
        assertEquals(DictationMode.TAP,DictationMode.read(app))
        compose.setContent{PeygirTheme("light"){SettingsScreen("light",{},{})}}
        compose.onNodeWithTag("settings_voice").performScrollTo().performClick()
        compose.onNodeWithTag("dictation_mode_tap").assertIsSelected()
        compose.onNodeWithTag("dictation_mode_hold").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle{assertEquals(DictationMode.HOLD,DictationMode.read(app))}
    }
    @Test fun permissionApprovalNeverStartsARecordingAfterTheFingerHasBeenReleased() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val speech=Speech(app);val factory: (Context)->FieldSpeechEngine={speech}
        compose.setContent{CompositionLocalProvider(LocalFieldSpeechEngine provides factory){PeygirTheme("light"){DictationButton("عنوان") {}}}}
        mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.runOnIdle{assertEquals(0,speech.starts);assertFalse(speech.active)}
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        mic().performTouchInput{down(center)}
        compose.runOnIdle{assertEquals(1,speech.starts);assertTrue(speech.active)}
        mic().performTouchInput{cancel()}
    }
}
