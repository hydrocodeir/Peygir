package ir.peygir.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.time.LocalDate
import java.time.ZoneId

internal data class MeetingAlertPlan(val meeting: Meeting,val at: Long,val end: Long,val key: String)

/** Local scheduling only: neither per-meeting overrides nor delivery markers are synchronized. */
internal object MeetingAlerts {
    const val CHANNEL="meeting_reminders"
    fun create(db: SQLiteDatabase) {db.execSQL("CREATE TABLE IF NOT EXISTS meeting_alerts(meeting_id INTEGER PRIMARY KEY REFERENCES meetings(id) ON DELETE CASCADE,lead INTEGER,delivered_key TEXT NOT NULL DEFAULT '')")}
    fun overrides(db: SQLiteDatabase)=db.rawQuery("SELECT meeting_id,lead FROM meeting_alerts",null).use{c->buildMap<Long,Int?>{while(c.moveToNext()) put(c.getLong(0),if(c.isNull(1)) null else c.getInt(1))}}
    fun save(db: SQLiteDatabase,id: Long,lead: Int?) {
        require(lead==null || lead in UserOptions.leads)
        val previous=db.rawQuery("SELECT lead FROM meeting_alerts WHERE meeting_id=?",arrayOf(id.toString())).use{c->if(!c.moveToFirst()) false to null else true to if(c.isNull(0)) null else c.getInt(0)}
        if(previous.second==lead && (previous.first || lead==null)) return
        if(!previous.first) db.insertOrThrow("meeting_alerts",null,ContentValues().apply{put("meeting_id",id);put("lead",lead)})
        else db.update("meeting_alerts",ContentValues().apply{put("lead",lead);put("delivered_key","")},"meeting_id=?",arrayOf(id.toString()))
    }
    fun plans(data: Snapshot,defaultLead: Int,zone: ZoneId=ZoneId.systemDefault()): List<MeetingAlertPlan> = data.meetings.mapNotNull{m->
        val lead=m.alertMinutes ?: defaultLead
        if(lead<0 || m.start==null || m.end==null || m.end<=m.start) null else {
            val day=LocalDate.ofEpochDay(m.date)
            val start=day.atStartOfDay().plusMinutes(m.start.toLong()).atZone(zone).toInstant().toEpochMilli()
            val end=day.atStartOfDay().plusMinutes(m.end.toLong()).atZone(zone).toInstant().toEpochMilli()
            MeetingAlertPlan(m,start-lead*60_000L,end,"${m.date}|${m.start}|$lead")
        }
    }
    fun next(context: Context,store: Store,data: Snapshot,now: Long): Long? {
        val delivered=delivered(store)
        return plans(data,UserOptions.read(context).meetingLead).filter{it.at>now && delivered[it.meeting.id]!=it.key}.minOfOrNull{it.at}
    }
    private fun delivered(store: Store)=store.syncDb{db->db.rawQuery("SELECT meeting_id,delivered_key FROM meeting_alerts",null).use{c->buildMap<Long,String>{while(c.moveToNext()) put(c.getLong(0),c.getString(1))}}}
    private fun tag(c: Context,id: Long)="meeting:${Accounts.id(c).takeIf{Accounts.notificationTag(c)!=null}?.let{it+":"}.orEmpty()}$id"
    fun process(context: Context,store: Store,data: Snapshot,now: Long) {
        val manager=context.getSystemService(NotificationManager::class.java)
        val plans=plans(data,UserOptions.read(context).meetingLead)
        val byId=plans.associateBy{it.meeting.id}
        manager.activeNotifications.filter{it.tag?.startsWith("meeting:")==true && (it.notification.extras.getString(Accounts.EXTRA).orEmpty()==Accounts.id(context) || Accounts.notificationTag(context)==null && !it.notification.extras.containsKey(Accounts.EXTRA))}.forEach{n->
            val id=n.tag.substringAfterLast(':').toLongOrNull();val current=byId[id]
            if(current==null || current.end<=now || n.notification.extras.getString("peygir_meeting_key")!=current.key) manager.cancel(n.tag,n.id)
        }
        val sent=delivered(store)
        plans.filter{it.at<=now && now-it.at<=15*60_000 && it.end>now && sent[it.meeting.id]!=it.key}.forEach{p->
            if(!Reminders.allowed(context,CHANNEL)) return@forEach
            val m=p.meeting
            val intent=Accounts.intent(context,Intent(context,MainActivity::class.java)).putExtra("meeting_id",m.id).setData(Uri.parse("peygir://meeting-reminder/${Accounts.id(context)}/${m.id}"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val open=PendingIntent.getActivity(context,0,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle((Accounts.profile(context)?.label?.let{it+" - "} ?: "")+"یادآوری جلسه - ${m.title}")
                .setContentText("${Solar.label(LocalDate.ofEpochDay(m.date))} - ${m.clockLabel()}").setContentIntent(open).setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addExtras(android.os.Bundle().apply{putString("peygir_meeting_key",p.key);putString(Accounts.EXTRA,Accounts.id(context))}).build()
            runCatching {
                manager.notify(tag(context,m.id),0,n)
                val valid=store.syncDb{db->
                    val exists=db.rawQuery("SELECT 1 FROM meetings WHERE id=? AND date=? AND start=?",arrayOf(m.id.toString(),m.date.toString(),m.start.toString())).use{it.moveToFirst()}
                    if(exists) {
                        db.insertWithOnConflict("meeting_alerts",null,ContentValues().apply{put("meeting_id",m.id);put("lead",m.alertMinutes)},SQLiteDatabase.CONFLICT_IGNORE)
                        db.update("meeting_alerts",ContentValues().apply{put("delivered_key",p.key)},"meeting_id=?",arrayOf(m.id.toString()))
                    };exists
                }
                if(!valid) manager.cancel(tag(context,m.id),0)
            }
        }
    }
    fun channel(manager: NotificationManager) {manager.createNotificationChannel(NotificationChannel(CHANNEL,"یادآوری جلسات",NotificationManager.IMPORTANCE_HIGH))}
}
