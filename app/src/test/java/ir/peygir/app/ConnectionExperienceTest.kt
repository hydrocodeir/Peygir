package ir.peygir.app

import android.app.Application
import android.app.NotificationManager
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.google.firebase.messaging.RemoteMessage
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp-mdpi")
class ConnectionExperienceTest {
    @get:Rule val compose=createComposeRule()
    @Test fun retryIntervalsIncreaseAndSuccessResetsThemWithoutTreatingNetworkAvailabilityAsServerSuccess() {
        var now=1_000L;val tracker=ConnectionTracker{now}
        assertFalse(tracker.state.value.connected);assertTrue(tracker.due())
        listOf(10_000L,30_000L,60_000L,120_000L,300_000L,300_000L).forEach{wait->
            tracker.start();assertFalse(tracker.state.value.connected)
            tracker.failure();assertFalse(tracker.due());now+=wait;assertTrue(tracker.due())
        }
        tracker.success();assertTrue(tracker.state.value.connected);assertEquals(0,tracker.state.value.failures)
        tracker.start();assertTrue(tracker.state.value.connected)
        tracker.networkLost();assertFalse(tracker.state.value.connected)
        tracker.failure(auth=true);now+=86_400_000;assertFalse(tracker.due());assertTrue(tracker.state.value.loginRequired)
        tracker.reset();assertTrue(tracker.due());assertFalse(tracker.state.value.loginRequired)
    }
    @Test fun connectionStatusIsNamedAndManualRefreshIsASeparateSingleTapAction() {
        var state by mutableStateOf(ServerState(failures=1));var refreshes=0;var details=0
        compose.setContent{PeygirTheme("light"){Surface{Column{ServerTitle("پیگیر",state){details++};ServerRefresh(state.checking){refreshes++}}}}}
        compose.onNodeWithText("قطع",substring=false).assertExists()
        compose.onNodeWithTag("server_connection").performClick();assertEquals(1,details)
        compose.onNodeWithTag("server_refresh").performClick();assertEquals(1,refreshes)
        compose.runOnIdle{state=ServerState(connected=true)}
        compose.onNodeWithText("متصل",substring=false).assertExists()
        compose.runOnIdle{state=ServerState(checking=true)}
        compose.onNodeWithTag("server_refresh").assertIsNotEnabled()
        compose.onNodeWithText("اتصال…").assertExists()
    }
    @Test fun dailyAndWeeklyCalendarsOpenAtCurrentTimeAndReenteringReturnsToNow() {
        val today=LocalDate.now();var now by mutableStateOf(today.atTime(16,42));var active by mutableStateOf(true)
        var meetings by mutableStateOf(listOf(Meeting(id=1,title="جلسهٔ اکنون",date=today.toEpochDay(),start=16*60,end=17*60)))
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(meetings=meetings),{},{},active=active,clock={now})}}
        compose.onNodeWithTag("meeting_card_1").assertIsDisplayed()
        val initial=compose.onNodeWithTag("meeting_timeline_scroll").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertEquals(initial.maxValue(),initial.value(),1f)
        compose.onNodeWithTag("meeting_mode_WEEK").performClick()
        compose.onNodeWithTag("meeting_card_1").assertIsDisplayed()
        compose.onNodeWithTag("meeting_timeline_scroll").performTouchInput{swipeDown()}
        val before=compose.onNodeWithTag("meeting_timeline_scroll").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        compose.runOnIdle{meetings=meetings.map{it.copy(notes="تغییر سرور")}}
        val after=compose.onNodeWithTag("meeting_timeline_scroll").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
        assertEquals(before,after,1f)
        compose.runOnIdle{active=false;now=today.atTime(16,50)}
        compose.runOnIdle{active=true}
        compose.onNodeWithTag("meeting_card_1").assertIsDisplayed()
    }
    @Test fun invitationsAndAssignmentsPostDistinctNotificationsWhileAppIsAbsentAndDeduplicateDelivery() {
        val c=RuntimeEnvironment.getApplication();val manager=c.getSystemService(NotificationManager::class.java)
        c.getSharedPreferences("shared_private",0).edit().putString("session","test-only").putBoolean("alerts",true).commit()
        c.getSharedPreferences("push_private",0).edit().clear().commit();manager.cancelAll()
        OrgAccess.set(c,SharedSession("https://unit.invalid","","","","کاربر","deputy","u","company-a"))
        val controller=Robolectric.buildService(PeygirMessagingService::class.java).create()
        fun event(kind: String,id: String=kind)=RemoteMessage.Builder("server").setData(mapOf("userId" to "u","company" to "company-a","kind" to kind,"recordId" to kind,"eventId" to id)).build()
        try {
            controller.get().onMessageReceived(event("invitation"));controller.get().onMessageReceived(event("assignment"))
            val titles=shadowOf(manager).allNotifications.map{it.extras.getString("android.title")}
            assertEquals(setOf("به جلسه دعوت شده‌اید","مصوبه‌ای به شما واگذار شد"),titles.toSet())
            controller.get().onMessageReceived(event("invitation"));assertEquals(2,shadowOf(manager).allNotifications.size)
            compose.setContent{PeygirTheme("light"){Surface{androidx.compose.material3.Text("اپ باز است")}}}
            controller.get().onMessageReceived(event("invitation","foreground"));assertEquals(3,shadowOf(manager).allNotifications.size)
        } finally {controller.destroy();manager.cancelAll();c.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(c,null)}
    }
}
