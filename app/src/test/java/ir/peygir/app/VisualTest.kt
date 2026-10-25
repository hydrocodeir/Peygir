package ir.peygir.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="fa-rIR-w411dp-h891dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun rendersDashboardAndTaskEditor() {
        val context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db")
        context.getSharedPreferences("drafts",0).edit().clear().commit()
        context.getSharedPreferences("settings",0).edit().clear().putString("theme","light").commit()
        val store=Store.get(context)
        val today=LocalDate.now().toEpochDay()
        val m=store.saveMeeting(Meeting(title="شورای راهبری | آبان"))
        val tracked=store.saveTask(Resolution(title="آماده‌سازی گزارش عملکرد پاییز",owner="خانم رضایی",meetingId=m,due=today-2,priority=Priority.HIGH),emptyList())
        store.addFollowUp(tracked,"گزارش اولیه بررسی شد")
        store.addFollowUp(tracked,"منتظر تأیید مالی است")
        store.saveTask(Resolution(title="پیگیری قرارداد تأمین‌کننده",owner="آقای احمدی",meetingId=m,due=today+1,status=Status.ACTIVE),emptyList())
        store.saveTask(Resolution(title="ارسال صورت‌جلسه",owner="خانم محمدی",meetingId=m,due=today,status=Status.DONE),emptyList())
        compose.setContent {PeygirApp()}
        compose.waitUntil(15000) {compose.onAllNodesWithText("برای پیگیری").fetchSemanticsNodes().isNotEmpty()}
        capture("dashboard-light")
        compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        capture("review-light")
        compose.onNodeWithText("پیگیری",useUnmergedTree=true).performClick()
        compose.onNodeWithText("جلسات",useUnmergedTree=true).performClick()
        capture("meetings-light")
        compose.onNodeWithText("شورای راهبری | آبان",substring=false).performClick()
        capture("meeting-detail-light")
        compose.onNodeWithText("حذف جلسه",substring=false).performClick()
        capture("meeting-delete-light")
        compose.onNodeWithText("انصراف",substring=false).performClick()
        compose.onNodeWithText("مصوبات",useUnmergedTree=true).performClick()
        compose.onNodeWithText("آماده‌سازی گزارش عملکرد پاییز").assertIsDisplayed()
        capture("tasks-light")
        compose.onNodeWithText("آماده‌سازی گزارش عملکرد پاییز").performClick()
        capture("task-tools-light")
        compose.onNodeWithText("سوابق پیگیری (۲)").performScrollTo().performClick()
        capture("follow-ups-light")
        compose.onNodeWithTag("owner_open_tasks").performScrollTo().performClick()
        capture("owner-tasks-light")
        compose.onNodeWithContentDescription("بستن مصوبات مسئول").performClick()
        compose.onNodeWithText("تمدید مهلت",substring=false).performScrollTo().performClick()
        capture("reschedule-light")
        compose.onNodeWithText("دلیل (اختیاری)").performTextInput("دریافت گزارش تکمیلی")
        compose.onNodeWithText("ثبت تغییر موعد").performClick()
        compose.waitUntil(15000) {store.snapshot().history.size==1 && compose.onAllNodesWithText("جزئیات مصوبه").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("آماده‌سازی گزارش عملکرد پاییز").performClick()
        compose.onNodeWithText("تاریخچهٔ موعد (۱)").performScrollTo().performClick()
        compose.onNodeWithText("دلیل: دریافت گزارش تکمیلی").performScrollTo().assertIsDisplayed()
        capture("due-history-light")
        compose.onNodeWithContentDescription("بستن").performClick()
        compose.onNodeWithText("ثبت مصوبه",useUnmergedTree=true).performClick()
        compose.onNodeWithText("عنوان مصوبه *").assertIsDisplayed()
        capture("editor-light")
        compose.onNodeWithText("مسئول انجام *").performTextInput("احمد")
        capture("owner-suggestions-light")
        compose.onNodeWithTag("input_voice").performClick()
        capture("input-voice")
        compose.onNodeWithTag("input_write").performClick()
        compose.onNodeWithText("سررسید | ${Solar.label(LocalDate.now().plusDays(1))}").performClick()
        compose.onNodeWithText("انتخاب تاریخ شمسی").assertIsDisplayed()
        capture("solar-picker")
    }
    @Test @Config(qualifiers="fa-rIR-w360dp-h740dp-mdpi") fun rendersSmallDarkDashboard() {
        val context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db")
        context.getSharedPreferences("drafts",0).edit().clear().commit()
        context.getSharedPreferences("settings",0).edit().clear().putString("theme","dark").commit()
        compose.setContent {PeygirApp()}
        compose.waitUntil(15000) {compose.onAllNodesWithText("برای پیگیری").fetchSemanticsNodes().isNotEmpty()}
        capture("dashboard-dark-small")
    }
    @Test fun rendersLauncherIconAtMultipleSizes() {
        compose.runOnIdle {
            val context=RuntimeEnvironment.getApplication()
            val out=File("../branding").apply{mkdirs()}
            val icon=context.getDrawable(R.mipmap.ic_launcher)!!
            val image=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888)
            icon.setBounds(0,0,512,512);icon.draw(Canvas(image))
            File(out,"peygir-icon-512.png").outputStream().use{image.compress(Bitmap.CompressFormat.PNG,100,it)}
            image.recycle()
            val preview=Bitmap.createBitmap(640,320,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(preview);canvas.drawColor(android.graphics.Color.rgb(248,245,253))
            listOf(160,96,64,40,24).forEachIndexed {i,size->val x=listOf(24,210,338,442,518)[i];icon.setBounds(x,32,x+size,32+size);icon.draw(canvas)}
            val mono=android.graphics.drawable.AdaptiveIconDrawable(
                android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(40,36,63)),
                context.getDrawable(R.drawable.ic_launcher_monochrome)!!)
            mono.setBounds(552,44,624,116);mono.draw(canvas)
            // Draw the actual adaptive layers under common launcher masks.
            val background=context.getDrawable(R.drawable.ic_launcher_background)!!
            val foreground=context.getDrawable(R.drawable.ic_launcher)!!
            listOf(24f,144f,264f).forEachIndexed {i,x->
                val y=216f;val size=80f
                val mask=android.graphics.Path().apply {
                    if(i==0) addCircle(x+size/2,y+size/2,size/2,android.graphics.Path.Direction.CW)
                    else addRoundRect(x,y,x+size,y+size,if(i==1) 20f else 8f,if(i==1) 20f else 8f,android.graphics.Path.Direction.CW)
                }
                canvas.save();canvas.clipPath(mask)
                listOf(background,foreground).forEach {layer->
                    layer.setBounds((x-size/4).toInt(),(y-size/4).toInt(),(x+size*1.25f).toInt(),(y+size*1.25f).toInt());layer.draw(canvas)
                }
                canvas.restore()
            }
            File(out,"peygir-icon-preview.png").outputStream().use{preview.compress(Bitmap.CompressFormat.PNG,100,it)}
            preview.recycle()
        }
    }
    @Test fun rendersDailyReviewColorAndBackupSettings() {
        val context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db")
        context.getSharedPreferences("settings",0).edit().clear().putString("theme","light").commit()
        compose.setContent {PeygirApp()}
        compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("تنظیمات").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("تنظیمات").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithText("مرور روزانهٔ مدیریتی").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("مرور روزانهٔ مدیریتی").performScrollTo()
        capture("settings-review")
        compose.onNodeWithText("رنگ‌های گوشی").performScrollTo()
        capture("settings-colors")
        compose.onNodeWithText("ذخیرهٔ فایل پشتیبان").performScrollTo()
        capture("settings-backup")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view=if(name in listOf("editor-light","solar-picker","input-voice","reschedule-light","due-history-light","meeting-delete-light","task-tools-light","follow-ups-light","owner-tasks-light","owner-suggestions-light")) ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView else compose.activity.window.decorView
            if(view.width==0 || view.height==0) {
                val dm=compose.activity.resources.displayMetrics
                view.measure(android.view.View.MeasureSpec.makeMeasureSpec(dm.widthPixels,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(dm.heightPixels,android.view.View.MeasureSpec.EXACTLY))
                view.layout(0,0,dm.widthPixels,dm.heightPixels)
            }
            val bitmap=Bitmap.createBitmap(view.width.coerceAtLeast(1),view.height.coerceAtLeast(1),Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dest=File("../verification/screenshots/$name.png")
            dest.parentFile!!.mkdirs()
            dest.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
}
