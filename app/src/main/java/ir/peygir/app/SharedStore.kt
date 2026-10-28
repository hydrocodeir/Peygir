package ir.peygir.app

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal fun jsonEqual(a: Any?, b: Any?): Boolean {
    if(a is JSONObject && b is JSONObject) return a.keys().asSequence().toSet()==b.keys().asSequence().toSet() && a.keys().asSequence().all{jsonEqual(a.opt(it),b.opt(it))}
    if(a is JSONArray && b is JSONArray) return a.length()==b.length() && (0 until a.length()).all{jsonEqual(a.opt(it),b.opt(it))}
    return a==b || (a is Number && b is Number && a.toString()==b.toString())
}
internal fun mergeJson(base: JSONObject, local: JSONObject, remote: JSONObject): JSONObject? {
    val out=JSONObject()
    for(key in (base.keys().asSequence()+local.keys().asSequence()+remote.keys().asSequence()).toSet()) {
        val b=base.opt(key);val l=local.opt(key);val r=remote.opt(key)
        val value=when {
            jsonEqual(l,r)->l
            jsonEqual(l,b)->r
            jsonEqual(r,b)->l
            key=="reminders" && b is JSONArray && l is JSONArray && r is JSONArray -> {
                fun values(a: JSONArray)=(0 until a.length()).map{a.getLong(it)}.toSet()
                val bs=values(b);val ls=values(l);val rs=values(r)
                JSONArray(((bs-(bs-ls)-(bs-rs))+(ls-bs)+(rs-bs)).sorted())
            }
            else->return null
        }
        out.put(key,value ?: JSONObject.NULL)
    }
    return out
}

internal class SharedStore(private val store: Store) {
    data class Pending(val kind: String,val localId: Long,val generation: Long,val operation: JSONObject)
    data class Conflict(val kind: String,val localId: Long,val title: String,val local: JSONObject?,val remote: JSONObject)
    private val tables=linkedMapOf("meeting" to "meetings","task" to "tasks","followup" to "follow_ups","history" to "due_changes")
    fun binding()=store.syncDb{db->meta(db,"binding")}
    fun receiveContacts(a: JSONArray)=Organization.receiveContacts(store,a)
    fun receiveDirectory(a: JSONArray)=Organization.receiveUsers(store,a)
    fun inventory()=store.syncDb {db->JSONArray().also {a->db.rawQuery("SELECT kind,remote_id,revision,view FROM sync_objects WHERE revision>0",null).use {c->while(c.moveToNext()) a.put(JSONObject().put("kind",c.getString(0)).put("id",c.getString(1)).put("revision",c.getLong(2)).put("view",c.getString(3)))}}}
    fun remoteLocalId(remote: String): Pair<String,Long>?=store.syncDb{db->db.rawQuery("SELECT kind,local_id FROM sync_objects WHERE remote_id=? AND deleted=0",arrayOf(remote)).use{if(it.moveToFirst()) it.getString(0) to it.getLong(1) else null}}
    fun principal()=store.syncDb{meta(it,"principal")}
    fun principal(value: String)=store.syncDb{setMeta(it,"principal",value)}
    fun clearScopedData(detachBinding: Boolean=false)=store.syncDb {db->transaction(db) {
        listOf("recordings","backlog","notification_tokens","follow_ups","due_changes","reminders","tasks","meetings","sync_dirty","sync_objects","contacts").forEach{db.delete(it,null,null)}
        if(detachBinding) db.delete("sync_meta",null,null) else db.delete("sync_meta","key IN ('directory','principal')",null)
    }}
    fun cursor()=store.syncDb{db->meta(db,"cursor").toLongOrNull() ?: 0L}
    fun cursor(value: Long)=store.syncDb{db->setMeta(db,"cursor",value.toString())}
    fun counts()=store.syncDb{db->db.rawQuery("SELECT (SELECT count(*) FROM sync_dirty),(SELECT count(*) FROM sync_objects WHERE conflict IS NOT NULL)",null).use{it.moveToFirst();it.getInt(0) to it.getInt(1)}}
    fun bind(endpoint: String,workspace: String,company: String="") = store.syncDb {db->
        val value=sharedBinding(endpoint,workspace,company);val old=meta(db,"binding")
        check(old.isEmpty() || old==value) {"اطلاعات این گوشی به فضای مشترک دیگری متصل است. ابتدا اتصال قبلی را جدا کنید."}
        if(old.isEmpty()) transaction(db) {
            setMeta(db,"binding",value);setMeta(db,"cursor","0")
            tables.forEach{(kind,table)->db.execSQL("INSERT OR IGNORE INTO sync_dirty(kind,local_id,generation,deleted) SELECT ?,id,1,0 FROM $table",arrayOf(kind))}
        }
    }
    /** Explicit detach keeps local content but discards identity/queue state, never sends remote deletions. */
    fun detach()=store.syncDb {db->transaction(db) {db.delete("sync_dirty",null,null);db.delete("sync_objects",null,null);db.delete("sync_meta",null,null)}}
    private fun identity(db: SQLiteDatabase,kind: String,id: Long): String = db.rawQuery("SELECT remote_id FROM sync_objects WHERE kind=? AND local_id=?",arrayOf(kind,id.toString())).use {c->
        if(c.moveToFirst()) c.getString(0) else UUID.randomUUID().toString().also {remote->db.execSQL("INSERT INTO sync_objects(kind,local_id,remote_id) VALUES(?,?,?)",arrayOf<Any>(kind,id,remote))}
    }
    private fun register(db: SQLiteDatabase) {tables.forEach{(kind,table)->db.rawQuery("SELECT id FROM $table",null).use {c->while(c.moveToNext()) identity(db,kind,c.getLong(0))}}}
    private fun localId(db: SQLiteDatabase,kind: String,remote: String): Long?=db.rawQuery("SELECT local_id FROM sync_objects WHERE kind=? AND remote_id=?",arrayOf(kind,remote)).use{if(it.moveToFirst()) it.getLong(0) else null}
    private fun exists(db: SQLiteDatabase,kind: String,id: Long)=db.rawQuery("SELECT 1 FROM ${tables.getValue(kind)} WHERE id=?",arrayOf(id.toString())).use{it.moveToFirst()}
    private fun payload(db: SQLiteDatabase,kind: String,id: Long): JSONObject? {
        return db.rawQuery("SELECT * FROM ${tables.getValue(kind)} WHERE id=?",arrayOf(id.toString())).use {c->
            if(!c.moveToFirst()) return@use null
            fun str(key: String)=c.getString(c.getColumnIndexOrThrow(key))
            fun num(key: String)=c.getLong(c.getColumnIndexOrThrow(key))
            val p=JSONObject()
            when(kind) {
                "meeting"->{p.put("title",str("title"));p.put("date",num("date"));p.put("notes",str("notes"));p.put("start",if(c.isNull(c.getColumnIndexOrThrow("start"))) JSONObject.NULL else num("start"));p.put("end",if(c.isNull(c.getColumnIndexOrThrow("end"))) JSONObject.NULL else num("end"));p.put("location",str("location"));p.put("attendees",JSONArray(str("attendees")));p.put("guests",JSONArray(str("guests")));p.put("repeat",str("repeat_rule"));p.put("until",if(c.isNull(c.getColumnIndexOrThrow("repeat_until"))) JSONObject.NULL else num("repeat_until"));p.put("series",str("series") ?: JSONObject.NULL)}
                "task"->{
                    listOf("title","description","owner","priority","status").forEach {p.put(it,str(it))};p.put("due",num("due"))
                    p.put("meeting",if(c.isNull(c.getColumnIndexOrThrow("meeting_id"))) JSONObject.NULL else identity(db,"meeting",num("meeting_id")))
                    val times=db.rawQuery("SELECT at FROM reminders WHERE task_id=? ORDER BY at",arrayOf(id.toString())).use{r->JSONArray().also {a->while(r.moveToNext()) a.put(r.getLong(0))}}
                    p.put("reminders",times);p.put("assignee",str("assignee") ?: JSONObject.NULL);p.put("submitted",num("submitted"));p.put("approved",num("approved"));p.put("approvedBy",str("approved_by") ?: JSONObject.NULL)
                }
                "followup"->{p.put("task",identity(db,"task",num("task_id")));p.put("text",str("text"));p.put("created",num("created"))}
                "history"->{p.put("task",identity(db,"task",num("task_id")));p.put("old",num("old_due"));p.put("new",num("new_due"));p.put("changed",num("changed_at"));p.put("kind",str("kind"));p.put("reason",str("reason"))}
            }
            p
        }
    }
    fun prepare(limit: Int=20): List<Pending> = store.syncDb {db->transaction(db) {
        register(db)
        val dirty=db.rawQuery("SELECT d.kind,d.local_id,d.generation,d.deleted,d.operation,d.payload,d.base_revision FROM sync_dirty d LEFT JOIN sync_objects o ON o.kind=d.kind AND o.local_id=d.local_id WHERE o.conflict IS NULL ORDER BY CASE d.kind WHEN 'meeting' THEN 0 WHEN 'task' THEN 1 WHEN 'followup' THEN 2 ELSE 3 END,d.local_id LIMIT ?",arrayOf(limit.toString())).use {c->buildList{while(c.moveToNext()) add(arrayOf<Any?>(c.getString(0),c.getLong(1),c.getLong(2),c.getInt(3),c.getString(4),c.getString(5),if(c.isNull(6)) null else c.getLong(6)))}}
        dirty.mapNotNull{row->
            val kind=row[0] as String;val id=row[1] as Long;val generation=row[2] as Long
            val remote=identity(db,kind,id)
            val state=db.rawQuery("SELECT revision,payload FROM sync_objects WHERE kind=? AND local_id=?",arrayOf(kind,id.toString())).use{it.moveToFirst();it.getLong(0) to it.getString(1)}
            val data=payload(db,kind,id)
            if(data==null && state.first==0L) {db.delete("sync_dirty","kind=? AND local_id=?",arrayOf(kind,id.toString()));null}
            else {
                val deleted=data==null
                val raw=data ?: JSONObject(state.second)
                if(kind=="meeting" && !raw.has("attendees")) raw.meetingOrganization(Meeting())
                if(kind=="task" && !raw.has("assignee")) raw.taskOrganization(Resolution())
                val value=row[5] as String? ?: raw.toString()
                val op=row[4] as String? ?: UUID.randomUUID().toString()
                val base=row[6] as Long? ?: state.first
                db.execSQL("UPDATE sync_dirty SET operation=?,payload=?,base_revision=? WHERE kind=? AND local_id=? AND generation=?",arrayOf<Any>(op,value,base,kind,id,generation))
                Pending(kind,id,generation,JSONObject().put("id",op).put("recordId",remote).put("kind",kind).put("baseRevision",base).put("deleted",deleted).put("payload",JSONObject(value)))
            }
        }
    }}
    fun receive(remote: JSONObject, acknowledged: Pending?=null, accepted: Boolean=false)=store.syncDb {db->transaction(db) {receive(db,remote,acknowledged,accepted)}}
    private fun receive(db: SQLiteDatabase,r: JSONObject,ack: Pending?,accepted: Boolean) {
        val kind=r.getString("kind");check(kind in tables);val remoteId=r.getString("id");UUID.fromString(remoteId)
        var id=localId(db,kind,remoteId)
        if(r.optBoolean("revoked")) {
            if(id!=null) {
                apply(db,kind,id,r)
                db.delete("sync_dirty","kind=? AND local_id=?",arrayOf(kind,id.toString()))
                db.delete("sync_objects","kind=? AND local_id=?",arrayOf(kind,id.toString()))
                // Child identities cannot keep any pending content after access to their parent is withdrawn.
                db.execSQL("DELETE FROM sync_objects WHERE kind='followup' AND local_id NOT IN(SELECT id FROM follow_ups)")
                db.execSQL("DELETE FROM sync_objects WHERE kind='history' AND local_id NOT IN(SELECT id FROM due_changes)")
            }
            return
        }
        if(id==null && r.getBoolean("deleted")) return
        if(id==null) {
            id=write(db,kind,null,r.getJSONObject("payload")) ?: return
            db.execSQL("INSERT INTO sync_objects(kind,local_id,remote_id) VALUES(?,?,?)",arrayOf(kind,id,remoteId))
        }
        val local=id
        val old=db.rawQuery("SELECT revision,payload,deleted FROM sync_objects WHERE kind=? AND local_id=?",arrayOf(kind,local.toString())).use{it.moveToFirst();Triple(it.getLong(0),JSONObject(it.getString(1)),it.getInt(2)==1)}
        val oldView=db.rawQuery("SELECT view FROM sync_objects WHERE kind=? AND local_id=?",arrayOf(kind,local.toString())).use{it.moveToFirst();it.getString(0)}
        if(r.getLong("revision")<=old.first && (!r.has("view") || r.optString("view")==oldView) && r.optString("reason")!="parent_deleted") return
        val dirty=db.rawQuery("SELECT generation,deleted FROM sync_dirty WHERE kind=? AND local_id=?",arrayOf(kind,local.toString())).use{if(it.moveToFirst()) it.getLong(0) to (it.getInt(1)==1) else null}
        fun baseline() {db.execSQL("UPDATE sync_objects SET revision=?,payload=?,deleted=?,view=?,conflict=NULL WHERE kind=? AND local_id=?",arrayOf(r.getLong("revision"),r.getJSONObject("payload").toString(),if(r.getBoolean("deleted")) 1 else 0,r.optString("view"),kind,local))}
        if(accepted && ack!=null && dirty!=null && dirty.first!=ack.generation) {baseline();return}
        val current=payload(db,kind,local)
        val dependentDirty=kind=="task" && db.rawQuery("SELECT 1 FROM sync_dirty d WHERE (d.kind='followup' AND d.local_id IN(SELECT id FROM follow_ups WHERE task_id=?)) OR (d.kind='history' AND d.local_id IN(SELECT id FROM due_changes WHERE task_id=?)) LIMIT 1",arrayOf(local.toString(),local.toString())).use{it.moveToFirst()}
        if(dirty==null && !(r.getBoolean("deleted") && dependentDirty)) {
            apply(db,kind,local,r);baseline();return
        }
        if(accepted || (current==null && r.getBoolean("deleted"))) {
            apply(db,kind,local,r);baseline();db.delete("sync_dirty","kind=? AND local_id=?",arrayOf(kind,local.toString()));return
        }
        val merged=if(current!=null && !r.getBoolean("deleted")) mergeJson(old.second,current,r.getJSONObject("payload")) else null
        if(merged==null) {
            db.execSQL("UPDATE sync_objects SET conflict=? WHERE kind=? AND local_id=?",arrayOf(r.toString(),kind,local))
            db.execSQL("INSERT OR IGNORE INTO sync_dirty(kind,local_id,generation,deleted) VALUES(?,?,1,?)",arrayOf<Any>(kind,local,if(current==null) 1 else 0))
        } else {
            write(db,kind,local,merged);baseline()
            if(jsonEqual(merged,r.getJSONObject("payload"))) db.delete("sync_dirty","kind=? AND local_id=?",arrayOf(kind,local.toString()))
            else db.execSQL("UPDATE sync_dirty SET operation=NULL,payload=NULL,base_revision=NULL WHERE kind=? AND local_id=?",arrayOf<Any>(kind,local))
        }
    }
    private fun apply(db: SQLiteDatabase,kind: String,id: Long,r: JSONObject) {
        if(r.getBoolean("deleted")) {
            if(kind=="meeting") db.execSQL("UPDATE sync_dirty SET generation=generation+1,operation=NULL,payload=NULL,base_revision=NULL WHERE kind='task' AND local_id IN(SELECT id FROM tasks WHERE meeting_id=?)",arrayOf(id))
            if(kind=="task") {
                db.execSQL("DELETE FROM sync_dirty WHERE (kind='followup' AND local_id IN(SELECT id FROM follow_ups WHERE task_id=?)) OR (kind='history' AND local_id IN(SELECT id FROM due_changes WHERE task_id=?))",arrayOf(id,id))
            }
            db.delete(tables.getValue(kind),"id=?",arrayOf(id.toString()))
        } else write(db,kind,id,r.getJSONObject("payload"))
    }
    private fun write(db: SQLiteDatabase,kind: String,id: Long?,p: JSONObject): Long? {
        val previousDue=if(kind=="task" && id!=null) db.rawQuery("SELECT due FROM tasks WHERE id=?",arrayOf(id.toString())).use{if(it.moveToFirst()) it.getLong(0) else null} else null
        val v=ContentValues()
        when(kind) {
            "meeting"->{v.put("title",p.getString("title"));v.put("date",p.getLong("date"));v.put("notes",p.getString("notes"));v.meetingOrganization(Meeting().fromOrganization(p))}
            "task"->{
                listOf("title","description","owner","priority","status").forEach{v.put(it,p.getString(it))};Priority.valueOf(p.getString("priority"));Status.valueOf(p.getString("status"));v.put("due",p.getLong("due"));v.taskOrganization(Resolution().fromOrganization(p))
                val meeting=if(p.isNull("meeting")) null else localId(db,"meeting",p.getString("meeting"))?.takeIf{exists(db,"meeting",it)}
                v.put("meeting_id",meeting)
            }
            "followup","history"->{
                val parent=localId(db,"task",p.getString("task"))?.takeIf{exists(db,"task",it)} ?: return null
                v.put("task_id",parent)
                if(kind=="followup") {v.put("text",p.getString("text"));v.put("created",p.getLong("created"))}
                else {DueChangeKind.valueOf(p.getString("kind"));v.put("old_due",p.getLong("old"));v.put("new_due",p.getLong("new"));v.put("changed_at",p.getLong("changed"));v.put("kind",p.getString("kind"));v.put("reason",p.getString("reason"))}
            }
        }
        val resolved=if(id!=null && exists(db,kind,id)) {db.update(tables.getValue(kind),v,"id=?",arrayOf(id.toString()));id} else {if(id!=null) v.put("id",id);db.insertOrThrow(tables.getValue(kind),null,v)}
        if(kind=="task") {
            if(previousDue!=p.getLong("due") || p.getString("status")==Status.DONE.name) db.delete("notification_tokens","task_id=?",arrayOf(resolved.toString()))
            val times=p.getJSONArray("reminders");val wanted=(0 until times.length()).map{times.getLong(it)}.toSet()
            db.rawQuery("SELECT id,at FROM reminders WHERE task_id=?",arrayOf(resolved.toString())).use {c->while(c.moveToNext()) if(c.getLong(1) !in wanted) db.delete("reminders","id=?",arrayOf(c.getLong(0).toString()))}
            wanted.forEach{at->db.execSQL("INSERT OR IGNORE INTO reminders(task_id,at,delivered) VALUES(?,?,0)",arrayOf(resolved,at))}
        }
        return resolved
    }
    fun conflicts(): List<Conflict> = store.syncDb {db->
        db.rawQuery("SELECT kind,local_id,conflict FROM sync_objects WHERE conflict IS NOT NULL ORDER BY CASE kind WHEN 'meeting' THEN 0 WHEN 'task' THEN 1 ELSE 2 END",null).use {c->buildList {while(c.moveToNext()) {
            val kind=c.getString(0);val id=c.getLong(1);val local=payload(db,kind,id);val remote=JSONObject(c.getString(2))
            add(Conflict(kind,id,local?.optString("title")?.takeIf{it.isNotBlank()} ?: local?.optString("text")?.takeIf{it.isNotBlank()} ?: remote.getJSONObject("payload").optString("title","نتیجهٔ پیگیری"),local,remote))
        }}}
    }
    fun resolve(kind: String,id: Long,keepLocal: Boolean)=store.syncDb {db->transaction(db) {
        val r=db.rawQuery("SELECT conflict FROM sync_objects WHERE kind=? AND local_id=?",arrayOf(kind,id.toString())).use{check(it.moveToFirst() && !it.isNull(0));JSONObject(it.getString(0))}
        if(!keepLocal) {apply(db,kind,id,r);db.delete("sync_dirty","kind=? AND local_id=?",arrayOf(kind,id.toString()))}
        else db.execSQL("UPDATE sync_dirty SET generation=generation+1,operation=NULL,payload=NULL,base_revision=NULL WHERE kind=? AND local_id=?",arrayOf<Any>(kind,id))
        db.execSQL("UPDATE sync_objects SET revision=?,payload=?,deleted=?,conflict=NULL WHERE kind=? AND local_id=?",arrayOf<Any>(r.getLong("revision"),r.getJSONObject("payload").toString(),if(r.getBoolean("deleted")) 1 else 0,kind,id))
    }}
    companion object {
        fun create(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_meta(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_objects(kind TEXT NOT NULL,local_id INTEGER NOT NULL,remote_id TEXT NOT NULL UNIQUE,revision INTEGER NOT NULL DEFAULT 0,payload TEXT NOT NULL DEFAULT '{}',deleted INTEGER NOT NULL DEFAULT 0,conflict TEXT,PRIMARY KEY(kind,local_id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_dirty(kind TEXT NOT NULL,local_id INTEGER NOT NULL,generation INTEGER NOT NULL,deleted INTEGER NOT NULL,operation TEXT,payload TEXT,base_revision INTEGER,PRIMARY KEY(kind,local_id))")
            val tables=mapOf("meetings" to "meeting","tasks" to "task","follow_ups" to "followup","due_changes" to "history")
            tables.forEach{(table,kind)->listOf("INSERT","UPDATE","DELETE").forEach{event->
                val row=if(event=="DELETE") "OLD" else "NEW";val deleted=if(event=="DELETE") 1 else 0
                db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_${table}_${event.lowercase()} AFTER $event ON $table WHEN COALESCE((SELECT value FROM sync_meta WHERE key='applying'),'0')<>'1' BEGIN ${mark(kind,"$row.id",deleted)}; END")
            }}
            listOf("INSERT","DELETE","UPDATE").forEach{event->
                val row=if(event=="DELETE") "OLD" else "NEW"
                val changed=if(event=="UPDATE") " AND (OLD.at<>NEW.at OR OLD.task_id<>NEW.task_id)" else ""
                db.execSQL("CREATE TRIGGER IF NOT EXISTS sync_reminders_${event.lowercase()} AFTER $event ON reminders WHEN COALESCE((SELECT value FROM sync_meta WHERE key='applying'),'0')<>'1' AND EXISTS(SELECT 1 FROM tasks WHERE id=$row.task_id)$changed BEGIN ${mark("task","$row.task_id",0)}; END")
            }
        }
        // Android 8 ships SQLite before UPSERT. Two statements inside the source write's transaction are atomic.
        private fun mark(kind: String,id: String,deleted: Int)="INSERT INTO sync_dirty(kind,local_id,generation,deleted) SELECT '$kind',$id,0,$deleted WHERE NOT EXISTS(SELECT 1 FROM sync_dirty WHERE kind='$kind' AND local_id=$id); UPDATE sync_dirty SET generation=generation+1,deleted=$deleted,operation=NULL,payload=NULL,base_revision=NULL WHERE kind='$kind' AND local_id=$id"
        private fun meta(db: SQLiteDatabase,key: String)=db.rawQuery("SELECT value FROM sync_meta WHERE key=?",arrayOf(key)).use{if(it.moveToFirst()) it.getString(0) else ""}
        private fun setMeta(db: SQLiteDatabase,key: String,value: String) {db.execSQL("INSERT OR REPLACE INTO sync_meta(key,value) VALUES(?,?)",arrayOf(key,value))}
        private fun <T> transaction(db: SQLiteDatabase,block: ()->T): T {
            db.beginTransaction();try {setMeta(db,"applying","1");val out=block();setMeta(db,"applying","0");db.setTransactionSuccessful();return out} finally {db.endTransaction()}
        }
    }
}
