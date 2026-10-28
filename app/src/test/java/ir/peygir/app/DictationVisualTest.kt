package ir.peygir.app

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DictationVisualTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun setup(){RuntimeEnvironment.getApplication().getSharedPreferences("settings",0).edit().clear().putString(DictationMode.PREFERENCE,"hold").commit()}
    @After fun cleanup(){RuntimeEnvironment.getApplication().getSharedPreferences("settings",0).edit().clear().commit()}
    @Test fun voiceSettingsStayReadableInBothThemesWithLargeText() {
        val theme=mutableStateOf("light")
        largeFont()
        compose.setContent{PeygirTheme(theme.value){Surface{SettingsScreen("light",{},{})}}}
        compose.onNodeWithTag("settings_voice").performScrollTo().performClick()
        compose.onNodeWithTag("dictation_mode_hold").performScrollTo().assertIsSelected()
        capture("dictation-settings-light-large")
        compose.runOnIdle{theme.value="dark"};capture("dictation-settings-dark-large")
    }
    @Test fun smallPhoneFieldsKeepTheMicrophoneReachableWithoutShiftingOnPress()=fields("phone")
    @Test @Config(qualifiers="fa-rIR-w740dp-h360dp-mdpi")
    fun landscapeFieldsKeepTheMicrophoneReachable()=fields("landscape")
    @Test @Config(qualifiers="fa-rIR-w800dp-h1280dp-mdpi")
    fun tabletFieldsKeepTheMicrophoneReachable()=fields("tablet")
    private fun fields(size: String) {
        largeFont()
        val theme=mutableStateOf("light")
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val engine=object: FieldSpeechEngine {
            var file: File?=null
            override fun start(){file=File.createTempFile("visual-",".wav",app.cacheDir)}
            override fun stop()=file.also{file=null}
            override fun transcribe(file: File)="نمونه"
        }
        val factory: (android.content.Context)->FieldSpeechEngine={engine}
        compose.setContent{CompositionLocalProvider(LocalFieldSpeechEngine provides factory){PeygirTheme(theme.value){Surface(Modifier.fillMaxSize()){
            Column(Modifier.padding(16.dp).widthIn(max=560.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("ثبت مصوبه",style=MaterialTheme.typography.titleLarge)
                VoiceTextField("بررسی گزارش فروش",{},label={Text("عنوان مصوبه")},voiceLabel="عنوان",modifier=Modifier.fillMaxWidth())
                VoiceTextField("",{},label={Text("مسئول انجام")},voiceLabel="مسئول",modifier=Modifier.fillMaxWidth())
                VoiceTextField("",{},label={Text("توضیحات")},voiceLabel="توضیحات",minLines=2,modifier=Modifier.fillMaxWidth())
            }
        }}}}
        val mic=compose.onNodeWithTag("hold_mic_عنوان")
        mic.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val before=mic.fetchSemanticsNode().boundsInRoot
        capture("dictation-fields-$size-light-large")
        mic.performTouchInput{down(center)}
        compose.onNodeWithText("صحبت کنید - برای تبدیل رها کنید").assertIsDisplayed()
        Assert.assertEquals(before,mic.fetchSemanticsNode().boundsInRoot)
        capture("dictation-fields-$size-recording-large")
        mic.performTouchInput{cancel()}
        compose.runOnIdle{theme.value="dark"};capture("dictation-fields-$size-dark-large")
    }
    private fun largeFont() {
        val config=android.content.res.Configuration(compose.activity.resources.configuration).apply{fontScale=1.5f}
        @Suppress("DEPRECATION")
        compose.activity.resources.updateConfiguration(config,compose.activity.resources.displayMetrics)
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("../verification/screenshots/$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
}
