package ir.peygir.app

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanyProfilesVisualTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var a: Context;private lateinit var b: Context
    @Before fun setup(){
        compose.activity.getSharedPreferences("company_profiles",0).edit().clear().commit()
        fun add(name: String,role: String): Context {
            val id=UUID.randomUUID().toString();val ctx=Accounts.context(compose.activity,id);val session=SharedSession("https://unit.invalid","test","workspace-$id",name,"علی احمدی",role,"user-$id","company-$role")
            Accounts.register(ctx,id,session);OrgAccess.set(ctx,session);ctx.getSharedPreferences("shared_private",0).edit().putString("session","test-only").commit()
            ctx.getSharedPreferences("settings",0).edit().putBoolean("daily",false).commit()
            for(i in 1..6) Store.get(ctx).saveTask(Resolution(title="بررسی گزارش پیشرفت پروژهٔ ${i.fa()}",owner="سارا کاظمی",status=if(i<3) Status.REVIEW else Status.ACTIVE),emptyList())
            return ctx
        }
        a=add("شرکت توسعهٔ منابع آب","manager");b=add("شرکت مهندسی بهار","deputy");Accounts.select(a,Accounts.id(a));Accounts.markUnread(b,Accounts.id(b))
        val config=android.content.res.Configuration(compose.activity.resources.configuration).apply{fontScale=1.3f}
        @Suppress("DEPRECATION") compose.activity.resources.updateConfiguration(config,compose.activity.resources.displayMetrics)
    }
    @After fun cleanup(){Store.get(a).close();Store.get(b).close();compose.activity.getSharedPreferences("company_profiles",0).edit().clear().commit();Organization.members.value=emptyList()}
    @Test fun companyHeaderPickerAndRoleSwitchStayReadable() {
        compose.setContent{PeygirApp(initialSection=1)}
        compose.onNodeWithTag("company_switcher").assertIsDisplayed();capture("companies-header-light")
        compose.onNodeWithTag("company_switcher").performClick();capture("companies-picker-light",true)
        compose.onNodeWithTag("company_${Accounts.id(b)}").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithTag("company_list").fetchSemanticsNodes().isEmpty() && compose.onAllNodesWithContentDescription("ثبت مصوبه").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("company_switcher").assertIsDisplayed();capture("companies-member-header-light")
    }
    @Test fun companyPickerSupportsDarkThemeAndLargeNames() {
        compose.setContent{CompositionLocalProvider(androidx.compose.ui.platform.LocalContext provides a){PeygirTheme("dark"){CompanySwitcher({})}}}
        capture("companies-picker-dark",true)
    }
    private fun capture(name: String,dialog: Boolean=false) {
        compose.waitForIdle();compose.runOnIdle {
            val view=if(dialog) ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView else compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            File("../verification/screenshots/$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
}
