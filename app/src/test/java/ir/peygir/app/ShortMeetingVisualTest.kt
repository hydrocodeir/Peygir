package ir.peygir.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShortMeetingVisualTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val day=LocalDate.of(2026,11,7).toEpochDay()
    @Before fun setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("settings",0).edit().clear().commit()
        RuntimeEnvironment.getApplication().getSharedPreferences("company_profiles",0).edit().clear().commit()
        OrgAccess.set(RuntimeEnvironment.getApplication(),null)
    }
    @Test fun shortTitlesFitAtLargeFontInDayAndWeekAndCreatorIsVisibleInDetails() {
        val data=Snapshot(meetings=listOf(
            Meeting(id=1,title="جلسه تأمین آب استان",date=day,start=600,end=690,creator="علی احمدی"),
            Meeting(id=2,title="بررسی گزارش کوتاه",date=day,start=690,end=720,creator="خانم رضایی"),
            Meeting(id=3,title="جلسه معارفه",date=day,start=720,end=735,reserved=true,creator="خانم رضایی"),
            Meeting(id=4,title="هماهنگی",date=day,start=735,end=740,creator="علی احمدی"),
            Meeting(id=5,title="پیگیری",date=day,start=740,end=745,creator="علی احمدی")
        ))
        var dark by mutableStateOf(false)
        var details by mutableStateOf(0L)
        compose.setContent {
            val current=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density,1.5f)) {
                PeygirTheme(if(dark) "dark" else "light"){Surface(Modifier.fillMaxSize()) {
                    if(details==0L) MeetingCalendarScreen(data,{details=it},{},initialDay=day,initialMode="DAY",clock={LocalDate.ofEpochDay(day).atTime(11,30)})
                    else MeetingDetail(data,details,day,{},{_,_->},{},{})
                }}
            }
        }
        fun titleFits(id: Long) {
            val card=compose.onNodeWithTag("meeting_card_$id").fetchSemanticsNode().boundsInRoot
            val node=compose.onNodeWithTag("meeting_title_$id",useUnmergedTree=true)
            val layouts=mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){assertTrue(it(layouts))}
            val layout=layouts.single()
            // Ellipsis in a narrow weekly column can set didOverflowHeight via maxLines.
            // The regression is vertical clipping of the visible line, not intentional ellipsis.
            assertTrue("Visible title line must fit vertically for meeting $id; layout=${layout.size}",layout.getLineBottom(layout.lineCount-1)<=layout.size.height+1)
            val title=node.fetchSemanticsNode().boundsInRoot
            assertTrue("Title must remain inside card $id",title.top>=card.top && title.bottom<=card.bottom+1)
        }
        (2L..5L).forEach(::titleFits)
        val a=compose.onNodeWithTag("meeting_card_4").fetchSemanticsNode().boundsInRoot
        val b=compose.onNodeWithTag("meeting_card_5").fetchSemanticsNode().boundsInRoot
        assertTrue("Very short touching meetings must not cover one another",a.right<=b.left || b.right<=a.left || a.bottom<=b.top || b.bottom<=a.top)
        capture("short-meetings-day-light-large")
        compose.runOnIdle{dark=true};capture("short-meetings-day-dark-large")
        compose.onNodeWithTag("meeting_card_2").performClick()
        compose.onNodeWithTag("meeting_creator").assertTextEquals("ثبت‌کنندهٔ جلسه: خانم رضایی")
        capture("meeting-creator-dark-large")
        compose.runOnIdle{details=0}
        compose.onNodeWithTag("meeting_mode_WEEK").performClick()
        (2L..5L).forEach(::titleFits)
        capture("short-meetings-week-dark-large")
    }
    private fun capture(name: String) {
        compose.waitForIdle();compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            val dir=File("../verification/short-meetings").apply{mkdirs()}
            File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
}
