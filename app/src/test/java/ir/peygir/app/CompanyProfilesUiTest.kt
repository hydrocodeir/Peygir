package ir.peygir.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp")
class CompanyProfilesUiTest {
    @get:Rule val compose=createComposeRule()
    private val c get()=RuntimeEnvironment.getApplication()
    private lateinit var a: Context;private lateinit var b: Context
    @Before fun setup(){
        c.getSharedPreferences("company_profiles",0).edit().clear().commit()
        fun account(label: String,role: String): Context {
            val id=UUID.randomUUID().toString();val s=SharedSession("https://unit.invalid","test","space-$label",label,"نام $label",role,"user-$label","company-$label")
            val ctx=Accounts.context(c,id);Accounts.register(c,id,s);OrgAccess.set(ctx,s);ctx.getSharedPreferences("shared_private",0).edit().putString("session","test-only").commit();ctx.getSharedPreferences("settings",0).edit().putBoolean("daily",false).commit()
            Store.get(ctx).saveTask(Resolution(title="مصوبه $label",owner="مسئول $label"),emptyList())
            return ctx
        }
        a=account("الف","manager");b=account("ب","deputy");Accounts.select(c,Accounts.id(a))
    }
    @After fun cleanup(){Store.get(a).close();Store.get(b).close();c.getSharedPreferences("company_profiles",0).edit().clear().commit();Organization.members.value=emptyList();Organization.contacts.value=emptyList()}
    @Test fun topCompanyPickerSwitchesTasksAndPermissionsWithoutMixingSameLocalIds() {
        Accounts.markUnread(c,Accounts.id(b))
        compose.setContent{PeygirApp(initialSection=1)}
        compose.onNodeWithTag("task_card_1").assertIsDisplayed();compose.onNodeWithText("مصوبه الف",substring=false).assertIsDisplayed()
        compose.onNodeWithTag("company_switcher").performClick()
        compose.onNodeWithContentDescription("تعداد موارد تازهٔ ب: ۱").assertExists()
        compose.onNodeWithTag("company_${Accounts.id(b)}").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("مصوبه ب",substring=false).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("مصوبه الف",substring=false).assertDoesNotExist();compose.onNodeWithContentDescription("ثبت مصوبه").assertDoesNotExist()
        compose.runOnIdle{assertEquals("deputy",OrgAccess.role(c));assertEquals(0,Accounts.unread(c,Accounts.id(b)));assertEquals("مصوبه الف",Store.get(a).snapshot().tasks.single().title)}
    }
    @Test fun notificationFromAnotherAccountSwitchesBeforeOpeningItsTask() {
        var requested by mutableStateOf<String?>(Accounts.id(b));var task by mutableLongStateOf(1)
        compose.setContent{PeygirApp(requestTask=task,requestAccount=requested,consume={requested=null;task=0})}
        compose.waitUntil(15000){compose.onAllNodesWithTag("task_summary").fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasText("مصوبه ب",substring=false) and hasAnyAncestor(hasTestTag("task_summary"))).assertIsDisplayed()
        compose.onNodeWithText("مصوبه الف",substring=false).assertDoesNotExist();compose.runOnIdle{assertEquals(Accounts.id(b),Accounts.active(c))}
    }
    @Test fun removedAccountDeepLinkDoesNotOpenTheSameIdInAnotherCompany() {
        compose.setContent{PeygirApp(requestTask=1,requestAccount=UUID.randomUUID().toString())}
        compose.onNodeWithText("حساب مربوط به این اعلان یا ویجت در دسترس نیست",substring=true).assertIsDisplayed()
        compose.onNodeWithTag("task_summary").assertDoesNotExist();compose.onNodeWithTag("task_card_1").assertDoesNotExist()
    }
    @Test fun addCompanyFormDoesNotCarryThePreviousCompanyOrAskToPublishItsData() {
        compose.setContent{CompositionLocalProvider(LocalContext provides a){PeygirTheme("light"){CompanySwitcher({})}}}
        compose.onNodeWithText("افزودن شرکت",substring=false).performClick()
        compose.onNodeWithTag("login_company").assertTextContains("")
        compose.onNodeWithText("اشتراک اطلاعات این گوشی",substring=false).assertDoesNotExist()
        compose.onNodeWithText("نام کاربری",substring=false).assertExists();compose.onNodeWithText("رمز ورود",substring=false).assertExists()
    }
}
