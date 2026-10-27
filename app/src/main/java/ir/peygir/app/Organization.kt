package ir.peygir.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

data class CompanyUser(val id: String,val name: String,val role: String,val active: Boolean=true,val username: String="")
internal object OrgAccess {
    fun role(context: Context)=context.getSharedPreferences("organization_access",0).getString("role","local") ?: "local"
    fun id(context: Context)=context.getSharedPreferences("organization_access",0).getString("id","").orEmpty()
    fun deputy(context: Context)=role(context)=="deputy"
    fun set(context: Context,session: SharedSession?) {context.getSharedPreferences("organization_access",0).edit().clear().apply {if(session!=null) putString("role",session.role).putString("id",session.userId)}.commit()}
    fun targetStatus(context: Context,task: Resolution,status: Status): Status = if(status==Status.DONE && task.assignee!=null && (role(context)!="manager" || task.status!=Status.REVIEW)) Status.REVIEW else status
    fun statuses(context: Context,task: Resolution)=if(deputy(context)) {if(task.status==Status.DONE) emptyList() else listOf(Status.WAITING,Status.ACTIVE,Status.REVIEW)} else Status.entries.filter{it!=Status.DONE || task.assignee==null || (role(context)=="manager" && task.status==Status.REVIEW)}
}
internal object Organization {
    val members=MutableStateFlow<List<CompanyUser>>(emptyList())
    fun create(db: SQLiteDatabase) {
        fun add(table: String,name: String,type: String) {
            val exists=db.rawQuery("PRAGMA table_info($table)",null).use{c->var found=false;while(c.moveToNext()) if(c.getString(1)==name) found=true;found}
            if(!exists) db.execSQL("ALTER TABLE $table ADD COLUMN $name $type")
        }
        for((key,type) in mapOf("start" to "INTEGER","end" to "INTEGER","location" to "TEXT NOT NULL DEFAULT ''","attendees" to "TEXT NOT NULL DEFAULT '[]'","repeat_rule" to "TEXT NOT NULL DEFAULT 'NONE'","repeat_until" to "INTEGER","series" to "TEXT")) add("meetings",key,type)
        for((key,type) in mapOf("assignee" to "TEXT","submitted" to "INTEGER NOT NULL DEFAULT 0","approved" to "INTEGER NOT NULL DEFAULT 0","approved_by" to "TEXT")) add("tasks",key,type)
        add("sync_objects","view","TEXT NOT NULL DEFAULT ''")
        // A v2 in-flight request has the old payload shape; rebuild it once with the new fields.
        db.execSQL("UPDATE sync_dirty SET operation=NULL,payload=NULL,base_revision=NULL")
    }
    fun users(store: Store): List<CompanyUser> = store.syncDb {db->db.rawQuery("SELECT value FROM sync_meta WHERE key='directory'",null).use {if(it.moveToFirst()) parseUsers(JSONArray(it.getString(0))) else emptyList()}}
    fun parseUsers(a: JSONArray)=(0 until a.length()).map{a.getJSONObject(it).let {p->CompanyUser(p.getString("id"),p.getString("name"),p.getString("role"),p.optInt("active",1)==1,p.optString("username"))}}
    fun receiveUsers(store: Store,a: JSONArray) {store.syncDb{it.execSQL("INSERT OR REPLACE INTO sync_meta(key,value) VALUES('directory',?)",arrayOf(a.toString()))};members.value=parseUsers(a)}
    fun account(context: Context,payload: JSONObject): JSONObject {
        val s=SharedCredentials(context).read() ?: error("ابتدا وارد حساب شوید.")
        val response=SharedHttp(s.endpoint,s.token).post("/v2/users",payload)
        receiveUsers(Store.get(context),response.getJSONArray("users"));SharedSync.revision.value++;return response
    }
}
private fun Cursor.nullInt(key: String)=getColumnIndexOrThrow(key).let{if(isNull(it)) null else getInt(it)}
private fun Cursor.nullLong(key: String)=getColumnIndexOrThrow(key).let{if(isNull(it)) null else getLong(it)}
private fun Cursor.nullString(key: String)=getColumnIndexOrThrow(key).let{if(isNull(it)) null else getString(it)}
internal fun Meeting.withOrganization(c: Cursor)=copy(start=c.nullInt("start"),end=c.nullInt("end"),location=c.nullString("location").orEmpty(),attendees=JSONArray(c.nullString("attendees") ?: "[]").strings(),repeat=c.nullString("repeat_rule") ?: "NONE",until=c.nullLong("repeat_until"),series=c.nullString("series"))
internal fun Resolution.withOrganization(c: Cursor)=copy(assignee=c.nullString("assignee"),submitted=c.nullLong("submitted") ?: 0,approved=c.nullLong("approved") ?: 0,approvedBy=c.nullString("approved_by"))
internal fun JSONArray.strings()=(0 until length()).map{getString(it)}
internal fun ContentValues.meetingOrganization(m: Meeting) {put("start",m.start);put("end",m.end);put("location",m.location);put("attendees",JSONArray(m.attendees).toString());put("repeat_rule",m.repeat);put("repeat_until",m.until);put("series",m.series)}
internal fun ContentValues.taskOrganization(t: Resolution) {put("assignee",t.assignee);put("submitted",t.submitted);put("approved",t.approved);put("approved_by",t.approvedBy)}
internal fun JSONObject.meetingOrganization(m: Meeting): JSONObject = put("start",m.start ?: JSONObject.NULL).put("end",m.end ?: JSONObject.NULL).put("location",m.location).put("attendees",JSONArray(m.attendees)).put("repeat",m.repeat).put("until",m.until ?: JSONObject.NULL).put("series",m.series ?: JSONObject.NULL)
internal fun JSONObject.taskOrganization(t: Resolution): JSONObject = put("assignee",t.assignee ?: JSONObject.NULL).put("submitted",t.submitted).put("approved",t.approved).put("approvedBy",t.approvedBy ?: JSONObject.NULL)
internal fun JSONObject.nullableString(key: String)=if(isNull(key) || !has(key)) null else getString(key)
internal fun JSONObject.nullableLong(key: String)=if(isNull(key) || !has(key)) null else getLong(key)
internal fun Meeting.fromOrganization(p: JSONObject)=copy(start=p.nullableLong("start")?.toInt(),end=p.nullableLong("end")?.toInt(),location=p.optString("location"),attendees=p.optJSONArray("attendees")?.strings().orEmpty(),repeat=p.optString("repeat","NONE"),until=p.nullableLong("until"),series=p.nullableString("series"))
internal fun Resolution.fromOrganization(p: JSONObject)=copy(assignee=p.nullableString("assignee"),submitted=p.optLong("submitted"),approved=p.optLong("approved"),approvedBy=p.nullableString("approvedBy"))
internal object MeetingRecurrence {
    val labels=linkedMapOf("NONE" to "بدون تکرار","DAILY" to "هر روز","WEEKLY" to "هر هفته","MONTHLY" to "هر ماه شمسی")
    fun expand(m: Meeting): List<Meeting> {
        require(m.repeat in labels)
        require((m.id!=0L && m.start==null && m.end==null) || m.start!=null && m.end!=null && m.start in 0..1438 && m.end in m.start+1..1439) {"ساعت پایان باید پس از شروع باشد."}
        if(m.id!=0L || m.repeat=="NONE") return listOf(m)
        val end=m.until ?: error("پایان تکرار را انتخاب کنید.")
        require(end in m.date..m.date+366 && end<=WidgetDays.max) {"پایان تکرار باید حداکثر یک سال بعد باشد."}
        val first=LocalDate.ofEpochDay(m.date);val wanted=Solar.parts(first).day;val series=m.series ?: UUID.randomUUID().toString()
        val result=mutableListOf<Meeting>();var current=first
        while(current.toEpochDay()<=end) {
            result.add(m.copy(date=current.toEpochDay(),series=series))
            current=when(m.repeat) {"DAILY"->current.plusDays(1);"WEEKLY"->current.plusWeeks(1);else->{val next=Solar.nextMonth(current);val length=ChronoUnit.DAYS.between(next,Solar.nextMonth(next)).toInt();next.plusDays(minOf(wanted,length).toLong()-1)}}
        }
        return result
    }
}
internal fun Meeting.clockLabel(): String = if(start==null) "بدون ساعت" else "%02d:%02d تا %02d:%02d".format(java.util.Locale.US,start/60,start%60,(end ?: start)/60,(end ?: start)%60).fa()

internal object MeetingSchedule {
    val order=compareBy<Meeting>{it.date}.thenBy{it.start ?: Int.MAX_VALUE}.thenBy{it.end ?: Int.MAX_VALUE}.thenBy{it.id}
    fun forDay(data: Snapshot,day: Long)=data.meetings.filter{it.date==day}.sortedWith(order)
    fun requireTime(m: Meeting) {require(m.start!=null && m.end!=null && m.start in 0..1438 && m.end in m.start+1..1439) {"ساعت شروع و پایان جلسه الزامی است؛ پایان باید بعد از شروع و در همان روز باشد."}}
}
