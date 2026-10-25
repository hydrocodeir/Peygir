package ir.peygir.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate

/** Runs against the installed app. Creates/deletes only uniquely prefixed test rows. */
class DeviceSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    companion object {
        const val PREFIX="آزمون دستگاه | "
        private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
        private val store get()=Store.get(context)
        private var draftsBefore: Map<String,*> = emptyMap<String,Any>()
        @JvmStatic @BeforeClass fun seed() {
            val drafts=context.getSharedPreferences("drafts",0)
            draftsBefore=drafts.all.toMap()
            check(drafts.edit().clear().commit())
            store.saveTask(Resolution(title=PREFIX+"گزارش",owner="مسئول آزمایشی",due=LocalDate.now().toEpochDay()),emptyList())
        }
        @JvmStatic @AfterClass fun cleanup() {
            store.snapshot().tasks.filter{it.title.startsWith(PREFIX)}.forEach{store.deleteTask(it.id)}
            val editor=context.getSharedPreferences("drafts",0).edit().clear()
            draftsBefore.forEach{(key,value)->when(value) {
                is String -> editor.putString(key,value)
                is Boolean -> editor.putBoolean(key,value)
                is Int -> editor.putInt(key,value)
                is Long -> editor.putLong(key,value)
                is Float -> editor.putFloat(key,value)
                is Set<*> -> editor.putStringSet(key,value.filterIsInstance<String>().toSet())
            }}
            check(editor.commit())
            Reminders.schedule(context)
        }
    }
    private fun ready() {
        compose.activity.runOnUiThread {compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        compose.waitUntil(30000) {compose.onAllNodesWithText("برای پیگیری").fetchSemanticsNodes().isNotEmpty()}
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.cacheDir,"device-test").apply{mkdirs()}.resolve("$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
    @Test fun sectionsAndDailySwipes() {
        ready()
        compose.onNodeWithTag("sections_pager").performTouchInput {swipeRight()}
        compose.onNodeWithTag("section_1").assertIsDisplayed()
        compose.onNodeWithTag("section_swipe_1").performTouchInput {swipeRight()}
        compose.onNodeWithTag("section_2").assertIsDisplayed()
        compose.onNodeWithTag("section_swipe_2").performTouchInput {swipeRight()}
        compose.onNodeWithTag("section_3").assertIsDisplayed()
        val day=LocalDate.now().plusDays(1).toEpochDay()
        compose.onNodeWithTag("daily_pager").performTouchInput {swipeRight()}
        compose.onNodeWithTag("review_day_$day").assertIsDisplayed()
        screenshot("review-on-device")
        compose.onNodeWithTag("section_swipe_3").performTouchInput {swipeLeft()}
        compose.onNodeWithTag("section_2").assertIsDisplayed()
        compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("review_day_$day").assertIsDisplayed()
    }
    @Test fun createsPersianTaskAndChangesOnlyItsStatus() {
        ready()
        compose.onNodeWithText("ثبت مصوبه").performClick()
        compose.onNodeWithText("عنوان مصوبه *").performTextReplacement(PREFIX+"ثبت از فرم")
        compose.onNodeWithText("مسئول انجام *").performTextReplacement("آقای آزمایشی")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("سررسید | ${Solar.label(LocalDate.now().plusDays(1))}").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("ماه قبل").fetchSemanticsNodes().isNotEmpty()}
        val p=Solar.parts(LocalDate.now().plusDays(1))
        val prev=Solar.parts(Solar.previousMonth(LocalDate.now().plusDays(1)))
        compose.onNodeWithContentDescription("ماه قبل").performClick()
        compose.onNodeWithText("${Solar.months[prev.month-1]} ${prev.year.fa()}").assertIsDisplayed()
        compose.onNodeWithContentDescription("ماه بعد").performClick()
        compose.onNodeWithText("${Solar.months[p.month-1]} ${p.year.fa()}").assertIsDisplayed()
        screenshot("calendar-on-device")
        compose.onNodeWithText("تاریخ؛ مانند ۱۴۰۵/۰۸/۱۵").performTextReplacement("۱۴۰۵/۰۸/۱۵")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithText("انتخاب",substring=false).performClick()
        compose.onNodeWithText("ذخیره",substring=false).performClick()
        compose.waitUntil(15000) {store.snapshot().tasks.any{it.title==PREFIX+"ثبت از فرم"}}
        compose.waitUntil(15000) {compose.onAllNodesWithText("مصوبهٔ تازه").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("مصوبات",useUnmergedTree=true).performClick()
        compose.onNodeWithText("جست‌وجوی مصوبه، مسئول یا جلسه").performTextInput(PREFIX+"ثبت از فرم")
        compose.onNodeWithContentDescription("وضعیت و گزینه‌های "+PREFIX+"ثبت از فرم").performClick()
        compose.onNode(hasText("در حال انجام") and hasAnyAncestor(isPopup())).performClick()
        compose.waitUntil(15000) {store.snapshot().tasks.any{it.title==PREFIX+"ثبت از فرم" && it.status==Status.ACTIVE}}
        val saved=store.snapshot().tasks.single{it.title==PREFIX+"ثبت از فرم"}
        assertEquals("آقای آزمایشی",saved.owner)
        assertEquals(Solar.toDate(1405,8,15)!!.toEpochDay(),saved.due)
        compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        compose.onNodeWithText("مصوبات",useUnmergedTree=true).performClick()
        compose.onNode(hasText(PREFIX+"ثبت از فرم") and !hasSetTextAction()).assertIsDisplayed()
        screenshot("task-on-device")
    }
    @Test fun inputModesStayInsideSameEditor() {
        ready();compose.onNodeWithText("ثبت مصوبه").performClick()
        compose.onNodeWithTag("input_photo").assertDoesNotExist()
        compose.onNodeWithText("گرفتن عکس").assertDoesNotExist()
        compose.onNodeWithText("انتخاب عکس").assertDoesNotExist()
        compose.onNodeWithTag("input_voice").performClick()
        compose.onNodeWithText("ضبط مصوبه").assertIsDisplayed()
        compose.onNodeWithText("عنوان مصوبه *").assertExists()
        compose.onNodeWithContentDescription("بستن").performClick()
    }
    @Test fun nativePersianOcrUsesBundledModel() {
        ready()
        val bitmap=Bitmap.createBitmap(1600,650,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.WHITE)
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=72f;textAlign=Paint.Align.RIGHT;typeface=context.resources.getFont(R.font.vazirmatn_regular)}
        listOf("تهیه گزارش فروش","مسئول: آقای احمدی","مهلت: ۱۴۰۵/۰۸/۱۵").forEachIndexed{i,line->canvas.drawText(line,1500f,150f+i*160f,paint)}
        val image=File(context.cacheDir,"device-test-ocr.png")
        try {
            image.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            val text=OfflineText.ocr(context,Uri.fromFile(image)).replace('ي','ی').replace('ك','ک')
            File(context.cacheDir,"device-test").apply{mkdirs()}.resolve("ocr-result.txt").writeText(text)
            assertTrue("Persian report word missing: $text",text.contains("گزارش"))
            assertTrue("Persian owner missing: $text",text.contains("احمدی"))
        } finally {bitmap.recycle();image.delete()}
    }
    @Test fun nativeSpeechModelLoadsAndProcessesSilentWav() {
        ready()
        val wav=File(context.cacheDir,"device-test-silence.wav")
        try {
            wav.outputStream().use{it.write(AudioCapture.wavHeader(64000));it.write(ByteArray(64000))}
            assertTrue(OfflineText.transcribe(context,wav.absolutePath).isBlank())
        } finally {wav.delete()}
    }
}
