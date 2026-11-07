package ir.peygir.app

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Store(context: Context, name: String = "peygir.db") : SQLiteOpenHelper(context.applicationContext, name, null, 10) {
    internal val profileId=Accounts.id(context)
    private val appContext=Accounts.context(context).applicationContext
    internal fun isVisible()=profileId==Accounts.active(appContext) || profileId.isEmpty() && Accounts.active(appContext) in listOf("",Accounts.LEGACY)
    internal fun publishPeople(users: List<CompanyUser>?=null,contacts: List<String>?=null)=Accounts.publishPeople(appContext,profileId,users,contacts)
    val externalChanges=kotlinx.coroutines.flow.MutableStateFlow(0L)
    private val recordingDirectory=java.io.File(context.filesDir,"recordings").canonicalFile
    private val closedDrafts=mutableSetOf<String>()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meetings(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,date INTEGER NOT NULL,notes TEXT NOT NULL)")
        db.execSQL("CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT,meeting_id INTEGER REFERENCES meetings(id) ON DELETE SET NULL,title TEXT NOT NULL,description TEXT NOT NULL,owner TEXT NOT NULL,due INTEGER NOT NULL,priority TEXT NOT NULL,status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,at INTEGER NOT NULL,delivered INTEGER NOT NULL DEFAULT 0,UNIQUE(task_id,at))")
        db.execSQL("CREATE INDEX reminders_due ON reminders(delivered,at)")
        db.execSQL("CREATE TABLE recordings(id INTEGER PRIMARY KEY AUTOINCREMENT,meeting_id INTEGER REFERENCES meetings(id) ON DELETE SET NULL,path TEXT NOT NULL,created INTEGER NOT NULL,seconds INTEGER NOT NULL,transcript TEXT NOT NULL,draft_session TEXT NOT NULL DEFAULT '')")
        createHistory(db)
        createFollowUps(db)
        createBacklog(db)
        SharedStore.create(db)
        Organization.create(db)
        MeetingAlerts.create(db)
        DeadlineProgress.create(db)
        MeetingCreator.create(db)
    }
    private fun createHistory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE due_changes(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,old_due INTEGER NOT NULL,new_due INTEGER NOT NULL,changed_at INTEGER NOT NULL,kind TEXT NOT NULL,reason TEXT NOT NULL)")
        db.execSQL("CREATE INDEX due_changes_task ON due_changes(task_id,id)")
    }
    private fun createFollowUps(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE follow_ups(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,text TEXT NOT NULL,created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX follow_ups_task ON follow_ups(task_id,created DESC,id DESC)")
        db.execSQL("CREATE TABLE notification_tokens(task_id INTEGER PRIMARY KEY REFERENCES tasks(id) ON DELETE CASCADE,token TEXT NOT NULL)")
    }
    private fun createBacklog(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE backlog(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,description TEXT NOT NULL,owner TEXT NOT NULL,created INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {if(old<2) {
        createHistory(db)
        val hasDraft=db.rawQuery("PRAGMA table_info(recordings)",null).use{c->var found=false;while(c.moveToNext()) if(c.getString(1)=="draft_session") found=true;found}
        if(!hasDraft) db.execSQL("ALTER TABLE recordings ADD COLUMN draft_session TEXT NOT NULL DEFAULT ''")
    };if(old<3) createFollowUps(db);if(old<4) createBacklog(db);if(old<5) SharedStore.create(db);if(old<7) Organization.create(db);if(old<8) MeetingAlerts.create(db);if(old<9) DeadlineProgress.create(db);if(old<10) MeetingCreator.create(db)}
    @Synchronized internal fun <T> syncDb(block: (SQLiteDatabase)->T): T = block(writableDatabase)
    @Synchronized fun snapshot(): Snapshot {
        val db = readableDatabase
        val history=db.rawQuery("SELECT * FROM due_changes ORDER BY id DESC",null).use {c->buildList{while(c.moveToNext()) add(DueChange(c.getLong(0),c.getLong(1),c.getLong(2),c.getLong(3),c.getLong(4),DueChangeKind.valueOf(c.getString(5)),c.getString(6)))}}
        val delays=history.filter{it.newDue>it.oldDue}.groupingBy{it.taskId}.eachCount()
        val alerts=MeetingAlerts.overrides(db)
        val meetings = db.rawQuery("SELECT * FROM meetings ORDER BY date DESC,COALESCE(start,1440),COALESCE(end,1440),id",null).use { c -> buildList { while(c.moveToNext()) add(Meeting(c.getLong(0),c.getString(1),c.getLong(2),c.getString(3)).withOrganization(c).copy(alertMinutes=alerts[c.getLong(0)])) } }
        val tasks = db.rawQuery("SELECT * FROM tasks ORDER BY due,id DESC",null).use { c -> buildList { while(c.moveToNext()) add(Resolution(c.getLong(0),if(c.isNull(1)) null else c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5),Priority.valueOf(c.getString(6)),Status.valueOf(c.getString(7)),delays[c.getLong(0)] ?: 0).withOrganization(c)) } }
        val reminders = db.rawQuery("SELECT * FROM reminders ORDER BY at",null).use { c -> buildList { while(c.moveToNext()) add(Reminder(c.getLong(0),c.getLong(1),c.getLong(2),c.getInt(3)==1)) } }
        val recordings = db.rawQuery("SELECT * FROM recordings ORDER BY created DESC",null).use { c -> buildList { while(c.moveToNext()) add(Recording(c.getLong(0),if(c.isNull(1)) null else c.getLong(1),c.getString(2),c.getLong(3),c.getInt(4),c.getString(5),c.getString(6))) } }
        val followUps=db.rawQuery("SELECT * FROM follow_ups ORDER BY created DESC,id DESC",null).use {c->buildList {while(c.moveToNext()) add(FollowUp(c.getLong(0),c.getLong(1),c.getString(2),c.getLong(3)))}}
        val backlog=db.rawQuery("SELECT * FROM backlog ORDER BY created DESC,id DESC",null).use {c->buildList {while(c.moveToNext()) add(BacklogItem(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getLong(4)))}}
        return Snapshot(meetings,tasks,reminders,recordings.filter{it.draftSession.isEmpty()},history,recordings.filter{it.draftSession.isNotEmpty()},followUps,backlog,Organization.contactNames(this))
    }
    @Synchronized fun saveMeeting(m: Meeting): Long {
        require(m.title.isNotBlank());MeetingSchedule.requireTime(m)
        require(m.guests.size<=200 && m.guests.all{it.isNotBlank() && it.length<=200 && Owners.key(it).isNotBlank()} && m.guests.distinctBy{Owners.key(it)}.size==m.guests.size) {"نام مخاطبان باید معتبر و بدون تکرار باشد."}
        if(m.reserved && m.id>0) require(!readableDatabase.rawQuery("SELECT 1 FROM tasks WHERE meeting_id=? LIMIT 1",arrayOf(m.id.toString())).use{it.moveToFirst()}) {"جلسهٔ دارای مصوبه را نمی‌توان رزرو کرد."}
        val v = ContentValues().apply { put("title",m.title.trim()); put("date",m.date); put("notes",m.notes);meetingOrganization(m);if(m.id==0L) put("creator",MeetingCreator.current(appContext)) }
        val db=writableDatabase;db.beginTransaction()
        try {
            val id=if(m.id==0L) db.insertOrThrow("meetings",null,v) else {
                val old=db.rawQuery("SELECT * FROM meetings WHERE id=?",arrayOf(m.id.toString())).use{c->check(c.moveToFirst());Meeting(c.getLong(0),c.getString(1),c.getLong(2),c.getString(3)).withOrganization(c)}
                v.put("creator",old.creator)
                // Local reminder changes do not dirty the shared meeting or notify colleagues.
                if(old.copy(alertMinutes=m.alertMinutes,creator=m.creator)!=m.copy(title=m.title.trim())) check(db.update("meetings",v,"id=?",arrayOf(m.id.toString()))==1)
                m.id
            }
            MeetingAlerts.save(db,id,m.alertMinutes)
            Organization.rememberContacts(db,m.guests);db.setTransactionSuccessful();return id
        } finally {db.endTransaction()}
    }
    @Synchronized fun saveMeetingSeries(m: Meeting): Long {
        val db=writableDatabase;db.beginTransaction()
        try {var first=0L;MeetingRecurrence.expand(m).forEach{val id=saveMeeting(it);if(first==0L) first=id};db.setTransactionSuccessful();return first} finally {db.endTransaction()}
    }
    @Synchronized fun deleteMeeting(id: Long) {
        require(id>0)
        check(writableDatabase.delete("meetings","id=?",arrayOf(id.toString()))==1) {"جلسه پیدا نشد."}
    }
    @Synchronized fun saveTask(t: Resolution, reminders: List<Long>, changeKind: DueChangeKind = DueChangeKind.EDIT, reason: String = "", draftSession: String? = null, consumedRecording: Long? = null): Long {
        require(t.title.isNotBlank() && t.owner.isNotBlank())
        val db = writableDatabase
        db.beginTransaction()
        var committed=false
        var audioToDelete=emptyList<String>()
        try {
            if(t.meetingId!=null) require(db.rawQuery("SELECT reserved FROM meetings WHERE id=?",arrayOf(t.meetingId.toString())).use{it.moveToFirst() && it.getInt(0)==0}) {"برای جلسهٔ رزرو نمی‌توان مصوبه ثبت کرد؛ ابتدا رزرو را بردارید."}
            val persisted=if(t.id==0L) null else db.rawQuery("SELECT due,created,extension FROM tasks WHERE id=?",arrayOf(t.id.toString())).use{c->check(c.moveToFirst()) {"مصوبه پیدا نشد."};Triple(c.getLong(0),c.getLong(1),c.getString(2))}
            if(persisted?.third?.let{org.json.JSONObject(it).optString("status")}=="PENDING" && t.due!=persisted.first) require(OrgAccess.role(appContext)=="manager" && t.extension?.let{org.json.JSONObject(it).optString("status")}=="APPROVED") {"تا بررسی درخواست تمدید توسط رئیس، مهلت فعلی باید حفظ شود."}
            val value=t.copy(created=persisted?.second?.takeIf{it>0} ?: t.created.takeIf{it>0} ?: System.currentTimeMillis())
            val oldDue=persisted?.first
            if(changeKind!=DueChangeKind.EDIT) require(oldDue!=null && t.status!=Status.DONE && t.due>oldDue && t.due>=java.time.LocalDate.now().toEpochDay()) {"مهلت جدید باید پس از موعد قبلی و از امروز به بعد باشد."}
            val v = ContentValues().apply { put("meeting_id",t.meetingId); put("title",t.title.trim()); put("description",t.description.trim()); put("owner",t.owner.trim()); put("due",t.due); put("priority",t.priority.name); put("status",t.status.name);taskOrganization(value) }
            val id = if(t.id==0L) db.insertOrThrow("tasks",null,v) else { check(db.update("tasks",v,"id=?",arrayOf(t.id.toString()))==1); t.id }
            if(oldDue!=null && (oldDue!=t.due || t.status==Status.DONE)) db.delete("notification_tokens","task_id=?",arrayOf(id.toString()))
            if(oldDue!=null && oldDue!=t.due) db.insertOrThrow("due_changes",null,ContentValues().apply{put("task_id",id);put("old_due",oldDue);put("new_due",t.due);put("changed_at",System.currentTimeMillis());put("kind",changeKind.name);put("reason",reason.trim())})
            val existing = db.rawQuery("SELECT id,at FROM reminders WHERE task_id=?",arrayOf(id.toString())).use { c -> buildList { while(c.moveToNext()) add(c.getLong(0) to c.getLong(1)) } }
            existing.filter { it.second !in reminders }.forEach { db.delete("reminders","id=?",arrayOf(it.first.toString())) }
            reminders.distinct().filter { at -> existing.none { it.second==at } }.forEach { at ->
                require(at > System.currentTimeMillis()) { "زمان یادآوری باید در آینده باشد" }
                db.insertOrThrow("reminders",null,ContentValues().apply { put("task_id",id); put("at",at) })
            }
            Organization.rememberContacts(db,if(t.assignee==null) listOf(t.owner) else emptyList())
            val clause="(draft_session<>'' AND draft_session=?) OR id=?"
            val args=arrayOf(draftSession.orEmpty(),(consumedRecording ?: -1).toString())
            audioToDelete=db.rawQuery("SELECT path FROM recordings WHERE $clause",args).use{c->buildList{while(c.moveToNext()) add(c.getString(0))}}
            db.delete("recordings",clause,args)
            db.setTransactionSuccessful()
            committed=true
            return id
        } finally {db.endTransaction();if(committed) audioToDelete.forEach{runCatching{deleteDraftFile(it)}}}
    }
    @Synchronized fun status(id: Long, status: Status) {
        writableDatabase.update("tasks",ContentValues().apply { put("status",OrgAccess.targetStatus(appContext,snapshot().tasks.first{it.id==id},status).name) },"id=?",arrayOf(id.toString()))
        if(status==Status.DONE) writableDatabase.delete("notification_tokens","task_id=?",arrayOf(id.toString()))
    }
    @Synchronized fun addFollowUp(taskId: Long, text: String): Long {
        val value=text.trim().replace(Regex("\\s+")," ")
        require(value.isNotBlank() && value.length<=1000) {"نتیجهٔ پیگیری را در حداکثر ۱۰۰۰ نویسه بنویسید."}
        return writableDatabase.insertOrThrow("follow_ups",null,ContentValues().apply {put("task_id",taskId);put("text",value);put("created",System.currentTimeMillis())})
    }
    /** Commit a token only after the notification was successfully posted. */
    @Synchronized fun activateNotification(taskId: Long, token: String, reminders: List<Long>): Boolean {
        val db=writableDatabase;db.beginTransaction()
        try {
            val active=db.rawQuery("SELECT 1 FROM tasks WHERE id=? AND status<>?",arrayOf(taskId.toString(),Status.DONE.name)).use{it.moveToFirst()}
            if(!active) return false
            db.insertWithOnConflict("notification_tokens",null,ContentValues().apply {put("task_id",taskId);put("token",token)},SQLiteDatabase.CONFLICT_REPLACE).also {check(it!=-1L)}
            reminders.forEach {db.execSQL("UPDATE reminders SET delivered=1 WHERE id=? AND task_id=?",arrayOf(it,taskId))}
            db.setTransactionSuccessful();return true
        } finally {db.endTransaction()}
    }
    /** Single-use token makes repeated, superseded and pre-restore actions harmless. */
    @Synchronized fun notificationAction(taskId: Long, token: String, snooze: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        val db=writableDatabase;db.beginTransaction();var changed=false
        try {
            val valid=db.rawQuery("SELECT 1 FROM notification_tokens n JOIN tasks t ON t.id=n.task_id WHERE n.task_id=? AND n.token=? AND t.status<>?",arrayOf(taskId.toString(),token,Status.DONE.name)).use{it.moveToFirst()}
            if(!valid) return false
            if(snooze) {
                db.execSQL("UPDATE reminders SET delivered=1 WHERE task_id=? AND at<=?",arrayOf(taskId,now))
                val at=Math.addExact(now,3_600_000L)
                db.execSQL("INSERT OR IGNORE INTO reminders(task_id,at,delivered) VALUES(?,?,0)",arrayOf(taskId,at))
                db.execSQL("UPDATE reminders SET delivered=0 WHERE task_id=? AND at=?",arrayOf(taskId,at))
            } else {
                db.execSQL("UPDATE tasks SET status=? WHERE id=?",arrayOf<Any>(OrgAccess.targetStatus(appContext,snapshot().tasks.first{it.id==taskId},Status.DONE).name,taskId))
                db.execSQL("UPDATE reminders SET delivered=1 WHERE task_id=? AND at<=?",arrayOf(taskId,now))
            }
            db.delete("notification_tokens","task_id=?",arrayOf(taskId.toString()))
            db.setTransactionSuccessful();changed=true;return true
        } finally {db.endTransaction();if(changed) externalChanges.value++}
    }
    @Synchronized fun deleteTask(id: Long) { writableDatabase.delete("tasks","id=?",arrayOf(id.toString())) }
    @Synchronized fun saveRecording(r: Recording): Long = writableDatabase.insertOrThrow("recordings",null,ContentValues().apply { put("meeting_id",r.meetingId); put("path",r.path); put("created",r.created); put("seconds",r.seconds); put("transcript",r.transcript);put("draft_session",r.draftSession) })
    @Synchronized fun stageRecording(r: Recording, session: String): Recording? {
        require(session.isNotBlank())
        if(session in closedDrafts) {deleteDraftFile(r.path);return null}
        return r.copy(draftSession=session).let{it.copy(id=saveRecording(it))}
    }
    private fun deleteDraftFile(path: String) {
        val file=java.io.File(path).canonicalFile
        if(file.parentFile==recordingDirectory) file.delete()
    }
    @Synchronized fun discardDraftRecordings(session: String? = null, except: String? = null) {
        if(session!=null) closedDrafts.add(session)
        val clause=if(session!=null) "draft_session=?" else "draft_session<>'' AND draft_session<>?"
        val args=arrayOf(session ?: except.orEmpty())
        val paths=writableDatabase.rawQuery("SELECT path,draft_session FROM recordings WHERE $clause",args).use{c->buildList{while(c.moveToNext()){add(c.getString(0));closedDrafts.add(c.getString(1))}}}
        writableDatabase.delete("recordings",clause,args)
        paths.forEach(::deleteDraftFile)
    }
    @Synchronized fun transcript(id: Long, text: String) { writableDatabase.update("recordings",ContentValues().apply { put("transcript",text) },"id=?",arrayOf(id.toString())) }
    @Synchronized fun deleteRecording(id: Long) { writableDatabase.delete("recordings","id=?",arrayOf(id.toString())) }
    @Synchronized fun delivered(ids: List<Long>) { val db=writableDatabase; db.beginTransaction(); try { ids.forEach { db.execSQL("UPDATE reminders SET delivered=1 WHERE id=?",arrayOf(it)) }; db.setTransactionSuccessful() } finally { db.endTransaction() } }
    @Synchronized fun <T> withSnapshot(block: (Snapshot)->T): T = block(snapshot())
    /** Replace validated backup data in one transaction, preserving IDs and foreign keys. */
    @Synchronized fun replaceAll(data: Snapshot, beforeCommit: ()->Unit = {}) {
        val db=writableDatabase
        check(SharedStore(this).binding().isEmpty()) {"برای بازیابی پشتیبان، ابتدا از حساب مشترک خارج شوید و اتصال فضای مشترک را از همین گوشی جدا کنید."}
        db.beginTransaction()
        try {
            listOf("backlog","notification_tokens","follow_ups","recordings","reminders","due_changes","tasks","meetings").forEach {db.delete(it,null,null)}
            data.backlog.forEach {b->db.insertOrThrow("backlog",null,ContentValues().apply {put("id",b.id);put("title",b.title);put("description",b.description);put("owner",b.owner);put("created",b.created)})}
            data.meetings.forEach {m->db.insertOrThrow("meetings",null,ContentValues().apply {put("id",m.id);put("title",m.title);put("date",m.date);put("notes",m.notes);meetingOrganization(m)});MeetingAlerts.save(db,m.id,m.alertMinutes)}
            data.tasks.forEach {t->db.insertOrThrow("tasks",null,ContentValues().apply {put("id",t.id);put("meeting_id",t.meetingId);put("title",t.title);put("description",t.description);put("owner",t.owner);put("due",t.due);put("priority",t.priority.name);put("status",t.status.name);taskOrganization(if(t.created>0) t else t.copy(created=System.currentTimeMillis()))})}
            data.reminders.forEach {r->db.insertOrThrow("reminders",null,ContentValues().apply {put("id",r.id);put("task_id",r.taskId);put("at",r.at);put("delivered",if(r.delivered) 1 else 0)})}
            data.history.forEach {h->db.insertOrThrow("due_changes",null,ContentValues().apply {put("id",h.id);put("task_id",h.taskId);put("old_due",h.oldDue);put("new_due",h.newDue);put("changed_at",h.changedAt);put("kind",h.kind.name);put("reason",h.reason)})}
            data.followUps.forEach {f->db.insertOrThrow("follow_ups",null,ContentValues().apply {put("id",f.id);put("task_id",f.taskId);put("text",f.text);put("created",f.created)})}
            data.recordings.forEach {r->db.insertOrThrow("recordings",null,ContentValues().apply {put("id",r.id);put("meeting_id",r.meetingId);put("path",r.path);put("created",r.created);put("seconds",r.seconds);put("transcript",r.transcript);put("draft_session","")})}
            db.delete("sync_objects",null,null);db.delete("sync_dirty",null,null);db.delete("contacts",null,null);Organization.rememberContacts(db,data.contacts)
            beforeCommit()
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
    }
    companion object {
        @Volatile private var instance: Store? = null
        private val companies=mutableMapOf<String,Store>()
        @Synchronized fun get(context: Context): Store {
            val id=Accounts.id(context)
            if(id.isEmpty() || id==Accounts.LEGACY) return instance ?: Store(Accounts.context(context,id)).also{instance=it}
            require(Accounts.validId(id))
            return companies.getOrPut(id){Store(Accounts.context(context,id),"peygir-$id.db")}
        }
        @Synchronized internal fun forgetCompany(context: Context,id: String) {
            companies.remove(id)?.close()
            if(id!=Accounts.LEGACY && id.isNotEmpty()) Accounts.root(context).deleteDatabase("peygir-$id.db")
        }
    }
}
