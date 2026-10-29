package ir.peygir.app

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.*
import java.util.concurrent.Executors

object Reminders {
    private const val CHANNEL = "follow_up"
    const val REVIEW_CHANNEL = "daily_review"
    private const val REVIEW_ID = -50
    private val worker = Executors.newSingleThreadExecutor()
    fun background(block: () -> Unit) { worker.execute(block) }
    fun exact(context: Context) = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun channels(context: Context) {
        context.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CHANNEL,"پیگیری مصوبات",NotificationManager.IMPORTANCE_HIGH).apply { description="زمان بررسی وضعیت مصوبات" })
            createNotificationChannel(NotificationChannel(REVIEW_CHANNEL,"مرور روزانه",NotificationManager.IMPORTANCE_DEFAULT).apply { description="خلاصهٔ مصوبات روز و تعداد کارهای معوق" })
            MeetingAlerts.channel(this)
        }
    }
    fun allowed(context: Context, channel: String = CHANNEL): Boolean {
        channels(context)
        return (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) && NotificationManagerCompat.from(context).areNotificationsEnabled() && context.getSystemService(NotificationManager::class.java).getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun dailyTimes(context: Context): List<LocalTime> = context.getSharedPreferences("settings",0).getString("times","08:00").orEmpty().split(',').mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }.distinct().sorted()
    fun dailyEnabled(context: Context) = context.getSharedPreferences("settings",0).getBoolean("daily",true)
    fun nextDaily(now: ZonedDateTime, times: List<LocalTime>): Long? = times.flatMap { time -> listOf(now.toLocalDate().atTime(time).atZone(now.zone),now.toLocalDate().plusDays(1).atTime(time).atZone(now.zone)) }.filter { it.isAfter(now) }.minOfOrNull { it.toInstant().toEpochMilli() }
    fun dueDaily(now: ZonedDateTime, times: List<LocalTime>): Long? = times.map {now.toLocalDate().atTime(it).atZone(now.zone)}.filter {!it.isAfter(now)}.maxOfOrNull {it.toInstant().toEpochMilli()}
    fun reviewBody(data: Snapshot, day: Long): String {
        val tasks=ReviewDays.forDay(data,day,false).tasks
        val late=data.tasks.count {it.due<day && it.status!=Status.DONE}
        return buildList {
            add(Solar.label(LocalDate.ofEpochDay(day)))
            add("${tasks.size.fa()} مصوبه - ${tasks.count{it.status==Status.DONE}.fa()} انجام‌شده - ${late.fa()} معوق")
            if(tasks.isEmpty()) add("امروز مصوبه‌ای سررسید ندارد.")
            tasks.take(8).forEach {add("- ${it.title} - ${it.owner} - ${it.status.label}")}
            if(tasks.size>8) add("و ${(tasks.size-8).fa()} مصوبهٔ دیگر؛ برای مرور کامل لمس کنید.")
        }.joinToString("\n")
    }
    private fun pending(context: Context, reviewAt: Long = 0): PendingIntent {
        val intent=Accounts.intent(context,Intent(context,ReminderReceiver::class.java)).setAction("ir.peygir.DISPATCH").putExtra("review_at",reviewAt)
        if(Accounts.notificationTag(context)!=null) intent.data=android.net.Uri.parse("peygir://dispatch/${Accounts.id(context)}")
        return PendingIntent.getBroadcast(context,1,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun cancel(context: Context) {context.getSystemService(AlarmManager::class.java).cancel(pending(Accounts.context(context)))}
    @Synchronized fun schedule(context: Context) {
        if(Accounts.explicit(context)==null) {Accounts.contexts(context).forEach{schedule(it)};return}
        if(!SharedCredentials(context).alerts()) {context.getSystemService(AlarmManager::class.java).cancel(pending(context));return}
        channels(context)
        val now=System.currentTimeMillis()
        val data=Store.get(context).snapshot()
        val active=data.tasks.filter { it.status!=Status.DONE }.map { it.id }.toSet()
        val taskAt=data.reminders.filter { !it.delivered && it.taskId in active && it.at>now }.minOfOrNull { it.at }
        val reviewAt=if(dailyEnabled(context)) nextDaily(ZonedDateTime.now(),dailyTimes(context)) else null
        val meetingAt=MeetingAlerts.next(context,Store.get(context),data,now)
        val at=listOfNotNull(taskAt,reviewAt,meetingAt).minOrNull()
        val manager=context.getSystemService(AlarmManager::class.java)
        if(at==null) { manager.cancel(pending(context)); return }
        val pi=pending(context,if(at==reviewAt) reviewAt!! else 0)
        try { if(exact(context)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi) else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi) }
        catch(_: SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,pi) }
    }
    private fun notification(context: Context, id: Int, title: String, body: String, taskId: Long = 0, reviewDay: Long? = null, token: String? = null, silent: Boolean = false): Boolean {
        val channel=if(reviewDay==null) CHANNEL else REVIEW_CHANNEL
        if(!allowed(context,channel)) return false
        val intent=Accounts.intent(context,Intent(context,MainActivity::class.java)).putExtra("task_id",taskId).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if(Accounts.notificationTag(context)!=null) intent.data=android.net.Uri.parse("peygir://account/${Accounts.id(context)}/task/$taskId")
        if(reviewDay!=null) intent.setAction(WidgetLaunch.REVIEW).setData(android.net.Uri.parse("peygir://daily-review/${Accounts.id(context)}/$reviewDay")).putExtra("day",reviewDay)
        val open=PendingIntent.getActivity(context,id,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=NotificationCompat.Builder(context,channel).setSmallIcon(R.drawable.ic_notification).setContentTitle(Accounts.profile(context)?.let{"${it.label} - $title"} ?: title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).setPriority(if(reviewDay==null) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT).setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if(silent) builder.setSilent(true)
        if(taskId>0 && token!=null) {
            listOf(ReminderActionReceiver.DONE to (if(OrgAccess.deputy(context)) "ارسال برای تأیید رئیس" else "انجام شد"),ReminderActionReceiver.SNOOZE to "یک ساعت دیگر یادآوری کن").forEach {(action,label)->
                val intent=Accounts.intent(context,Intent(context,ReminderActionReceiver::class.java)).setAction(action)
                    .setData(android.net.Uri.parse("peygir://task-action/${Accounts.id(context)}/$taskId/$token/${action.substringAfterLast('.')}"))
                    .putExtra("task_id",taskId).putExtra("token",token)
                val pending=PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(NotificationCompat.Action.Builder(0,label,pending).setAuthenticationRequired(true).build())
            }
        }
        val n=builder.addExtras(android.os.Bundle().apply{putString(Accounts.EXTRA,Accounts.id(context))}).build()
        return try { NotificationManagerCompat.from(context).notify(Accounts.notificationTag(context),id,n); true } catch(_: SecurityException) { false }
    }
    @Suppress("UNUSED_PARAMETER")
    @Synchronized fun process(context: Context, reviewAt: Long = 0) {
        if(Accounts.explicit(context)==null) {Accounts.contexts(context).forEach{process(it,reviewAt)};return}
        if(!SharedCredentials(context).alerts()) {schedule(context);return}
        val store=Store.get(context)
        val data=store.snapshot()
        val now=System.currentTimeMillis()
        MeetingAlerts.process(context,store,data,now)
        data.reminders.filter { !it.delivered && it.at<=now }.groupBy { it.taskId }.forEach { (taskId,rs) ->
            val t=data.tasks.find { it.id==taskId }
            if(t==null || t.status==Status.DONE) store.delivered(rs.map { it.id })
            else {
                val token=java.util.UUID.randomUUID().toString()
                if(notification(context,t.id.toInt(),"زمان پیگیری - ${t.title}","مسئول: ${t.owner}\nسررسید: ${Solar.label(LocalDate.ofEpochDay(t.due))}\nوضعیت: ${t.status.label}",t.id,token=token)) {
                    if(!store.activateNotification(t.id,token,rs.map{it.id})) Accounts.cancelTask(context,t.id.toInt())
                }
            }
        }
        // Upgrade already-visible notifications from older app versions without another sound.
        context.getSystemService(NotificationManager::class.java).activeNotifications
            .filter{it.tag==Accounts.notificationTag(context) && it.notification.channelId==CHANNEL && it.notification.actions.isNullOrEmpty()}
            .forEach {active->
                data.tasks.find{it.id.toInt()==active.id && it.status!=Status.DONE}?.let {task->
                    val token=java.util.UUID.randomUUID().toString()
                    if(notification(context,task.id.toInt(),"زمان پیگیری - ${task.title}","مسئول: ${task.owner}\nسررسید: ${Solar.label(LocalDate.ofEpochDay(task.due))}\nوضعیت: ${task.status.label}",task.id,token=token,silent=true)) {
                        if(!store.activateNotification(task.id,token,emptyList())) Accounts.cancelTask(context,task.id.toInt())
                    }
                }
            }
        val prefs=context.getSharedPreferences("settings",0)
        val due=dueDaily(ZonedDateTime.now(),dailyTimes(context))
        if(dailyEnabled(context) && due!=null && prefs.getLong("last_review",0)<due) {
            val today=LocalDate.now().toEpochDay()
            if(notification(context,REVIEW_ID,"مرور مصوبات امروز - پیگیر",reviewBody(data,today),reviewDay=today)) prefs.edit().putLong("last_review",due).apply()
        }
        data.tasks.filter { it.status==Status.DONE }.forEach { Accounts.cancelTask(context,it.id.toInt()) }
        schedule(context)
    }
    fun test(context: Context) = notification(context,-51,"پیگیر آماده است","اعلان‌ها فعال‌اند؛ زمان پیگیری را در فرم هر مصوبه انتخاب کنید.")
    @Synchronized fun handleAction(source: Context, intent: Intent): Boolean {
        val context=Accounts.fromIntent(source,intent) ?: return false
        if(context.getSharedPreferences("shared_status",0).getBoolean("auth_expired",false)) return false
        if(intent.action !in setOf(ReminderActionReceiver.DONE,ReminderActionReceiver.SNOOZE)) return false
        val id=intent.getLongExtra("task_id",0);val token=intent.getStringExtra("token") ?: return false
        if(id<=0 || !Store.get(context).notificationAction(id,token,intent.action==ReminderActionReceiver.SNOOZE)) return false
        Accounts.cancelTask(context,id.toInt())
        process(context);ReviewWidget.updateAll(context)
        SharedSync.request(context)
        return true
    }
}
class ReminderActionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action !in setOf(DONE,SNOOZE)) return
        val pending=goAsync()
        Reminders.background {try {Reminders.handleAction(context,intent)} catch(e:Exception) {android.util.Log.e("Peygir","Notification action failed",e)} finally {pending.finish()}}
    }
    companion object {
        const val DONE="ir.peygir.app.notification.DONE"
        const val SNOOZE="ir.peygir.app.notification.SNOOZE"
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action !in setOf("ir.peygir.DISPATCH",Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED,"android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        val result=goAsync()
        Reminders.background { try { if(intent.action=="ir.peygir.DISPATCH") Accounts.fromIntent(context,intent)?.let{Reminders.process(it,intent.getLongExtra("review_at",0))} else Reminders.process(context) } finally { result.finish() } }
    }
}
