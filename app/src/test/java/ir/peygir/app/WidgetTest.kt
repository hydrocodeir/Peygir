package ir.peygir.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.FrameLayout
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
    }
    @After fun cleanup() {store.close()}
    @Test fun eachWidgetKeepsItsDayAndTodayResumesFollowingTheCalendar() {
        val today=LocalDate.now().toEpochDay()
        WidgetDays.move(context,1,1);WidgetDays.move(context,1,1)
        assertEquals(today+2,WidgetDays.selected(context,1));assertEquals(today,WidgetDays.selected(context,2))
        assertEquals(today+2,WidgetDays.selected(context,1,today+8))
        WidgetDays.today(context,1);assertEquals(today+8,WidgetDays.selected(context,1,today+8))
        context.getSharedPreferences("review_widgets",0).edit().putBoolean("follow_1",false).putLong("day_1",WidgetDays.max).commit()
        WidgetDays.move(context,1,1);assertEquals(WidgetDays.max,WidgetDays.selected(context,1))
        ReviewWidget().onDeleted(context,intArrayOf(1));assertEquals(today,WidgetDays.selected(context,1))
    }
    @Test fun widgetUsesSameDayGroupingAndPriorityAsReviewAndShowsCompletedTasks() {
        val day=LocalDate.now().toEpochDay()
        val data=Snapshot(tasks=listOf(Resolution(id=1,title="عادی",owner="احمدی",due=day),Resolution(id=2,title="فوری",owner="رضایی",due=day,priority=Priority.URGENT),Resolution(id=3,title="انجام‌شده",owner="کریمی",due=day,status=Status.DONE),Resolution(id=4,title="فردا",owner="قاسمی",due=day+1)))
        assertEquals(listOf(2L,1L,3L),WidgetDays.tasks(data,day).map{it.id})
        val host=android.appwidget.AppWidgetHostView(context)
        host.setAppWidget(4,android.appwidget.AppWidgetProviderInfo().apply{provider=android.content.ComponentName(context,ReviewWidget::class.java)})
        val root=ReviewWidget.build(context,4,data).apply(context,host)
        assertEquals("۳ مصوبه • ۱ انجام‌شده",root.findViewById<TextView>(R.id.widget_summary).text.toString())
        assertEquals(View.GONE,root.findViewById<View>(R.id.widget_empty).visibility)
        assertEquals(3,root.findViewById<android.widget.ListView>(R.id.widget_list).count)
    }
    @Test fun shortcutsAnchorToTomorrowAndTheSameWeekdayNextWeekForEachWidget() {
        val today=LocalDate.now().toEpochDay()
        WidgetDays.move(context,1,-1)
        WidgetDays.jump(context,1,1,today);assertEquals(today+1,WidgetDays.selected(context,1))
        WidgetDays.jump(context,1,7,today);assertEquals(today+7,WidgetDays.selected(context,1))
        WidgetDays.jump(context,1,7,today);assertEquals(today+7,WidgetDays.selected(context,1))
        assertEquals(today,WidgetDays.selected(context,2))
        val root=ReviewWidget.build(context,1,Snapshot()).apply(context,FrameLayout(context))
        listOf(R.id.widget_tomorrow to ReviewWidget.TOMORROW,R.id.widget_next_week to ReviewWidget.NEXT_WEEK).forEach {(id,action)->
            root.findViewById<View>(id).performClick()
            assertEquals(action,shadowOf(context as Application).broadcastIntents.last().action)
        }
        WidgetDays.today(context,1);assertEquals(today,WidgetDays.selected(context,1))
    }
    @Test fun widgetButtonsLaunchTheMatchingFormOrReviewOnTheSelectedDate() {
        WidgetDays.move(context,7,1)
        val day=WidgetDays.selected(context,7)
        val root=ReviewWidget.build(context,7,Snapshot()).apply(context,FrameLayout(context))
        listOf(R.id.widget_new_task to WidgetLaunch.NEW_TASK,R.id.widget_new_meeting to WidgetLaunch.NEW_MEETING,R.id.widget_date to WidgetLaunch.REVIEW).forEach{(id,kind)->
            root.findViewById<View>(id).performClick()
            val intent=shadowOf(context as Application).nextStartedActivity
            assertEquals(MainActivity::class.java.name,intent.component!!.className)
            val request=WidgetLaunch.from(intent)!!
            assertEquals(kind,request.kind);assertEquals(day,request.day)
        }
        assertNull(WidgetLaunch.from(Intent()))
        assertEquals(WidgetDays.max,WidgetLaunch.from(Intent().setAction(WidgetLaunch.REVIEW).putExtra("day",Long.MAX_VALUE))!!.day)
    }
    @Test fun collectionReloadsAfterDayAndDatabaseChangesWithoutTruncatingLargeDays() {
        val today=LocalDate.now().toEpochDay()
        store.saveTask(Resolution(title="امروز",owner="احمدی",due=today),emptyList())
        repeat(60) {store.saveTask(Resolution(title="فردا $it",owner="رضایی",due=today+1),emptyList())}
        val factory=ReviewWidgetFactory(context,10);factory.onCreate();assertEquals(1,factory.getCount())
        WidgetDays.move(context,10,1);factory.onDataSetChanged();assertEquals(60,factory.getCount())
        val row=factory.getViewAt(0)!!.apply(context,FrameLayout(context))
        assertEquals("فردا 0",row.findViewById<TextView>(R.id.widget_row_title).text.toString())
        assertTrue(factory.hasStableIds());assertNull(factory.getViewAt(-1))
        ReviewWidget.build(context,10,store.snapshot()) // large collection uses the service adapter
        factory.onDestroy();assertEquals(0,factory.getCount())
    }
    @Test @Config(sdk=[28]) fun oldAndroidInflatesWidgetAndUsesTheSameNavigationContracts() {
        val root=ReviewWidget.build(context,1,Snapshot()).apply(context,FrameLayout(context))
        assertEquals(View.VISIBLE,root.findViewById<View>(R.id.widget_empty).visibility)
        root.findViewById<View>(R.id.widget_new_task).performClick()
        assertEquals(WidgetLaunch.NEW_TASK,shadowOf(context as Application).nextStartedActivity.action)
    }
}
