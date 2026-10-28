package ir.peygir.app

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w360dp-h740dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PersonalOptionsVisualTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var store: Store
    @Before fun setup() {
        val app=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        app.deleteDatabase("peygir.db");store=Store.get(app)
        app.getSharedPreferences("settings",0).edit().clear().putBoolean("daily",false).commit()
        app.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(app,null)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val config=android.content.res.Configuration(compose.activity.resources.configuration).apply{fontScale=1.5f}
        @Suppress("DEPRECATION") compose.activity.resources.updateConfiguration(config,compose.activity.resources.displayMetrics)
    }
    @After fun cleanup(){store.close()}
    @Test fun compactGroupsAndChoicesStayReadableInBothThemesWithLargeText() {
        val theme=mutableStateOf("light")
        compose.setContent{PeygirTheme(theme.value){Surface{SettingsScreen("light",{},{})}}}
        capture("personal-settings-collapsed-light-large")
        compose.onNodeWithTag("settings_personal").performScrollTo().performClick()
        compose.onNodeWithTag("option_calendar").performScrollTo().assertIsDisplayed()
        capture("personal-settings-choices-light-large")
        compose.runOnIdle{theme.value="dark"};capture("personal-settings-choices-dark-large")
        compose.onNodeWithTag("settings_appearance").performScrollTo().performClick()
        compose.onNodeWithTag("option_font").performScrollTo().assertIsDisplayed();capture("personal-settings-appearance-dark-large")
        compose.onNodeWithTag("settings_notifications").performScrollTo().performClick()
        compose.onNodeWithTag("option_meeting_lead").performScrollTo().assertIsDisplayed();capture("personal-settings-notifications-dark-large")
    }
    @Test @Config(qualifiers="fa-rIR-w740dp-h360dp-mdpi")
    fun controlsRemainReachableInLandscape() {
        compose.setContent{PeygirTheme("light"){Surface{SettingsScreen("light",{},{})}}}
        compose.onNodeWithTag("settings_personal").performScrollTo().performClick()
        compose.onNodeWithTag("option_duration").performScrollTo().assertIsDisplayed()
        capture("personal-settings-landscape-large")
    }
    @Test fun detailedCardsUseTheChosenExtraSpaceAndStillOpenOnTouch() {
        val app=RuntimeEnvironment.getApplication();val theme=mutableStateOf("light");var opened=false
        UserOptions(cards="full").save(app.getSharedPreferences("settings",0).edit()).commit()
        compose.setContent{PeygirTheme(theme.value){Surface(Modifier.fillMaxSize()){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("مصوبات",style=MaterialTheme.typography.titleLarge)
            TaskCard(Resolution(id=1,title="بررسی گزارش فروش",description="جمع‌بندی آخرین آمار برای جلسهٔ مدیران",owner="علی احمدی",priority=Priority.HIGH),"جلسهٔ هفتگی",LocalDate.now().toEpochDay(),{opened=true}){}
        }}}}
        capture("personal-cards-full-light-large")
        compose.runOnIdle{theme.value="dark"};capture("personal-cards-full-dark-large")
        compose.onNodeWithTag("task_card_1").performClick();Assert.assertTrue(opened)
    }
    private fun capture(name: String) {
        compose.waitForIdle();compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            File("../verification/screenshots/$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
}
