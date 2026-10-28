package ir.peygir.app

import android.app.Application
import android.app.TimePickerDialog
import android.content.DialogInterface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp-mdpi")
class CalendarCreationTest {
    @get:Rule val compose=createComposeRule()
    private val day=Solar.toDate(1405,8,5)!!.toEpochDay()
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun setup(){context.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(context,null)}
    @After fun cleanup(){OrgAccess.set(context,null)}
    @Test fun dailyEmptySpaceUsesTappedTimeIncludingTheVerticalScrollOffset() {
        var created: Pair<Long,Int>?=null
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(),{},{},initialDay=day,initialMode="DAY",createAt={d,m->created=d to m},clock={java.time.LocalDate.now().atTime(7,30)})}}
        compose.onNodeWithTag("meeting_timeline_scroll").performTouchInput{click(Offset(width/2f,126f))}
        compose.runOnIdle{assertEquals(day to 555,created)}
    }
    @Test fun weeklyEmptySpaceUsesTheTappedColumnRatherThanTheSelectedDay() {
        var created: Pair<Long,Int>?=null
        val target=MeetingTimeline.weekStart(day)
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(),{},{},initialDay=day,initialMode="WEEK",createAt={d,m->created=d to m},clock={java.time.LocalDate.now().atTime(7,30)})}}
        val cell=compose.onNodeWithTag("meeting_time_column_$target").fetchSemanticsNode().boundsInRoot
        val viewport=compose.onNodeWithTag("meeting_timeline_scroll").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("meeting_timeline_scroll").performTouchInput{click(Offset(cell.center.x-viewport.left,72f))}
        compose.runOnIdle{assertEquals(target to 510,created)}
    }
    @Test fun monthEmptyDayChoosesTimeAndCancellationDoesNotCreateAnything() {
        var created: Pair<Long,Int>?=null
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(meetings=listOf(Meeting(id=1,title="جلسهٔ موجود",date=day+2))),{},{},initialDay=day,initialMode="MONTH",createAt={d,m->created=d to m},clock={java.time.LocalDate.now().atTime(7,30)})}}
        compose.onNodeWithTag("meeting_day_${day+2}").performClick()
        compose.runOnIdle{assertNull(created)}
        compose.onNodeWithTag("meeting_day_${day+1}").performClick()
        compose.runOnIdle{val dialog=ShadowDialog.getLatestDialog() as TimePickerDialog;dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();assertNull(created)}
        compose.onNodeWithTag("meeting_day_${day+1}").performClick()
        compose.runOnIdle{val dialog=ShadowDialog.getLatestDialog() as TimePickerDialog;dialog.updateTime(14,30);dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();assertEquals((day+1) to 870,created)}
    }
    @Test fun meetingCardAndVerticalOrHorizontalSwipesNeverCreateAnExtraMeeting() {
        var count=0;var opened=0L
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(meetings=listOf(Meeting(id=7,title="جلسهٔ موجود",date=day,start=540,end=600))),{opened=it},{},initialDay=day,initialMode="WEEK",createAt={_,_->count++},clock={java.time.LocalDate.now().atTime(7,30)})}}
        compose.onNodeWithTag("meeting_card_7").performClick()
        compose.runOnIdle{assertEquals(7L,opened);assertEquals(0,count)}
        compose.onNodeWithTag("meeting_timeline_scroll").performTouchInput{swipeUp()}
        compose.onNodeWithTag("meeting_days_pager").performTouchInput{swipeRight()}
        compose.runOnIdle{assertEquals(0,count)}
    }
    @Test fun deputyCannotCreateByTappingEmptyTimeOrEmptyMonthlyDates() {
        OrgAccess.set(context,SharedSession("https://test.invalid","","","","معاون","deputy","person"))
        var count=0
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(),{},{},initialDay=day,initialMode="WEEK",createAt={_,_->count++},clock={java.time.LocalDate.now().atTime(7,30)})}}
        compose.onNodeWithTag("meeting_timeline_scroll").performTouchInput{click(Offset(width/2f,72f))}
        compose.onNodeWithTag("meeting_mode_MONTH").performClick()
        compose.onNodeWithTag("meeting_day_${day+1}").performClick()
        compose.runOnIdle{assertEquals(0,count)}
        compose.onNodeWithContentDescription("جلسهٔ تازه").assertDoesNotExist()
    }
}
