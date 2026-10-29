package ir.peygir.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScaleVisualTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val f=ScaleFixture
    @Before fun setup(){
        compose.activity.getSharedPreferences("settings",0).edit().clear().commit()
        OrgAccess.set(compose.activity,SharedSession("https://test.invalid","","","","مدیر","manager",f.users.first().id,"scale"));Organization.members.value=f.users
        val config=android.content.res.Configuration(compose.activity.resources.configuration).apply{fontScale=1.3f}
        @Suppress("DEPRECATION") compose.activity.resources.updateConfiguration(config,compose.activity.resources.displayMetrics)
    }
    @After fun clear(){OrgAccess.set(compose.activity,null);Organization.members.value=emptyList();Organization.contacts.value=emptyList()}
    @Test fun denseCalendarStaysReadableWithLargeTextInBothThemes() {
        val theme=mutableStateOf("light")
        compose.setContent{PeygirTheme(theme.value){Surface{MeetingCalendarScreen(f.data,{},{},initialDay=f.day,clock={LocalDate.ofEpochDay(f.day).atTime(9,0)})}}}
        compose.onNodeWithTag("meeting_dense_${f.day}_1").assertIsDisplayed();capture("scale-calendar-day-light")
        compose.onNodeWithTag("meeting_mode_WEEK").performClick();capture("scale-calendar-week-light")
        compose.runOnIdle{theme.value="dark"};capture("scale-calendar-week-dark")
        compose.onNodeWithTag("meeting_dense_${f.day}_1").performClick();capture("scale-calendar-overlap-dark",true)
    }
    @Test fun fiveHundredTasksAndFiftyPeopleKeepCompactFirstScreens() {
        val theme=mutableStateOf("light");val form=mutableStateOf(false)
        compose.setContent{PeygirTheme(theme.value){Surface{if(form.value) MeetingEditor(f.meetings.first().copy(attendees=f.users.map{it.id}),false,{}, {}) else TaskListScreen(f.data,f.day,"all",{},{},{_,_->})}}}
        capture("scale-tasks-light");compose.runOnIdle{theme.value="dark"};capture("scale-tasks-dark")
        compose.runOnIdle{form.value=true;theme.value="light"};capture("scale-people-selected-light",true)
        compose.onNodeWithTag("meeting_people").performClick()
        compose.onNodeWithTag("person_suggestions").assertDoesNotExist() // all fifty are already selected
        compose.onNodeWithTag("participants_expand").performScrollTo().assertIsDisplayed()
    }
    @Test fun personSuggestionsUseABoundedListInSmallPortraitWindow() {
        compose.setContent{PeygirTheme("light"){MeetingEditor(f.meetings.first().copy(attendees=emptyList()),false,{}, {})}}
        compose.onNodeWithTag("meeting_people").performTouchInput{click(center)}
        compose.onNodeWithTag("person_suggestions").performScrollTo().assertIsDisplayed()
        capture("scale-people-suggestions-light",true)
    }
    private fun capture(name: String,dialog: Boolean=false) {
        compose.waitForIdle();compose.runOnIdle {
            val view=if(dialog) ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView else compose.activity.window.decorView
            if(view.width==0 || view.height==0){val dm=compose.activity.resources.displayMetrics;view.measure(android.view.View.MeasureSpec.makeMeasureSpec(dm.widthPixels,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(dm.heightPixels,android.view.View.MeasureSpec.EXACTLY));view.layout(0,0,dm.widthPixels,dm.heightPixels)}
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            File("../verification/screenshots/$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
}
