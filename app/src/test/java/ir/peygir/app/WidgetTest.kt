package ir.peygir.app

import android.app.Application
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class WidgetTest {
    private lateinit var context: Context
    private lateinit var store: Store
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        Store::class.java.getDeclaredField("instance").apply{isAccessible=true}.set(null,null)
        context.deleteDatabase("peygir.db");store=Store.get(context)
        context.getSharedPreferences("review_widgets",0).edit().clear().commit()
        context.getSharedPreferences("shared_private",0).edit().clear().commit();OrgAccess.set(context,null)
    }
    @After fun cleanup() {store.close();OrgAccess.set(context,null)}
    private fun host(data: Snapshot,meetings: Boolean,width: Int=360,height: Int=800): View {
        val host=AppWidgetHostView(context)
        host.setAppWidget(31,AppWidgetProviderInfo().apply{provider=ComponentName(context,if(meetings) MeetingWidget::class.java else ReviewWidget::class.java)})
        val root=ReviewWidget.build(context,31,data,meetings).apply(context,host);host.addView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height)
        return root
    }
    @Test fun bothAgendasOnlyShowTodayAndFutureAndIgnoreOldSelectedDays() {
        val day=LocalDate.now().toEpochDay()
        WidgetDays.move(context,31,-1)
        val data=Snapshot(meetings=listOf(Meeting(id=1,title="گذشته",date=day-1),Meeting(id=2,title="امروز",date=day),Meeting(id=3,title="آینده",date=day+1)),tasks=listOf(Resolution(id=1,title="گذشته",due=day-1),Resolution(id=2,title="امروز",due=day),Resolution(id=3,title="آینده",due=day+1)))
        for(meetings in listOf(false,true)) {
            val rows=WidgetAgenda.rows(data,meetings,day)
            assertEquals(30,rows.size);assertTrue(rows.all{it.day>=day});assertEquals(day+29,rows.last().day)
            assertFalse(rows.any{it.meeting?.id==1L || it.task?.id==1L})
            val root=host(data,meetings)
            val badge=root.findViewById<TextView>(R.id.widget_count)
            assertEquals("۲",badge.text.toString());assertEquals("تعداد ${if(meetings) "جلسات" else "مصوبات"}: ۲",badge.contentDescription.toString())
            badge.performClick();assertEquals(if(meetings) WidgetLaunch.MEETINGS else WidgetLaunch.REVIEW,WidgetLaunch.from(shadowOf(context as Application).nextStartedActivity)!!.kind)
            assertTrue(root.findViewById<View>(R.id.widget_date).contentDescription.toString().contains(Solar.label(LocalDate.ofEpochDay(day))))
            listOf("widget_today","widget_tomorrow","widget_next_week","widget_previous","widget_next").forEach{name->
                val id=context.resources.getIdentifier(name,"id",context.packageName);assertNull(root.findViewById<View>(id))
            }
        }
    }
    @Test fun bothEmptyWidgetsKeepARealScrollableCalendarInsideTheirWholeContentArea() {
        for(meetings in listOf(false,true)) {
            val root=host(Snapshot(),meetings)
            assertEquals("۰",root.findViewById<TextView>(R.id.widget_count).text.toString())
            val list=root.findViewById<ListView>(R.id.widget_list)
            assertEquals(30,list.count);assertTrue(list.canScrollList(1))
            assertFalse(list.isNestedScrollingEnabled);assertEquals(View.OVER_SCROLL_ALWAYS,list.overScrollMode)
            list.scrollListBy(220);assertTrue(list.firstVisiblePosition>0)
            assertTrue(list.height>650)
        }
    }
    @Test fun dateGroupingAndOrderRemainCorrectAcrossSolarMonthAndThirtyDayBoundary() {
        val start=Solar.toDate(1405,8,30)!!.toEpochDay()
        val data=Snapshot(meetings=listOf(Meeting(id=1,title="عصر",date=start,start=900,end=960),Meeting(id=2,title="صبح",date=start,start=480,end=540),Meeting(id=3,title="بعدی",date=start+1),Meeting(id=4,title="خارج",date=start+30)))
        val rows=WidgetAgenda.rows(data,true,start)
        assertEquals(31,rows.size);assertEquals(listOf(2L,1L,3L),rows.take(3).map{it.meeting?.id})
        assertTrue(rows[0].showDate);assertFalse(rows[1].showDate);assertTrue(rows[2].showDate)
        assertEquals(9,Solar.parts(LocalDate.ofEpochDay(rows[2].day)).month)
        assertEquals(rows.size,rows.map{it.stableId}.distinct().size)
        assertEquals(1,WidgetAgenda.rows(Snapshot(),true,WidgetDays.max).size)
    }
    @Test fun meetingCollectionOpensTheExactMeetingAndEmptyDate() {
        val day=LocalDate.now().toEpochDay()
        val root=host(Snapshot(meetings=listOf(Meeting(id=11,title="جلسه فردا",date=day+1,start=480,end=540))),true)
        val list=root.findViewById<ListView>(R.id.widget_list)
        val first=list.getChildAt(1)
        assertEquals("جلسه فردا",first.findViewById<TextView>(R.id.widget_row_title).text.toString())
        assertEquals("۰۸:۰۰ تا ۰۹:۰۰",first.findViewById<TextView>(R.id.widget_row_detail).text.toString())
        list.performItemClick(first,1,list.adapter.getItemId(1))
        val meeting=shadowOf(context as Application).nextStartedActivity
        assertEquals(11L,meeting.getLongExtra("meeting_id",0));assertEquals(day+1,WidgetLaunch.from(meeting)!!.day)
        list.performItemClick(list.getChildAt(0),0,list.adapter.getItemId(0))
        val empty=shadowOf(context as Application).nextStartedActivity
        assertEquals(0L,empty.getLongExtra("meeting_id",0));assertEquals(day,WidgetLaunch.from(empty)!!.day)
    }
    @Test fun taskCollectionUsesSameLayoutAndKeepsOwnerStatusAndTaskRoute() {
        val day=LocalDate.now().toEpochDay()
        val root=host(Snapshot(tasks=listOf(Resolution(id=9,title="گزارش",owner="احمدی",due=day+1,status=Status.DONE))),false)
        val list=root.findViewById<ListView>(R.id.widget_list);val row=list.getChildAt(1)
        assertEquals("احمدی - انجام‌شده",row.findViewById<TextView>(R.id.widget_row_detail).text.toString())
        list.performItemClick(row,1,list.adapter.getItemId(1))
        val intent=shadowOf(context as Application).nextStartedActivity
        assertEquals(9L,intent.getLongExtra("task_id",0));assertEquals(WidgetLaunch.REVIEW,WidgetLaunch.from(intent)!!.kind)
        root.findViewById<View>(R.id.widget_new_task).performClick()
        assertEquals(WidgetLaunch.NEW_TASK,shadowOf(context as Application).nextStartedActivity.action)
    }
    @Test @Config(sdk=[28]) fun oldAndroidFactoryKeepsLargeFutureCollectionsAndRefreshesDeletedMeetings() {
        val day=LocalDate.now().toEpochDay()
        val ids=(0 until 45).map{store.saveMeeting(Meeting(title="جلسه $it",date=day+1,start=480+it,end=600+it))}
        val factory=ReviewWidgetFactory(context,33,true);factory.onCreate();assertEquals(74,factory.getCount())
        assertEquals(74,(0 until factory.getCount()).map{factory.getItemId(it)}.distinct().size)
        store.deleteMeeting(ids.first());factory.onDataSetChanged();assertEquals(73,factory.getCount())
        WidgetDays.move(context,33,-1);factory.onDataSetChanged();assertEquals(73,factory.getCount())
        MeetingWidget.build(context,33,store.snapshot())
        assertNull(factory.getViewAt(-1));factory.onDestroy();assertEquals(0,factory.getCount())
    }
    @Test @Config(sdk=[28]) fun taskFactoryIncludesAllFutureTasksAndLeavesPastTasksOut() {
        val day=LocalDate.now().toEpochDay()
        store.saveTask(Resolution(title="قدیمی",owner="الف",due=day-1),emptyList())
        repeat(60){store.saveTask(Resolution(title="فردا $it",owner="ب",due=day+1),emptyList())}
        val factory=ReviewWidgetFactory(context,10);factory.onCreate();assertEquals(89,factory.getCount())
        val row=factory.getViewAt(1)!!.apply(context,FrameLayout(context))
        assertEquals("فردا 0",row.findViewById<TextView>(R.id.widget_row_title).text.toString())
        ReviewWidget.build(context,10,store.snapshot());factory.onDestroy()
    }
    @Test fun deputyHasNoCreateButtonInEitherAgenda() {
        OrgAccess.set(context,SharedSession("https://test.invalid","","","","معاون","deputy","person"))
        for(meetings in listOf(false,true)) {
            val root=host(Snapshot(),meetings)
            assertEquals(View.GONE,root.findViewById<View>(if(meetings) R.id.widget_new_meeting else R.id.widget_new_task).visibility)
        }
    }
}
