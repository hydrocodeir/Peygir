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
        const val MEETINGS="ir.peygir.app.widget.MEETINGS"
        const val REVIEW="ir.peygir.app.widget.REVIEW"
        const val NEW_TASK="ir.peygir.app.widget.NEW_TASK"
        const val NEW_MEETING="ir.peygir.app.widget.NEW_MEETING"
        fun from(intent: Intent): WidgetLaunch? = intent.action?.takeIf{it in setOf(REVIEW,MEETINGS,NEW_TASK,NEW_MEETING)}?.let {
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

open class ReviewWidget: AppWidgetProvider() {
    protected open val meetings=false
    override fun onUpdate(context: Context,manager: AppWidgetManager,ids: IntArray) {
        val pending=goAsync()
        Reminders.background {try {update(context,ids,meetings)} finally {pending?.finish()}}
    }
    override fun onAppWidgetOptionsChanged(context: Context,manager: AppWidgetManager,id: Int,options: Bundle)=onUpdate(context,manager,intArrayOf(id))
    override fun onDeleted(context: Context,ids: IntArray) {ids.forEach{WidgetDays.remove(context,it)}}
    override fun onReceive(context: Context,intent: Intent) {
        super.onReceive(context,intent)
        if(intent.action in setOf(Intent.ACTION_DATE_CHANGED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) {
            onUpdate(context,AppWidgetManager.getInstance(context),AppWidgetManager.getInstance(context).getAppWidgetIds(component(context,meetings)))
        }
    }
    companion object {
        // Kept for old pinned PendingIntents; navigation is no longer offered by either widget.
        const val PREVIOUS="ir.peygir.app.widget.PREVIOUS"
        const val NEXT="ir.peygir.app.widget.NEXT"
        const val TODAY="ir.peygir.app.widget.TODAY"
        const val TOMORROW="ir.peygir.app.widget.TOMORROW"
        const val NEXT_WEEK="ir.peygir.app.widget.NEXT_WEEK"
        private fun component(context: Context,meetings: Boolean)=ComponentName(context,if(meetings) MeetingWidget::class.java else ReviewWidget::class.java)
        fun updateAll(context: Context) {for(meetings in listOf(false,true)) {
            val ids=AppWidgetManager.getInstance(context).getAppWidgetIds(component(context,meetings))
            if(ids.isNotEmpty()) update(context,ids,meetings)
        }}
        private fun update(context: Context,ids: IntArray,meetings: Boolean) {
            if(ids.isEmpty()) return
            try {
                val data=Store.get(context).snapshot();val manager=AppWidgetManager.getInstance(context)
                ids.forEach{manager.updateAppWidget(it,build(context,it,data,meetings))}
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(ids,R.id.widget_list)
            } catch(e: Exception) {android.util.Log.w("PeygirWidget","Widget refresh failed",e)}
        }
        fun build(context: Context,id: Int,data: Snapshot,meetings: Boolean=false): RemoteViews {
            val day=LocalDate.now().toEpochDay();val month=Solar.parts(LocalDate.ofEpochDay(day))
            val rows=WidgetAgenda.rows(data,meetings,day)
            val kind=if(meetings) WidgetLaunch.MEETINGS else WidgetLaunch.REVIEW
            val create=if(meetings) WidgetLaunch.NEW_MEETING else WidgetLaunch.NEW_TASK
            val addId=if(meetings) R.id.widget_new_meeting else R.id.widget_new_task
            return RemoteViews(context.packageName,if(meetings) R.layout.meeting_widget else R.layout.review_widget).apply {
                setTextViewText(R.id.widget_date,"${Solar.months[month.month-1]} ${month.year.fa()}")
                setContentDescription(R.id.widget_date,"${if(meetings) "جلسات" else "مصوبات"} از ${Solar.label(LocalDate.ofEpochDay(day))}")
                val count=rows.count{it.meeting!=null || it.task!=null}
                val label=if(meetings) "جلسات" else "مصوبات"
                setTextViewText(R.id.widget_summary,label)
                setTextViewText(R.id.widget_count,count.fa())
                setContentDescription(R.id.widget_count,"تعداد $label: ${count.fa()}")
                setViewVisibility(addId,if(OrgAccess.deputy(context)) View.GONE else View.VISIBLE)
                setOnClickPendingIntent(addId,launch(context,id,create,day))
                val open=launch(context,id,kind,day)
                setOnClickPendingIntent(R.id.widget_date,open);setOnClickPendingIntent(R.id.widget_summary,open);setOnClickPendingIntent(R.id.widget_count,open)
                val template=Intent(context,MainActivity::class.java).setAction(kind)
                    .setData(Uri.parse("peygir://widget/$id/${if(meetings) "meeting" else "task"}/agenda"))
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                setPendingIntentTemplate(R.id.widget_list,PendingIntent.getActivity(context,id,template,PendingIntent.FLAG_UPDATE_CURRENT or if(Build.VERSION.SDK_INT>=31) PendingIntent.FLAG_MUTABLE else 0))
                if(Build.VERSION.SDK_INT>=31 && rows.size<=60) {
                    val items=RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1)
                    rows.forEach{items.addItem(it.stableId,agendaRow(context,it,meetings))}
                    setRemoteAdapter(R.id.widget_list,items.build())
                } else {
                    @Suppress("DEPRECATION")
                    setRemoteAdapter(R.id.widget_list,Intent(context,ReviewWidgetService::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id).putExtra("meetings",meetings)
                        .setData(Uri.parse("peygir://widget/$id/${if(meetings) "meetings" else "tasks"}/agenda/$day")))
                }
            }
        }
        fun row(context: Context,task: Resolution)=agendaRow(context,WidgetAgendaRow(task.due,task=task),false)
        fun meetingRow(context: Context,m: Meeting)=agendaRow(context,WidgetAgendaRow(m.date,meeting=m),true)
        fun agendaRow(context: Context,row: WidgetAgendaRow,meetings: Boolean): RemoteViews=RemoteViews(context.packageName,R.layout.meeting_widget_row).apply {
            val date=LocalDate.ofEpochDay(row.day);val parts=Solar.parts(date)
            val weekday=listOf("شنبه","یکشنبه","دوشنبه","سه‌شنبه","چهارشنبه","پنجشنبه","جمعه")[(date.dayOfWeek.value+1)%7]
            setViewVisibility(R.id.widget_day_label,if(row.showDate) View.VISIBLE else View.INVISIBLE)
            setTextViewText(R.id.widget_day_weekday,weekday);setTextViewText(R.id.widget_day_number,parts.day.fa());setTextViewText(R.id.widget_day_month,Solar.months[parts.month-1])
            val today=row.day==LocalDate.now().toEpochDay()
            setInt(R.id.widget_day_number,"setBackgroundResource",if(today) R.drawable.widget_button else 0)
            setTextColor(R.id.widget_day_number,context.getColor(if(today) R.color.widget_primary else R.color.widget_ink))
            val title=row.meeting?.title ?: row.task?.title ?: if(meetings) "جلسه‌ای ندارید" else "مصوبه‌ای ندارید"
            val detail=row.meeting?.clockLabel() ?: row.task?.let{"${it.owner} - ${it.status.label}"}.orEmpty()
            setTextViewText(R.id.widget_row_title,title);setTextViewText(R.id.widget_row_detail,detail)
            setViewVisibility(R.id.widget_row_detail,if(detail.isBlank()) View.GONE else View.VISIBLE)
            setTextColor(R.id.widget_row_detail,context.getColor(if(row.task?.status==Status.DONE) R.color.widget_done else R.color.widget_muted))
            val open=Intent().putExtra("day",row.day).apply {row.meeting?.let{putExtra("meeting_id",it.id)};row.task?.let{putExtra("task_id",it.id)}}
            setOnClickFillInIntent(R.id.widget_row,open)
            setContentDescription(R.id.widget_row,listOf(weekday,Solar.label(date),title,detail).filter{it.isNotBlank()}.joinToString("، "))
        }
        private fun launch(context: Context,id: Int,action: String,day: Long)=PendingIntent.getActivity(context,id,
            Intent(context,MainActivity::class.java).setAction(action).setData(Uri.parse("peygir://widget/$id/${action.substringAfterLast('.')}"))
                .putExtra("day",day).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
class ReviewWidgetService: RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory=ReviewWidgetFactory(applicationContext,intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,0),intent.getBooleanExtra("meetings",false))
}
class ReviewWidgetFactory(private val context: Context,private val id: Int,private val meetings: Boolean=false): RemoteViewsService.RemoteViewsFactory {
    private var rows=emptyList<WidgetAgendaRow>()
    override fun onCreate() {onDataSetChanged()}
    override fun onDataSetChanged() {rows=WidgetAgenda.rows(Store.get(context).snapshot(),meetings,LocalDate.now().toEpochDay())}
    override fun onDestroy() {rows=emptyList()}
    override fun getCount()=rows.size
    override fun getViewAt(position: Int)=rows.getOrNull(position)?.let{ReviewWidget.agendaRow(context,it,meetings)}
    override fun getLoadingView(): RemoteViews?=null
    override fun getViewTypeCount()=1
    override fun getItemId(position: Int)=rows.getOrNull(position)?.stableId ?: position.toLong()
    override fun hasStableIds()=true
}
class MeetingWidget: ReviewWidget() {
    override val meetings=true
    companion object {
        fun build(context: Context,id: Int,data: Snapshot)=ReviewWidget.build(context,id,data,true)
        fun row(context: Context,m: Meeting)=ReviewWidget.meetingRow(context,m)
    }
}
