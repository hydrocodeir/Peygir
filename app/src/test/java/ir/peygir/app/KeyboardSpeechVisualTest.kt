package ir.peygir.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.*
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardSpeechVisualTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("settings",0).edit().clear().putString(DictationMode.PREFERENCE,"keyboard").commit()
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val config=android.content.res.Configuration(compose.activity.resources.configuration).apply{fontScale=1.5f}
        @Suppress("DEPRECATION") compose.activity.resources.updateConfiguration(config,compose.activity.resources.displayMetrics)
    }
    @Test fun barAndEditorStayReadableInBothThemesWithLargeText()=render(240,"phone")
    @Test @Config(qualifiers="fa-rIR-w740dp-h360dp-mdpi")
    fun compactBarPreservesEditorSpaceInLandscape()=render(140,"landscape")
    private fun render(ime: Int,name: String) {
        val theme=mutableStateOf("light")
        val engine=KeyboardSpeechFake(RuntimeEnvironment.getApplication())
        val factory: (Context)->FieldSpeechEngine={engine}
        compose.setContent{CompositionLocalProvider(LocalSpeechImeInsets provides WindowInsets(0,0,0,ime),LocalSpeechWindowFocused provides true,LocalFieldSpeechEngine provides factory){
            PeygirTheme(theme.value){TaskEditor(Resolution(title="مرور گزارش فروش",owner="علی احمدی"),emptyList(),emptyList(),false,{},{_,_->},null)}
        }}
        compose.onNodeWithText("عنوان مصوبه *").performClick()
        compose.onNodeWithTag("keyboard_speech_bar").assertIsDisplayed()
        compose.onNodeWithTag("keyboard_speech_mic").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("ذخیره",substring=false).assertIsDisplayed()
        capture("keyboard-speech-$name-light-large",ime)
        compose.onNodeWithTag("keyboard_speech_mic").performTouchInput{down(center)}
        compose.onNodeWithText("ذخیره",substring=false).assertIsNotEnabled()
        capture("keyboard-speech-$name-recording-large",ime)
        compose.onNodeWithTag("keyboard_speech_mic").performTouchInput{cancel()}
        compose.runOnIdle{theme.value="dark"};capture("keyboard-speech-$name-dark-large",ime)
    }
    private fun capture(name: String,ime: Int) {
        compose.waitForIdle();compose.runOnIdle {
            val view=ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView
            if(view.width==0 || view.height==0) {
                val dm=compose.activity.resources.displayMetrics
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(dm.widthPixels,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(dm.heightPixels,android.view.View.MeasureSpec.EXACTLY));view.layout(0,0,dm.widthPixels,dm.heightPixels)
            }
            // Crop the simulated IME area; this is an app renderer, not an image of a real keyboard.
            val bitmap=Bitmap.createBitmap(view.width,(view.height-ime).coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("../verification/screenshots/$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
}
