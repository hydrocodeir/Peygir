package ir.peygir.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import java.time.LocalDate

data class WidgetLaunch(val kind: String, val day: Long, val token: Long = System.nanoTime()) {
    companion object {
        const val REVIEW="ir.peygir.app.widget.REVIEW"
        const val NEW_TASK="ir.peygir.app.widget.NEW_TASK"
        const val NEW_MEETING="ir.peygir.app.widget.NEW_MEETING"
        fun from(intent: Intent): WidgetLaunch? = intent.action?.takeIf{it in setOf(REVIEW,NEW_TASK,NEW_MEETING)}?.let {
            WidgetLaunch(it,WidgetDays.clamp(intent.getLongExtra("day",LocalDate.now().toEpochDay())))
        }
    }
}

object WidgetDays {
    val min: Long get()=Solar.toDate(1200,1,1)!!.toEpochDay()
    val max: Long get()=Solar.toDate(1600,12,29)!!.toEpochDay()
    fun clamp(day: Long)=day.coerceIn(min,max)
    fun selected(context: Context, id: Int, today: Long = LocalDate.now().toEpochDay()): Long {
        val p=context.getSharedPreferences("review_widgets",0)
        return if(p.getBoolean("follow_$id",true)) today else clamp(p.getLong("day_$id",today))
    }
    fun move(context: Context, id: Int, delta: Int) {
        val day=clamp(selected(context,id)+delta.coerceIn(-1,1))
        context.getSharedPreferences("review_widgets",0).edit().putLong("day_$id",day).putBoolean("follow_$id",false).apply()
    }
    fun today(context: Context, id: Int) {context.getSharedPreferences("review_widgets",0).edit().putBoolean("follow_$id",true).remove("day_$id").apply()}
    fun jump(context: Context, id: Int, offset: Int, today: Long = LocalDate.now().toEpochDay()) {
        require(offset==1 || offset==7)
        context.getSharedPreferences("review_widgets",0).edit().putLong("day_$id",clamp(today+offset)).putBoolean("follow_$id",false).apply()
    }
    fun remove(context: Context, id: Int) {context.getSharedPreferences("review_widgets",0).edit().remove("day_$id").remove("follow_$id").apply()}
    fun tasks(data: Snapshot, day: Long)=ReviewDays.forDay(data,day,false).tasks
}

class ReviewWidget: AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending=goAsync()
        Reminders.background {try {update(context,ids)} finally {pending?.finish()}}
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {onUpdate(context,manager,intArrayOf(id))}
    override fun onDeleted(context: Context, ids: IntArray) {ids.forEach{WidgetDays.remove(context,it)}}
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action in setOf(PREVIOUS,NEXT,TODAY,TOMORROW,NEXT_WEEK)) {
            val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
            if(id !in AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,ReviewWidget::class.java))) return
            val pending=goAsync()
            Reminders.background {try {
                when(intent.action) {
                    TODAY -> WidgetDays.today(context,id)
                    TOMORROW -> WidgetDays.jump(context,id,1)
                    NEXT_WEEK -> WidgetDays.jump(context,id,7)
                    else -> WidgetDays.move(context,id,if(intent.action==PREVIOUS) -1 else 1)
                }
                update(context,intArrayOf(id))
            } finally {pending?.finish()}}
            return
        }
        super.onReceive(context,intent)
        if(intent.action in setOf(Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) onUpdate(context,AppWidgetManager.getInstance(context),AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,ReviewWidget::class.java)))
    }
    companion object {
        const val PREVIOUS="ir.peygir.app.widget.PREVIOUS"
        const val NEXT="ir.peygir.app.widget.NEXT"
        const val TODAY="ir.peygir.app.widget.TODAY"
        const val TOMORROW="ir.peygir.app.widget.TOMORROW"
        const val NEXT_WEEK="ir.peygir.app.widget.NEXT_WEEK"
        fun updateAll(context: Context) {
            val ids=AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,ReviewWidget::class.java))
            if(ids.isNotEmpty()) update(context,ids)
        }
        private fun update(context: Context, ids: IntArray) {
            if(ids.isEmpty()) return
            val manager=AppWidgetManager.getInstance(context)
            try {
                val data=Store.get(context).snapshot()
                ids.forEach{id->manager.updateAppWidget(id,build(context,id,data))}
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(ids,R.id.widget_list)
            } catch(e:Exception) {android.util.Log.w("PeygirWidget","Widget refresh failed",e)}
        }
        fun build(context: Context, id: Int, data: Snapshot): RemoteViews {
            val day=WidgetDays.selected(context,id)
            val tasks=WidgetDays.tasks(data,day)
            val views=RemoteViews(context.packageName,R.layout.review_widget)
            views.setTextViewText(R.id.widget_date,Solar.label(LocalDate.ofEpochDay(day)))
            views.setTextViewText(R.id.widget_summary,"${tasks.size.fa()} مصوبه • ${tasks.count{it.status==Status.DONE}.fa()} انجام‌شده")
            views.setViewVisibility(R.id.widget_empty,if(tasks.isEmpty()) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_list,if(tasks.isEmpty()) View.GONE else View.VISIBLE)
            views.setEmptyView(R.id.widget_list,R.id.widget_empty)
            views.setOnClickPendingIntent(R.id.widget_previous,navigate(context,id,PREVIOUS))
            views.setOnClickPendingIntent(R.id.widget_next,navigate(context,id,NEXT))
            views.setOnClickPendingIntent(R.id.widget_today,navigate(context,id,TODAY))
            views.setOnClickPendingIntent(R.id.widget_tomorrow,navigate(context,id,TOMORROW))
            views.setOnClickPendingIntent(R.id.widget_next_week,navigate(context,id,NEXT_WEEK))
            views.setBoolean(R.id.widget_previous,"setEnabled",day>WidgetDays.min)
            views.setBoolean(R.id.widget_next,"setEnabled",day<WidgetDays.max)
            val review=launch(context,id,WidgetLaunch.REVIEW,day)
            views.setOnClickPendingIntent(R.id.widget_brand,review)
            views.setOnClickPendingIntent(R.id.widget_date,review)
            views.setOnClickPendingIntent(R.id.widget_summary,review)
            views.setOnClickPendingIntent(R.id.widget_new_task,launch(context,id,WidgetLaunch.NEW_TASK,day))
            views.setOnClickPendingIntent(R.id.widget_new_meeting,launch(context,id,WidgetLaunch.NEW_MEETING,day))
            val template=Intent(context,MainActivity::class.java).setAction("ir.peygir.app.widget.OPEN_TASK").setData(Uri.parse("peygir://widget/$id/task"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            views.setPendingIntentTemplate(R.id.widget_list,PendingIntent.getActivity(context,id,template,PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0))
            if(Build.VERSION.SDK_INT>=31 && tasks.size<=30) {
                val items=RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                tasks.forEach{items.addItem(it.id,row(context,it))}
                views.setRemoteAdapter(R.id.widget_list,items.build())
            } else {
                @Suppress("DEPRECATION")
                views.setRemoteAdapter(R.id.widget_list,Intent(context,ReviewWidgetService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).setData(Uri.parse("peygir://widget/$id/day/$day")))
            }
            return views
        }
        fun row(context: Context, task: Resolution): RemoteViews = RemoteViews(context.packageName,R.layout.review_widget_row).apply {
            setTextViewText(R.id.widget_row_title,task.title)
            setTextViewText(R.id.widget_row_detail,"${task.owner} • ${task.status.label}")
            setTextColor(R.id.widget_row_detail,context.getColor(if(task.status==Status.DONE) R.color.widget_done else R.color.widget_muted))
            setOnClickFillInIntent(R.id.widget_row,Intent().putExtra("task_id",task.id))
        }
        private fun navigate(context: Context, id: Int, action: String): PendingIntent = PendingIntent.getBroadcast(context,id,
            Intent(context,ReviewWidget::class.java).setAction(action).setData(Uri.parse("peygir://widget/$id/${action.substringAfterLast('.')}"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun launch(context: Context, id: Int, action: String, day: Long): PendingIntent = PendingIntent.getActivity(context,id,
            Intent(context,MainActivity::class.java).setAction(action).setData(Uri.parse("peygir://widget/$id/${action.substringAfterLast('.')}"))
                .putExtra("day",day).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class ReviewWidgetService: RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory=ReviewWidgetFactory(applicationContext,intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,0))
}
class ReviewWidgetFactory(private val context: Context, private val id: Int): RemoteViewsService.RemoteViewsFactory {
    private var tasks=emptyList<Resolution>()
    override fun onCreate() {onDataSetChanged()}
    override fun onDataSetChanged() {tasks=WidgetDays.tasks(Store.get(context).snapshot(),WidgetDays.selected(context,id))}
    override fun onDestroy() {tasks=emptyList()}
    override fun getCount()=tasks.size
    override fun getViewAt(position: Int)=tasks.getOrNull(position)?.let{ReviewWidget.row(context,it)}
    override fun getLoadingView(): RemoteViews?=null
    override fun getViewTypeCount()=1
    override fun getItemId(position: Int)=tasks.getOrNull(position)?.id ?: position.toLong()
    override fun hasStableIds()=true
}
