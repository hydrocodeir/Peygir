package ir.peygir.app

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class AccountManagementUiTest {
    @get:Rule val compose=createComposeRule()
    private val c get()=RuntimeEnvironment.getApplication()
    private val chief=CompanyUser("11111111-1111-4111-8111-111111111111","مدیر اول","manager",true,"chief")
    private val person=CompanyUser("22222222-2222-4222-8222-222222222222","علی احمدی","deputy",true,"ahmadi")
    @Volatile private var sent: JSONObject?=null
    @Before fun setup() {
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null);c.deleteDatabase("peygir.db")
        c.getSharedPreferences("shared_private",0).edit().putString("session","ui-test-only").commit()
        OrgAccess.set(c,SharedSession("https://test.invalid","","company","شرکت","مدیر","manager",chief.id,"company-a"));Organization.members.value=listOf(chief,person)
    }
    @After fun close(){Store.get(c).close();c.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(c,null);Organization.members.value=emptyList()}
    private fun accounts() {
        compose.setContent{PeygirTheme("light"){CompanyAccountsPanel{p->if(p.optString("action")!="list")sent=p;JSONObject().put("ok",true)}}}
        compose.onNodeWithText("مدیریت حساب‌ها").performClick()
        compose.waitUntil(10000){compose.onAllNodesWithText("کاربر جدید",substring=false).fetchSemanticsNodes().any{!it.config.contains(SemanticsProperties.Disabled)}}
    }
    @Test fun newUserAcceptsAnActualPersonsNameAndUsernameWithManagerRole() {
        accounts();compose.onNodeWithText("کاربر جدید",substring=false).performClick()
        compose.onNodeWithText("نام و نام خانوادگی").performTextInput("رضا حسنی")
        compose.onNodeWithText("نام کاربری انگلیسی").performTextInput("reza.hassani")
        compose.onNodeWithTag("account_role").performClick();compose.onNodeWithTag("account_role_manager").performClick()
        compose.onNodeWithText("ساخت حساب",substring=false).performClick();compose.waitUntil(10000){sent!=null}
        assertEquals("create",sent!!.getString("action"));assertEquals("رضا حسنی",sent!!.getString("name"));assertEquals("reza.hassani",sent!!.getString("username"));assertEquals("manager",sent!!.getString("role"))
    }
    @Test fun editingChangesNameUsernameAndRoleWithoutChangingTheUserId() {
        accounts();compose.onNodeWithContentDescription("گزینه‌های حساب علی احمدی").performClick();compose.onNodeWithTag("account_edit_${person.id}").performClick()
        compose.onNodeWithText("نام و نام خانوادگی").performTextReplacement("علی احمدیان")
        compose.onNodeWithText("نام کاربری انگلیسی").performTextReplacement("ahmadian")
        compose.onNodeWithTag("account_role").performClick();compose.onNodeWithTag("account_role_assistant").performClick()
        compose.onNodeWithText("ذخیرهٔ تغییرات").performClick();compose.waitUntil(10000){sent!=null}
        assertEquals(person.id,sent!!.getString("id"));assertEquals("علی احمدیان",sent!!.getString("name"));assertEquals("ahmadian",sent!!.getString("username"));assertEquals("assistant",sent!!.getString("role"));assertEquals(person.name,sent!!.getJSONObject("expected").getString("name"))
    }
    @Test fun deletionRequiresConfirmationAndSendsTheSelectedAccountOnly() {
        accounts();compose.onNodeWithContentDescription("گزینه‌های حساب علی احمدی").performClick();compose.onNodeWithTag("account_delete_${person.id}").performClick()
        assertNull(sent);compose.onNodeWithText("مصوبات و سوابق باقی می‌مانند",substring=true).assertExists()
        compose.onNodeWithText("حذف کاربر",substring=false).performClick();compose.waitUntil(10000){sent!=null}
        assertEquals("delete",sent!!.getString("action"));assertEquals(person.id,sent!!.getString("id"))
    }
    @Test fun assistantCannotManageManagersOrGrantElevatedRoles() {
        OrgAccess.set(c,SharedSession("https://test.invalid","","company","شرکت","دستیار","assistant","helper","company-a"))
        accounts();compose.onNodeWithContentDescription("گزینه‌های حساب مدیر اول").assertDoesNotExist()
        compose.onNodeWithText("کاربر جدید",substring=false).performClick();compose.onNodeWithTag("account_role").assertIsNotEnabled();compose.onNodeWithText("نقش: کاربر").assertExists()
    }
    private fun calendar(mode: String) {
        val day=LocalDate.now().toEpochDay();val meeting=Meeting(id=1,title="بررسی عملکرد",date=day,start=540,end=660)
        var opened=0L
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(Snapshot(meetings=listOf(meeting)),{opened=it},{},initialDay=day,initialMode=mode,clock={LocalDate.now().atTime(7,30)})}}
        compose.onNodeWithTag("meeting_card_1").assertIsDisplayed()
        compose.onAllNodesWithText(meeting.clockLabel(),substring=false).assertCountEquals(0)
        compose.onNodeWithContentDescription("${meeting.title}، ${Solar.label(LocalDate.ofEpochDay(day))}، ${meeting.clockLabel()}").assertExists()
        compose.onNodeWithTag("meeting_card_1").performClick();compose.runOnIdle{assertEquals(1L,opened)}
    }
    @Test fun weeklyCardsUseTheCalendarRulerAndKeepAccessibleTimes(){calendar("WEEK")}
    @Test fun dailyCardsUseTheCalendarRulerAndKeepAccessibleTimes(){calendar("DAY")}
}
