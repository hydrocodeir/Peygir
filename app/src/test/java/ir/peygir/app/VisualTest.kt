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
    @Test @Config(qualifiers="fa-rIR-w360dp-h740dp-mdpi")
    fun voiceFormsRemainReadableInBothThemesWithLargeText() {
        val theme=androidx.compose.runtime.mutableStateOf("light")
        val meeting=androidx.compose.runtime.mutableStateOf(false)
        val person=CompanyUser("a","علی احمدی","deputy",username="ahmadi")
        Organization.members.value=listOf(person);Organization.contacts.value=listOf("سارا کاظمی")
        val config=android.content.res.Configuration(compose.activity.resources.configuration).apply{fontScale=1.5f}
        @Suppress("DEPRECATION")
        compose.activity.resources.updateConfiguration(config,compose.activity.resources.displayMetrics)
        compose.setContent{PeygirTheme(theme.value){if(meeting.value) MeetingEditor(Meeting(id=1,title="مرور برنامهٔ شرکت",attendees=listOf("a"),guests=listOf("سارا کاظمی")),false,{}, {}) else TaskEditor(Resolution(title="مرور گزارش فروش"),emptyList(),emptyList(),false,{},{_,_->},null)}}
        compose.onNodeWithText("مسئول انجام *").performTextInput("احمدی")
        compose.onNodeWithTag("person_suggestion_a").performScrollTo().assertIsDisplayed()
        capture("voice-fields-task-light-large")
        compose.runOnIdle{theme.value="dark"};capture("voice-fields-task-dark-large")
        compose.runOnIdle{meeting.value=true;theme.value="light"}
        compose.onNodeWithText("سارا کاظمی",substring=false).performScrollTo().assertIsDisplayed()
        capture("voice-fields-meeting-light-large")
        compose.runOnIdle{theme.value="dark"};capture("voice-fields-meeting-dark-large")
        Organization.members.value=emptyList();Organization.contacts.value=emptyList()
    }
    @Test @Config(qualifiers="fa-rIR-w360dp-h740dp-mdpi")
    fun rendersSimpleExecutiveInterfaceWithLargeFont() {
        val c=RuntimeEnvironment.getApplication()
        c.getSharedPreferences("shared_private",0).edit().clear().commit()
        OrgAccess.set(c,SharedSession("https://test.invalid","","","","رئیس","manager","chief"))
        Organization.members.value=listOf(CompanyUser("a","علی احمدی","deputy"),CompanyUser("b","سمیه رضایی","deputy"))
        val configuration=android.content.res.Configuration(compose.activity.resources.configuration).apply{fontScale=1.5f}
        @Suppress("DEPRECATION")
        compose.activity.resources.updateConfiguration(configuration,compose.activity.resources.displayMetrics)
        val day=LocalDate.now().toEpochDay()
        val meeting=Meeting(id=1,title="شورای معاونین",date=day,start=540,end=600,location="سالن مدیریت",notes="مرور برنامه و مصوبات ماهانه",attendees=listOf("a","b"))
        val review=Resolution(id=1,title="تأیید گزارش عملکرد",owner="علی احمدی",assignee="a",due=day,status=Status.REVIEW,description="توضیحات و مدارک تکمیلی")
        val data=Snapshot(meetings=listOf(meeting),tasks=listOf(review,Resolution(id=2,title="ارسال قرارداد",owner="سمیه رضایی",due=day-1)),followUps=listOf(FollowUp(1,1,"گزارش آماده است؛ منتظر تأیید نهایی",System.currentTimeMillis())))
        val screen=androidx.compose.runtime.mutableIntStateOf(0);val theme=androidx.compose.runtime.mutableStateOf("light")
        compose.setContent{PeygirTheme(theme.value){androidx.compose.material3.Surface{when(screen.intValue){
            0->DashboardScreen(data,day,{},{},{_,_->},{},{})
            1->TaskSummary(review,data,false,{}, {},{_,_->},edit={})
            2->MeetingDetail(data,1,day,{}, {_,_->},{},{},{},copy={})
            else->SettingsScreen(theme.value,{theme.value=it},{})
        }}}}
        capture("simple-followup-chief-large")
        compose.runOnIdle{screen.intValue=1};compose.onNodeWithTag("task_primary_action").assertIsDisplayed();compose.onAllNodesWithText(Status.REVIEW.label,substring=false).assertCountEquals(1);capture("simple-task-light-large")
        compose.runOnIdle{theme.value="dark"};capture("simple-task-dark-large")
        compose.onNodeWithTag("task_summary_more").performClick();capture("simple-task-expanded-large")
        compose.runOnIdle{screen.intValue=2;theme.value="light"};capture("simple-meeting-large")
        compose.runOnIdle{screen.intValue=3};capture("simple-settings-large")
        OrgAccess.set(c,null);Organization.members.value=emptyList()
    }
    @Test fun rendersCompactMeetingFormOptionalDetailsConflictsAndCopy() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(context,null)
        Organization.members.value=listOf(CompanyUser("a","علی احمدی","deputy"),CompanyUser("b","سمیه رضایی","deputy"))
        val day=LocalDate.now().toEpochDay()
        val source=Meeting(id=11,title="شورای معاونین",date=day,notes="بررسی پیشرفت برنامهٔ ماهانه",start=540,end=630,location="سالن مدیریت",attendees=listOf("a","b"))
        val events=androidx.compose.runtime.mutableStateOf(emptyList<Meeting>())
        val theme=androidx.compose.runtime.mutableStateOf("light")
        val copied=androidx.compose.runtime.mutableStateOf(false)
        compose.setContent{PeygirTheme(theme.value){androidx.compose.runtime.key(copied.value){MeetingEditor(if(copied.value) MeetingCopies.draft(source) else source,false,{}, {},allMeetings=events.value,copied=copied.value)}}}
        capture("meeting-tools-compact-light")
        compose.onNodeWithTag("meeting_more_details").performClick();capture("meeting-tools-expanded-light")
        compose.onNodeWithTag("meeting_more_details").performScrollTo().performClick()
        compose.runOnIdle{events.value=listOf(Meeting(id=12,title="بررسی بودجه",date=day,start=600,end=660,attendees=listOf("a")))}
        compose.onNodeWithTag("meeting_conflicts").assertIsDisplayed();capture("meeting-tools-conflict-light")
        compose.runOnIdle{theme.value="dark"};capture("meeting-tools-conflict-dark")
        compose.runOnIdle{theme.value="light";copied.value=true;events.value=emptyList()};capture("meeting-tools-copy-light")
        Organization.members.value=emptyList()
    }
    @Test fun rendersCalendarTimeSelectionAndPrefilledMeetingDraft() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(context,null)
        val day=Solar.toDate(1405,8,6)!!.toEpochDay()
        val draft=androidx.compose.runtime.mutableStateOf<Pair<Long,Int>?>(null)
        compose.setContent{PeygirTheme("light"){
            androidx.compose.material3.Surface{MeetingCalendarScreen(Snapshot(),{},{},initialDay=day,initialMode="MONTH",createAt={d,m->draft.value=d to m})}
            draft.value?.let{(d,m)->MeetingEditor(Meeting(date=d),false,{draft.value=null},{},initialStart=m)}
        }}
        compose.onNodeWithTag("meeting_day_$day").performClick();capture("calendar-create-time")
        compose.runOnIdle{val dialog=ShadowDialog.getLatestDialog() as android.app.TimePickerDialog;dialog.updateTime(14,30);dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()}
        compose.onNodeWithTag("meeting_start_time").assertTextContains("شروع: ۱۴:۳۰")
        compose.onNodeWithTag("meeting_end_time").assertTextContains("پایان: ۱۵:۳۰")
        capture("calendar-create-editor")
    }
    @Test fun rendersTimetableOverlapsInWeekDayAndDarkMode() {
        val day=Solar.toDate(1405,8,5)!!.toEpochDay()
        val week=MeetingTimeline.weekStart(day)
        val data=Snapshot(meetings=listOf(
            Meeting(id=21,title="شورای معاونین",date=day,start=540,end=660),
            Meeting(id=22,title="برنامهٔ فروش",date=day,start=570,end=630),
            Meeting(id=23,title="جلسهٔ پروژه",date=day,start=600,end=720),
            Meeting(id=24,title="مرور بودجه",date=week+1,start=480,end=600),
            Meeting(id=25,title="بررسی قراردادها",date=week+4,start=660,end=780),
            Meeting(id=26,title="جلسهٔ قدیمی",date=week+2,start=null,end=null)
        ))
        val theme=androidx.compose.runtime.mutableStateOf("light")
        compose.setContent{PeygirTheme(theme.value){androidx.compose.material3.Surface{MeetingCalendarScreen(data,{},{},initialDay=day)}}}
        compose.onNodeWithTag("meeting_mode_DAY").assertIsSelected();compose.onNodeWithTag("meeting_mode_WEEK").performClick()
        capture("meeting-timetable-week-light")
        compose.onNodeWithTag("meeting_mode_DAY").performClick()
        val bounds=(21L..23L).map{compose.onNodeWithTag("meeting_card_$it").fetchSemanticsNode().boundsInRoot}
        for(i in bounds.indices) for(j in i+1 until bounds.size) org.junit.Assert.assertTrue("Overlapping appointments must have distinct columns",bounds[i].right<=bounds[j].left || bounds[j].right<=bounds[i].left)
        org.junit.Assert.assertTrue(bounds[1].top>bounds[0].top)
        org.junit.Assert.assertTrue(bounds[0].height>bounds[1].height*1.8f)
        capture("meeting-timetable-day-light")
        compose.runOnIdle{theme.value="dark"};capture("meeting-timetable-day-dark")
        compose.onNodeWithTag("meeting_mode_WEEK").performClick();capture("meeting-timetable-week-dark")
    }
    @Test @Config(qualifiers="fa-rIR-w360dp-h800dp-mdpi")
    fun monthMeetingIndicatorsStayInsideAdjacentDatesWithLargeFontInBothThemes() {
        val configuration=android.content.res.Configuration(compose.activity.resources.configuration).apply{fontScale=2f}
        @Suppress("DEPRECATION")
        compose.activity.resources.updateConfiguration(configuration,compose.activity.resources.displayMetrics)
        val first=Solar.toDate(1405,8,2)!!.toEpochDay()
        val meetings=listOf(1,2,12).flatMapIndexed {offset,count->(1..count).map{Meeting(id=(offset*100+it).toLong(),title="جلسهٔ نمونه",date=first+offset)}}
        val mode=androidx.compose.runtime.mutableStateOf("dark")
        compose.setContent{PeygirTheme(mode.value){androidx.compose.material3.Surface{MeetingCalendarScreen(Snapshot(meetings=meetings),{},{},initialDay=first,initialMode="MONTH")}}}
        fun assertMarkersFit() {
            for(day in first..first+2) {
                val cell=compose.onNodeWithTag("meeting_day_$day").fetchSemanticsNode().boundsInRoot
                val marker=compose.onNodeWithTag("meeting_day_marker_$day",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                org.junit.Assert.assertTrue("Day indicator must fit inside its date cell: cell=$cell marker=$marker",marker.left>=cell.left && marker.right<=cell.right && marker.top>=cell.top && marker.bottom<=cell.bottom)
            }
        }
        assertMarkersFit();capture("meeting-markers-dark-large")
        compose.runOnIdle{mode.value="light"};assertMarkersFit();capture("meeting-markers-light-large")
        compose.onNodeWithTag("meeting_day_${first+1}").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("۳ آبان ۱۴۰۵، ۲ جلسه").assertExists()
    }
    @Test @Config(qualifiers="fa-rIR-w360dp-h720dp-mdpi")
    fun timetableLeavesMorningMeetingsVisibleOnSmallScreenWithLargeFont() {
        val context=RuntimeEnvironment.getApplication()
        val configuration=android.content.res.Configuration(compose.activity.resources.configuration).apply{fontScale=1.3f}
        @Suppress("DEPRECATION")
        compose.activity.resources.updateConfiguration(configuration,compose.activity.resources.displayMetrics)
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db");context.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(context,null)
        context.getSharedPreferences("settings",0).edit().clear().putString("theme","light").commit()
        val store=Store.get(context);val today=LocalDate.now().toEpochDay()
        val one=store.saveMeeting(Meeting(title="جلسهٔ برنامه‌ریزی فروش",date=today,start=540,end=600,location="اتاق مدیریت"))
        val two=store.saveMeeting(Meeting(title="مرور پیشرفت پروژه",date=today,start=600,end=660,location="سالن جلسات"))
        store.saveTask(Resolution(title="تهیه گزارش فروش",owner="احمدی",due=today),emptyList())
        store.saveTask(Resolution(title="ارسال قرارداد",owner="رضایی",due=today,status=Status.ACTIVE),emptyList())
        var measuredFontScale=0f
        compose.setContent{val density=androidx.compose.ui.platform.LocalDensity.current;androidx.compose.runtime.SideEffect{measuredFontScale=density.fontScale};PeygirApp(initialSection=0)}
        compose.waitUntil(15000){compose.onAllNodesWithText("برای پیگیری").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{org.junit.Assert.assertEquals(1.3f,measuredFontScale,0.01f)}
        compose.onNode(hasText("جلسات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithTag("meeting_mode_WEEK").performClick()
        compose.onNodeWithTag("meeting_search_field").assertDoesNotExist()
        val viewport=compose.onNodeWithTag("meeting_day_page_$today").fetchSemanticsNode().boundsInRoot
        for(id in listOf(one,two)) {
            val card=compose.onNodeWithTag("meeting_card_$id").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue("Whole meeting card must fit above navigation",card.top>=viewport.top && card.bottom<=viewport.bottom)
        }
        capture("company-calendar-compact-week-small")
        compose.onNodeWithTag("meeting_mode_DAY").performClick();capture("company-calendar-compact-day-small")
        compose.onNodeWithTag("meeting_mode_MONTH").performClick();capture("company-calendar-compact-month-small")
        compose.onNodeWithContentDescription("جست‌وجوی جلسات").performClick()
        compose.onNodeWithTag("meeting_search_field").performTextInput("پروژه")
        compose.onNodeWithText("مرور پیشرفت پروژه").assertIsDisplayed();capture("company-calendar-search-small")
        compose.onNodeWithContentDescription("بستن جست‌وجوی جلسات").performClick()
        compose.onNode(hasText("مصوبات",substring=false) and hasClickAction()).performClick();capture("tasks-compact-small")
        compose.onNodeWithContentDescription("جست‌وجوی مصوبات").performClick()
        compose.onNodeWithTag("task_search_field").performTextInput("احمدی");capture("tasks-search-small")
    }
    @Test fun rendersFirstRunPermissionStepsAndLoginWithoutCredentials() {
        val c=RuntimeEnvironment.getApplication()
        c.getSharedPreferences("first_run",0).edit().clear().commit()
        org.robolectric.Shadows.shadowOf(c as Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        compose.setContent{PeygirTheme("light"){FirstRunScreen(autoAsk=false){}}}
        capture("first-run-notifications")
        compose.onNodeWithTag("first_run_later").performClick();capture("first-run-exact-alarm")
        compose.onNodeWithTag("first_run_later").performClick();capture("first-run-login")
    }
    @Test fun rendersCompanyCalendarAcrossModesAndPersianParticipantSearch() {
        val person=CompanyUser("deputy-test","علی احمدی — معاون مالی","deputy")
        Organization.members.value=listOf(person)
        val today=LocalDate.now().toEpochDay()
        val data=Snapshot(meetings=listOf(
            Meeting(id=1,title="شورای معاونین",date=today,start=540,end=600,location="سالن جلسات مدیریت",attendees=listOf(person.id),repeat="WEEKLY"),
            Meeting(id=2,title="بررسی بودجه و نقدینگی",date=today,start=660,end=720,attendees=listOf(person.id)),
            Meeting(id=3,title="مرور برنامهٔ فروش",date=today+2,start=600,end=660),
            Meeting(id=4,title="جلسهٔ ماه قبل",date=today-32,attendees=listOf(person.id))
        ))
        compose.setContent{PeygirTheme("light"){MeetingCalendarScreen(data,{},{},initialMode="MONTH")}}
        capture("company-calendar-month")
        compose.onNodeWithTag("meeting_mode_WEEK").performClick();capture("company-calendar-week")
        compose.onNodeWithTag("meeting_days_pager").performTouchInput{swipeRight()}
        compose.onNodeWithTag("meeting_day_page_${today+7}").assertIsDisplayed();capture("company-calendar-week-next-day")
        compose.onNodeWithTag("meeting_mode_DAY").performClick();capture("company-calendar-day")
        compose.onNodeWithTag("meeting_days_pager").performTouchInput{swipeLeft()}
        compose.onNodeWithTag("meeting_day_page_${today+6}").assertIsDisplayed();capture("company-calendar-day-previous")
        compose.onNodeWithContentDescription("جست‌وجوی جلسات").performClick()
        compose.onNodeWithTag("meeting_search_field").performTextInput("احمدی")
        capture("company-calendar-search")
        Organization.members.value=emptyList()
    }
    @Test fun rendersSharedSettingsAndLoginWithoutPrivateCredentials() {
        val context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db");context.getSharedPreferences("shared_private",0).edit().clear().commit()
        context.getSharedPreferences("settings",0).edit().clear().putString("theme","light").commit()
        compose.setContent{PeygirApp(initialSection=0)}
        compose.waitUntil(15000){compose.onAllNodesWithText("برای پیگیری").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("تنظیمات").performClick()
        compose.onNodeWithTag("settings_account").performClick()
        compose.onNodeWithText("ورود به فضای مشترک").performScrollTo()
        capture("shared-settings")
        compose.onNodeWithText("ورود به فضای مشترک").performClick()
        compose.onNodeWithText("نام کاربری").assertExists()
        capture("shared-login")
    }
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
        compose.setContent {PeygirApp(initialSection=0)}
        compose.waitUntil(15000) {compose.onAllNodesWithText("برای پیگیری").fetchSemanticsNodes().isNotEmpty()}
        capture("dashboard-light")
        compose.onNodeWithText("مرور",useUnmergedTree=true).performClick()
        capture("review-light")
        compose.onNodeWithText("پیگیری",useUnmergedTree=true).performClick()
        compose.onNode(hasText("جلسات",substring=false) and hasClickAction()).performClick()
        capture("meetings-light")
        compose.onNodeWithText("شورای راهبری | آبان",substring=false).performScrollTo().performClick()
        capture("meeting-detail-light")
        compose.onNodeWithContentDescription("گزینه‌های جلسه").performClick();compose.onNodeWithText("حذف جلسه",substring=false).performClick()
        capture("meeting-delete-light")
        compose.onNodeWithText("انصراف",substring=false).performClick()
        compose.onNode(hasText("مصوبات",substring=false) and hasClickAction()).performClick()
        compose.onNodeWithText("آماده‌سازی گزارش عملکرد پاییز").assertIsDisplayed()
        capture("tasks-light")
        compose.onNodeWithText("آماده‌سازی گزارش عملکرد پاییز").performClick()
        capture("task-tools-light")
        compose.onNodeWithTag("task_summary_more").performScrollTo().performClick()
        capture("follow-ups-light")
        compose.onNodeWithTag("owner_open_tasks").performScrollTo().performClick()
        capture("owner-tasks-light")
        compose.onNodeWithContentDescription("بستن مصوبات مسئول").performClick()
        compose.onNodeWithContentDescription("گزینه‌های مصوبه").performClick();compose.onNodeWithText("تمدید مهلت",substring=false).performClick()
        compose.onNodeWithText("سه روز",substring=false).performClick()
        capture("reschedule-light")
        compose.onNodeWithText("دلیل (اختیاری)").performTextInput("دریافت گزارش تکمیلی")
        compose.onNodeWithText("ثبت تغییر موعد").performClick()
        compose.waitUntil(15000) {store.snapshot().history.size==1 && compose.onAllNodesWithTag("task_summary").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithText("آماده‌سازی گزارش عملکرد پاییز").performClick()
        compose.onNodeWithTag("task_summary_more").performScrollTo().performClick()
        compose.onNodeWithText("دلیل: دریافت گزارش تکمیلی").performScrollTo().assertIsDisplayed()
        capture("due-history-light")
        compose.onNodeWithContentDescription("بستن").performClick()
        compose.onNodeWithText("ثبت مصوبه",useUnmergedTree=true).performClick()
        compose.onNodeWithText("عنوان مصوبه *").assertIsDisplayed()
        capture("editor-light")
        compose.onNodeWithText("مسئول انجام *").performTextInput("احمد")
        capture("owner-suggestions-light")
        compose.onNodeWithTag("input_voice").assertDoesNotExist()
        compose.onNodeWithText("سررسید - ${Solar.label(LocalDate.now().plusDays(1))}").performClick()
        compose.onNodeWithText("انتخاب تاریخ شمسی").assertIsDisplayed()
        capture("solar-picker")
    }
    @Test @Config(qualifiers="fa-rIR-w360dp-h740dp-mdpi") fun rendersSmallDarkDashboard() {
        val context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db")
        context.getSharedPreferences("drafts",0).edit().clear().commit()
        context.getSharedPreferences("settings",0).edit().clear().putString("theme","dark").commit()
        compose.setContent {PeygirApp(initialSection=0)}
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
        compose.setContent {PeygirApp(initialSection=0)}
        compose.waitUntil(15000) {compose.onAllNodesWithContentDescription("تنظیمات").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("تنظیمات").performClick()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("settings_notifications").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("settings_notifications").performClick()
        compose.onNodeWithText("مرور روزانهٔ مدیریتی").performScrollTo()
        capture("settings-review")
        compose.onNodeWithTag("settings_appearance").performScrollTo().performClick()
        compose.onNodeWithText("رنگ‌های گوشی").performScrollTo()
        capture("settings-colors")
        compose.onNodeWithTag("settings_backup").performScrollTo().performClick()
        compose.onNodeWithText("ذخیرهٔ فایل پشتیبان").performScrollTo()
        capture("settings-backup")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view=if(name.startsWith("voice-fields-") || name.startsWith("simple-task-") || name.startsWith("meeting-tools-") || name.startsWith("calendar-create-") || name.startsWith("company-calendar-search") || name=="tasks-search-small" || name=="first-run-login" || name in listOf("editor-light","solar-picker","input-voice","reschedule-light","due-history-light","meeting-delete-light","task-tools-light","follow-ups-light","owner-tasks-light","owner-suggestions-light","shared-login")) ShadowDialog.getShownDialogs().last{it.isShowing}.window!!.decorView else compose.activity.window.decorView
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
