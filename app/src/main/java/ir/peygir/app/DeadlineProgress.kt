package ir.peygir.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal object ReservationColors {
    val background=Color(0xFFFFE082)
    val ink=Color(0xFF4E3900)
}

internal object DeadlineProgress {
    fun create(db: SQLiteDatabase) {
        var changed=false
        fun add(table: String,column: String,type: String) {
            val exists=db.rawQuery("PRAGMA table_info($table)",null).use{c->var found=false;while(c.moveToNext()) if(c.getString(1)==column) found=true;found}
            if(!exists) {db.execSQL("ALTER TABLE $table ADD COLUMN $column $type");changed=true}
        }
        add("meetings","reserved","INTEGER NOT NULL DEFAULT 0")
        add("tasks","created","INTEGER NOT NULL DEFAULT 0")
        add("tasks","extension","TEXT")
        db.execSQL("UPDATE tasks SET created=? WHERE created=0",arrayOf(System.currentTimeMillis()))
        db.execSQL("CREATE TABLE IF NOT EXISTS midpoint_delivered(task_id INTEGER PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,stamp TEXT NOT NULL)")
        if(changed) db.execSQL("UPDATE sync_dirty SET operation=NULL,payload=NULL,base_revision=NULL")
    }
    fun at(task: Resolution): Long? {
        if(task.created<=0 || task.status in listOf(Status.DONE,Status.REVIEW)) return null
        val end=LocalDate.ofEpochDay(task.due).plusDays(1).atStartOfDay(ZoneId.of("Asia/Tehran")).toInstant().toEpochMilli()
        return if(end>task.created) task.created+(end-task.created)/2 else null
    }
    private fun eligible(context: Context,t: Resolution)=if(SharedCredentials(context).connected()) t.assignee==OrgAccess.id(context) else true
    private fun stamp(t: Resolution)="${t.created}:${t.due}:${t.assignee}"
    private fun pending(context: Context,store: Store,data: Snapshot)=data.tasks.filter{eligible(context,it) && at(it)!=null}.filter{t->store.syncDb{db->db.rawQuery("SELECT stamp FROM midpoint_delivered WHERE task_id=?",arrayOf(t.id.toString())).use{!it.moveToFirst() || it.getString(0)!=stamp(t)}}}
    fun next(context: Context,store: Store,data: Snapshot,now: Long)=pending(context,store,data).mapNotNull{at(it)?.takeIf{time->time>now || Reminders.allowed(context)}?.coerceAtLeast(now+1000)}.minOrNull()
    fun process(context: Context,store: Store,data: Snapshot,now: Long,notify: (Resolution)->Boolean) {
        pending(context,store,data).filter{at(it)!!<=now}.forEach{t->if(notify(t)) store.syncDb{it.execSQL("INSERT OR REPLACE INTO midpoint_delivered VALUES(?,?)",arrayOf<Any>(t.id,stamp(t)))}}
    }
    fun request(store: Store,context: Context,task: Resolution,progress: String,reason: String,due: Long) {
        val current=store.snapshot().tasks.first{it.id==task.id}
        require(current==task) {"مصوبه تغییر کرده است؛ دوباره بررسی کنید."}
        require(current.status !in listOf(Status.DONE,Status.REVIEW) && current.assignee==OrgAccess.id(context)) {"فقط مسئول مصوبه می‌تواند درخواست تمدید ثبت کند."}
        require(current.extension?.let{JSONObject(it).optString("status")}!="PENDING") {"درخواست قبلی هنوز بررسی نشده است."}
        require(progress.isNotBlank() && progress.length<=1000 && reason.isNotBlank() && reason.length<=2000 && due>current.due && due>=LocalDate.now(ZoneId.of("Asia/Tehran")).toEpochDay()) {"وضعیت پیشرفت، دلیل و تاریخی پس از مهلت فعلی را وارد کنید."}
        val e=JSONObject().put("id",UUID.randomUUID().toString()).put("status","PENDING").put("progress",progress.trim()).put("reason",reason.trim()).put("due",due).put("baseDue",current.due).put("requestedBy",OrgAccess.id(context)).put("requestedAt",System.currentTimeMillis()).put("decidedBy",JSONObject.NULL).put("decidedAt",0)
        store.saveTask(current.copy(extension=e.toString()),store.snapshot().reminders.filter{it.taskId==task.id}.map{it.at})
    }
    fun decide(store: Store,context: Context,task: Resolution,approve: Boolean) {
        require(OrgAccess.role(context)=="manager") {"بررسی درخواست تمدید فقط با رئیس است."}
        val current=store.snapshot().tasks.first{it.id==task.id}
        require(current==task && current.status !in listOf(Status.DONE,Status.REVIEW)) {"مصوبه تغییر کرده است؛ دوباره بررسی کنید."}
        val e=JSONObject(current.extension ?: error("درخواستی وجود ندارد."))
        require(e.getString("status")=="PENDING" && (!approve || e.getLong("baseDue")==current.due)) {"مهلت تغییر کرده است؛ درخواست را رد کنید تا مسئول درخواست تازه ثبت کند."}
        val newDue=e.getLong("due")
        require(!approve || newDue>current.due && newDue>=LocalDate.now(ZoneId.of("Asia/Tehran")).toEpochDay()) {"مهلت پیشنهادی گذشته است."}
        e.put("status",if(approve) "APPROVED" else "REJECTED").put("decidedBy",OrgAccess.id(context)).put("decidedAt",System.currentTimeMillis())
        store.saveTask(current.copy(due=if(approve) newDue else current.due,extension=e.toString()),store.snapshot().reminders.filter{it.taskId==task.id}.map{it.at},if(approve) DueChangeKind.EXTEND else DueChangeKind.EDIT,e.getString("reason"))
    }
}

@Composable internal fun DeadlineProgressPanel(task: Resolution,busy: Boolean,refresh: ()->Unit,message: (String)->Unit) {
    val context=LocalContext.current
    val store=Store.get(context)
    val e=task.extension?.let{runCatching{JSONObject(it)}.getOrNull()}
    val pending=e?.optString("status")=="PENDING"
    var writing by rememberSaveable(task.id){mutableStateOf(false)}
    var progress by rememberSaveable(task.id){mutableStateOf("")}
    var reason by rememberSaveable(task.id){mutableStateOf("")}
    var needsExtension by rememberSaveable(task.id){mutableStateOf(false)}
    var due by rememberSaveable(task.id){mutableLongStateOf(task.due+1)}
    var picker by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    fun commit(action: ()->Unit) {runCatching(action).onSuccess{store.externalChanges.value++;SharedSync.request(context);Reminders.schedule(context);ReviewWidget.updateAll(context);refresh();writing=false;error=""}.onFailure{error=it.message.orEmpty();message(error)}}
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("پیگیری نیمهٔ مهلت",style=MaterialTheme.typography.titleSmall)
        Text("در ۵۰٪ مهلت، وضعیت کار و نیاز به تمدید از مسئول پرسیده می‌شود.",style=MaterialTheme.typography.bodySmall)
        if(e!=null) {
            Text(when(e.optString("status")){"PENDING"->"درخواست تمدید منتظر بررسی رئیس";"APPROVED"->"تمدید مهلت تأیید شد";else->"درخواست تمدید رد شد؛ مهلت قبلی برقرار است"})
            Text("وضعیت کار: ${e.optString("progress")}")
            Text("دلیل: ${e.optString("reason")}")
            Text("مهلت پیشنهادی: ${Solar.label(LocalDate.ofEpochDay(e.getLong("due")))}")
            if(pending && OrgAccess.role(context)=="manager" && task.status !in listOf(Status.DONE,Status.REVIEW)) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={commit{DeadlineProgress.decide(store,context,task,true)}},enabled=!busy){Text("تأیید تمدید")}
                OutlinedButton(onClick={commit{DeadlineProgress.decide(store,context,task,false)}},enabled=!busy){Text("رد درخواست")}
            }
        }
        if(task.assignee==OrgAccess.id(context) && task.assignee!=null && !pending && task.status !in listOf(Status.DONE,Status.REVIEW)) {
            TextButton(onClick={writing=!writing},enabled=!busy){Text("ثبت وضعیت و نیاز به تمدید")}
            if(writing) {
                OutlinedTextField(progress,{progress=it.take(1000)},label={Text("کار در چه مرحله‌ای است؟")},modifier=Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().toggleable(value=needsExtension,enabled=!busy,role=Role.Checkbox,onValueChange={needsExtension=it}),verticalAlignment=Alignment.CenterVertically) {Checkbox(needsExtension,null,enabled=!busy);Text("نیاز به تمدید مهلت دارم")}
                if(needsExtension) {
                    OutlinedTextField(reason,{reason=it.take(2000)},label={Text("دلیل نیاز به تمدید")},modifier=Modifier.fillMaxWidth())
                    OutlinedButton(onClick={picker=true}){Text("مهلت پیشنهادی: ${Solar.label(LocalDate.ofEpochDay(due))}")}
                    Text("تا تأیید رئیس، مهلت فعلی تغییر نمی‌کند.",style=MaterialTheme.typography.bodySmall)
                }
                Button(onClick={commit{if(needsExtension) DeadlineProgress.request(store,context,task,progress,reason,due) else store.addFollowUp(task.id,"نیاز به تمدید ندارم؛ ${progress.take(970)}")}},enabled=!busy && progress.isNotBlank() && (!needsExtension || reason.isNotBlank())){Text("ارسال برای رئیس")}
            }
        }
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    }}
    if(picker) SolarPicker(LocalDate.ofEpochDay(due),{picker=false}){due=it.toEpochDay();picker=false}
}
