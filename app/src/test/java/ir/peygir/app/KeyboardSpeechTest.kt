package ir.peygir.app

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class KeyboardSpeechFake(private val c: Context): FieldSpeechEngine {
    var active=false;var starts=0;@Volatile var conversions=0
    var words="نمونه";var gate: CountDownLatch?=null
    private var clip: File?=null
    val files=mutableListOf<File>()
    override fun start(){check(!active);active=true;starts++;clip=File.createTempFile("keyboard-speech-",".wav",c.cacheDir).apply{writeBytes(ByteArray(80))};files+=clip!!}
    override fun stop(): File? {active=false;return clip.also{clip=null}}
    override fun transcribe(file: File): String {conversions++;gate?.await(10,TimeUnit.SECONDS);return words}
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class KeyboardSpeechTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val c: Context get()=RuntimeEnvironment.getApplication()
    private lateinit var engine: KeyboardSpeechFake
    private lateinit var store: Store
    private val ime=mutableIntStateOf(240)
    private val active=mutableStateOf(true)
    private val a=mutableStateOf("");private val b=mutableStateOf("")
    @Before fun setup() {
        c.getSharedPreferences("settings",0).edit().clear().putBoolean("daily",false).putString(DictationMode.PREFERENCE,"keyboard").commit()
        c.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(c,null)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null);c.deleteDatabase("peygir.db");store=Store.get(c)
        engine=KeyboardSpeechFake(c)
    }
    @After fun cleanup(){engine.gate?.countDown();engine.stop()?.delete();store.close()}
    @Composable private fun Fixture(content: @Composable ()->Unit) {
        val factory=remember{ {_: Context->engine as FieldSpeechEngine} }
        CompositionLocalProvider(LocalSpeechImeInsets provides WindowInsets(0,0,0,ime.intValue),LocalSpeechWindowFocused provides active.value,LocalFieldSpeechEngine provides factory) {
            PeygirTheme("light"){content()}
        }
    }
    private fun form() {compose.setContent{Fixture{KeyboardSpeechHost{Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        VoiceTextField(a.value,{a.value=it},label={Text("عنوان")},voiceLabel="عنوان",modifier=Modifier.fillMaxWidth().testTag("a"))
        VoiceTextField(b.value,{b.value=it},label={Text("مسئول")},voiceLabel="مسئول",modifier=Modifier.fillMaxWidth().testTag("b"))
        OutlinedTextField("",{},label={Text("رمز")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth().testTag("password"))
    }}}}}
    private fun mic()=compose.onNodeWithTag("keyboard_speech_mic")
    @Test fun focusedFieldUsesOneBarAboveTheImeAndPreservesTypingAndSelection() {
        ime.intValue=0;form()
        compose.onNodeWithTag("keyboard_speech_bar").assertDoesNotExist()
        compose.onNodeWithContentDescription("ورود صوتی - عنوان").assertDoesNotExist()
        compose.onNodeWithTag("a").performClick().performTextInput("گزارش sales")
        compose.onNodeWithTag("keyboard_speech_bar").assertDoesNotExist()
        compose.runOnIdle{ime.intValue=240}
        mic().assertIsDisplayed().assertWidthIsAtLeast(56.dp).assertHeightIsAtLeast(56.dp)
        val bar=compose.onNodeWithTag("keyboard_speech_bar").fetchSemanticsNode().boundsInRoot
        assertEquals(compose.activity.window.decorView.height-240f,bar.bottom,1f)
        compose.onNodeWithTag("a").performTextInputSelection(TextRange(0,5))
        mic().performTouchInput{down(center)}
        compose.runOnIdle{assertTrue(engine.active);assertEquals(0,engine.conversions)}
        compose.onNodeWithTag("a").assertIsFocused()
        mic().performTouchInput{advanceEventTime(1000);up()}
        compose.waitUntil(5000){a.value=="نمونه sales"}
        compose.runOnIdle{assertEquals("",b.value);assertTrue(engine.files.none{it.exists()})}
    }
    @Test fun switchingFieldsCancelsRecordingAndSensitiveTypingHasNoBar() {
        form();compose.onNodeWithTag("a").performClick()
        mic().performTouchInput{down(center)}
        // A focus move is modeled independently of a second touch pointer.
        compose.onNodeWithTag("b").performSemanticsAction(SemanticsActions.RequestFocus){it()}
        mic().performTouchInput{up()}
        compose.runOnIdle{assertFalse(engine.active);assertEquals(0,engine.conversions);assertTrue(engine.files.none{it.exists()})}
        compose.onNodeWithTag("keyboard_speech_label").assertTextEquals("مسئول")
        mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.waitUntil(5000){b.value==engine.words}
        compose.runOnIdle{assertEquals("",a.value)}
        compose.onNodeWithTag("password").performClick()
        compose.onNodeWithTag("keyboard_speech_bar").assertDoesNotExist()
    }
    @Test fun aLateTranscriptCannotEnterTheNextField() {
        engine.gate=CountDownLatch(1);form();compose.onNodeWithTag("a").performClick()
        mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.waitUntil(5000){engine.conversions==1}
        compose.onNodeWithTag("b").performClick();compose.waitForIdle();engine.gate!!.countDown()
        compose.waitUntil(5000){engine.files.none{it.exists()}}
        compose.runOnIdle{assertEquals("",a.value);assertEquals("",b.value)}
    }
    @Test fun hidingTheKeyboardOrLosingWindowFocusStopsAndDeletesRecording() {
        form();compose.onNodeWithTag("a").performClick();mic().performTouchInput{down(center)}
        compose.runOnIdle{ime.intValue=0};compose.waitForIdle()
        compose.onNodeWithTag("a").performTouchInput{up()}
        compose.runOnIdle{assertFalse(engine.active);assertTrue(engine.files.none{it.exists()})}
        compose.runOnIdle{ime.intValue=240};mic().assertIsDisplayed()
        compose.runOnIdle{assertEquals(1,engine.starts)}
        mic().performTouchInput{down(center)}
        compose.runOnIdle{active.value=false};compose.waitForIdle()
        compose.onNodeWithTag("a").performTouchInput{up()}
        compose.onNodeWithTag("keyboard_speech_bar").assertDoesNotExist()
        compose.runOnIdle{assertFalse(engine.active);assertEquals(0,engine.conversions)}
    }
    @Test fun accessibleStartAndEndAndCancelWorkWithoutHolding() {
        form();compose.onNodeWithTag("a").performClick()
        mic().performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.runOnIdle{assertTrue(engine.active)}
        compose.onNodeWithText("برای پایان دوباره میکروفون را بزنید").assertExists()
        mic().performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.waitUntil(5000){a.value==engine.words}
        mic().performSemanticsAction(SemanticsActions.OnClick){it()}
        compose.onNodeWithTag("keyboard_speech_cancel").performClick()
        compose.runOnIdle{assertFalse(engine.active);assertEquals(1,engine.conversions);assertTrue(engine.files.none{it.exists()})}
    }
    @Test fun removalOfTheEditorCancelsConversionAndCleansTemporaryAudio() {
        engine.gate=CountDownLatch(1);val visible=mutableStateOf(true)
        compose.setContent{Fixture{if(visible.value) FullEditor("تازه",false,{}, {}){VoiceTextField(a.value,{a.value=it},voiceLabel="عنوان",modifier=Modifier.testTag("a"))} else Text("بسته شد")}}
        compose.onNodeWithTag("a").performClick();mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.waitUntil(5000){engine.conversions==1}
        compose.runOnIdle{visible.value=false};compose.waitForIdle();engine.gate!!.countDown()
        compose.waitUntil(5000){engine.files.none{it.exists()}}
        compose.runOnIdle{assertEquals("",a.value)}
    }
    @Test fun modeIsOptionalAndPersistsInSettingsAndBackup() {
        c.getSharedPreferences("settings",0).edit().remove(DictationMode.PREFERENCE).commit()
        assertEquals(DictationMode.HOLD,DictationMode.read(c))
        compose.setContent{Fixture{SettingsScreen("light",{},{})}}
        compose.onNodeWithTag("settings_voice").performScrollTo().performClick()
        compose.onNodeWithTag("dictation_mode_keyboard").performScrollTo().performClick().assertIsSelected()
        compose.runOnIdle{assertEquals(DictationMode.KEYBOARD,DictationMode.read(c))}
        val bytes=ByteArrayOutputStream().also{Backup.write(c,it,store)}.toByteArray()
        c.getSharedPreferences("settings",0).edit().putString(DictationMode.PREFERENCE,"hold").commit()
        Backup.read(c,bytes.inputStream()).use{assertEquals("keyboard",it.settings.dictation);Backup.restore(c,it,store)}
        assertEquals(DictationMode.KEYBOARD,DictationMode.read(c))
    }
    @Test fun savingWaitsForTheTranscriptAndUsesTheCompletedText() {
        a.value="قبلی";engine.gate=CountDownLatch(1);var saved=""
        compose.setContent{Fixture{FullEditor("تازه",false,{}, {saved=a.value}){VoiceTextField(a.value,{a.value=it},voiceLabel="عنوان",modifier=Modifier.testTag("a"))}}}
        compose.runOnIdle{assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)}
        compose.onNodeWithTag("a").performClick();mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.waitUntil(5000){engine.conversions==1}
        compose.onNodeWithText("ذخیره",substring=false).assertIsNotEnabled()
        engine.gate!!.countDown();compose.waitUntil(5000){a.value=="قبلی نمونه"}
        compose.onNodeWithText("ذخیره",substring=false).assertIsEnabled().performClick()
        compose.runOnIdle{assertEquals(a.value,saved)}
    }
    @Test fun cancelDuringConversionLeavesTheFocusedFieldUnchanged() {
        engine.gate=CountDownLatch(1);form();compose.onNodeWithTag("a").performClick()
        mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.waitUntil(5000){engine.conversions==1};mic().assertIsNotEnabled()
        compose.onNodeWithTag("keyboard_speech_cancel").performClick()
        compose.onNodeWithTag("a").assertIsFocused();mic().assertIsEnabled()
        engine.gate!!.countDown();compose.waitUntil(5000){engine.files.none{it.exists()}}
        compose.runOnIdle{assertEquals("",a.value)}
    }
    @Test fun deniedPermissionNeverRecordsAfterTheFingerHasBeenReleased() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        form();compose.onNodeWithTag("a").performClick();mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.runOnIdle{assertEquals(0,engine.starts)}
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        mic().performTouchInput{down(center)}
        compose.runOnIdle{assertTrue(engine.active)}
        mic().performTouchInput{cancel()}
        compose.runOnIdle{assertEquals(0,engine.conversions);assertTrue(engine.files.none{it.exists()})}
    }
    @Test fun linkedMeetingSearchOwnsItsSpeechBarAndSelectionClosesTheModal() {
        var chosen: Long?=null
        compose.setContent{Fixture{LinkedMeetingSearch(listOf(Meeting(id=7,title="فروش")),a.value,{a.value=it},{}){chosen=it}}}
        compose.onNodeWithTag("linked_meeting_query").assertIsFocused()
        compose.onNodeWithTag("keyboard_speech_label").assertTextEquals("جست‌وجوی جلسهٔ مرتبط")
        engine.words="فروش";mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.waitUntil(5000){a.value=="فروش"}
        compose.onNodeWithTag("linked_meeting_7").performClick()
        compose.runOnIdle{assertEquals(7L,chosen)}
    }
    @Test fun bothSearchWindowsAcceptSpeechInTheirOwnQueryField() {
        val meetings=mutableStateOf(false);val today=java.time.LocalDate.now().toEpochDay()
        val data=Snapshot(tasks=listOf(Resolution(id=1,title="گزارش فروش",owner="احمدی")),meetings=listOf(Meeting(id=7,title="جلسه بودجه")))
        compose.setContent{Fixture{if(meetings.value) MeetingCalendarScreen(data,{},{}) else TaskListScreen(data,today,"all",{},{},{_,_->})}}
        compose.onNodeWithContentDescription("جست‌وجوی مصوبات").performClick()
        compose.onNodeWithTag("task_search_field").assertIsFocused()
        engine.words="گزارش";mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.waitUntil(5000){runCatching{compose.onNodeWithTag("task_search_field").assertTextContains("گزارش")}.isSuccess}
        compose.onNodeWithContentDescription("بستن جست‌وجوی مصوبات").performClick()
        compose.runOnIdle{meetings.value=true}
        compose.onNodeWithTag("meeting_search_button").performClick()
        compose.onNodeWithTag("meeting_search_field").assertIsFocused()
        engine.words="بودجه";mic().performTouchInput{down(center);advanceEventTime(1000);up()}
        compose.waitUntil(5000){runCatching{compose.onNodeWithTag("meeting_search_field").assertTextContains("بودجه")}.isSuccess}
        compose.onNode(hasTestTag("meeting_card_7") and hasAnyAncestor(hasTestTag("meeting_search_results"))).assertIsDisplayed()
    }
    @Test @Config(sdk=[28]) @GraphicsMode(GraphicsMode.Mode.LEGACY)
    fun keyboardDialogUsesResizeCompatibilityOnOlderAndroid() {
        compose.setContent{Fixture{FullEditor("فرم",false,{}, {}){Text("متن")}}}
        compose.runOnIdle{assertEquals(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)}
    }
    @Test fun androidWindowFocusEventsCancelTheBarWithoutATestFocusOverride() {
        val factory: (Context)->FieldSpeechEngine={engine}
        compose.setContent{CompositionLocalProvider(LocalSpeechImeInsets provides WindowInsets(0,0,0,240),LocalFieldSpeechEngine provides factory){PeygirTheme("light"){KeyboardSpeechHost{
            VoiceTextField(a.value,{a.value=it},voiceLabel="عنوان",modifier=Modifier.testTag("a"))
        }}}}
        compose.runOnIdle{compose.activity.window.decorView.dispatchWindowFocusChanged(true)}
        compose.onNodeWithTag("a").performClick();mic().performTouchInput{down(center)}
        compose.runOnIdle{assertTrue(engine.active);compose.activity.window.decorView.dispatchWindowFocusChanged(false)}
        compose.waitForIdle();compose.onNodeWithTag("a").performTouchInput{up()}
        compose.onNodeWithTag("keyboard_speech_bar").assertDoesNotExist()
        compose.runOnIdle{assertFalse(engine.active);assertTrue(engine.files.none{it.exists()})}
    }
}
