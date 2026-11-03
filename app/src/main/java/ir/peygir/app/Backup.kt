package ir.peygir.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.security.MessageDigest
import java.time.LocalTime
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable, offline backup. Drafts, API credentials, caches and model files are excluded. */
object Backup {
    private const val MAX_BYTES=512L*1024*1024
    private const val MAX_JSON=16L*1024*1024
    data class Preferences(val theme: String, val dynamic: Boolean, val daily: Boolean, val times: String,val dictation: String="hold",val options: UserOptions=UserOptions())
    class Prepared internal constructor(val data: Snapshot, val settings: Preferences, internal val directory: File): Closeable {
        override fun close() {directory.deleteRecursively()}
    }
    private fun settings(context: Context): Preferences {
        val p=context.getSharedPreferences("settings",0)
        return Preferences(p.getString("theme","system") ?: "system",p.getBoolean("dynamic_colors",false),Reminders.dailyEnabled(context),Reminders.dailyTimes(context).joinToString(","),DictationMode.read(context).key,UserOptions.read(context))
    }
    private fun applySettings(context: Context, value: Preferences) {
        check(value.options.save(context.getSharedPreferences("settings",0).edit()).putString("theme",value.theme).putBoolean("dynamic_colors",value.dynamic).putBoolean("daily",value.daily).putString("times",value.times).putString(DictationMode.PREFERENCE,value.dictation).remove("last_review").commit()) {"ذخیرهٔ تنظیمات بازیابی ناموفق بود."}
    }
    private fun obj(vararg fields: Pair<String,Any?>)=JSONObject().apply {fields.forEach{put(it.first,it.second ?: JSONObject.NULL)}}
    private fun <T> array(values: List<T>, convert: (T)->JSONObject)=JSONArray().apply {values.forEach {put(convert(it))}}
    private fun sha(file: File): String {
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val b=ByteArray(8192);while(true) {val n=input.read(b);if(n<0) break;digest.update(b,0,n)}}
        return digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)}
    }
    fun write(context: Context, output: OutputStream, store: Store = Store.get(context)) = store.withSnapshot {data->
        require(data.recordings.size<=10000 && listOf(data.meetings.size,data.tasks.size,data.reminders.size,data.history.size,data.followUps.size,data.backlog.size).all{it<=100000}) {"تعداد داده‌های پشتیبان بیش از حد مجاز است."}
        val directory=File(context.filesDir,"recordings").canonicalFile
        var size=0L
        val files=data.recordings.associate {r->
            val file=File(r.path).canonicalFile
            require(file.parentFile==directory && file.isFile) {"یکی از صوت‌های آرشیوی در دسترس نیست؛ پیش از پشتیبان‌گیری آن را بررسی کنید."}
            size+=file.length();require(size<=MAX_BYTES) {"حجم پشتیبان بیش از ۵۱۲ مگابایت است."}
            r.id to file
        }
        val p=settings(context)
        val json=obj("format" to "peygir-backup","version" to 6,"createdAt" to System.currentTimeMillis(),
            "settings" to obj("theme" to p.theme,"dynamic" to p.dynamic,"daily" to p.daily,"times" to p.times,"dictation" to p.dictation,"options" to p.options.json()),
            "contacts" to JSONArray(data.contacts),
            "meetings" to array(data.meetings) {obj("id" to it.id,"title" to it.title,"date" to it.date,"notes" to it.notes,"localAlert" to it.alertMinutes).meetingOrganization(it)},
            "tasks" to array(data.tasks) {obj("id" to it.id,"meeting" to it.meetingId,"title" to it.title,"description" to it.description,"owner" to it.owner,"due" to it.due,"priority" to it.priority.name,"status" to it.status.name).taskOrganization(it)},
            "reminders" to array(data.reminders) {obj("id" to it.id,"task" to it.taskId,"at" to it.at,"delivered" to it.delivered)},
            "history" to array(data.history) {obj("id" to it.id,"task" to it.taskId,"old" to it.oldDue,"new" to it.newDue,"changed" to it.changedAt,"kind" to it.kind.name,"reason" to it.reason)},
            "followUps" to array(data.followUps) {obj("id" to it.id,"task" to it.taskId,"text" to it.text,"created" to it.created)},
            "backlog" to array(data.backlog) {obj("id" to it.id,"title" to it.title,"description" to it.description,"owner" to it.owner,"created" to it.created)},
            "recordings" to array(data.recordings) {obj("id" to it.id,"meeting" to it.meetingId,"created" to it.created,"seconds" to it.seconds,"transcript" to it.transcript,"sha256" to sha(files.getValue(it.id)))})
        val bytes=json.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=MAX_JSON && bytes.size+size<=MAX_BYTES) {"حجم پشتیبان بیش از حد مجاز است."}
        ZipOutputStream(BufferedOutputStream(output)).use {zip->
            zip.putNextEntry(ZipEntry("peygir.json"));zip.write(bytes);zip.closeEntry()
            files.forEach {(id,file)->zip.putNextEntry(ZipEntry("audio/$id.wav"));file.inputStream().use{it.copyTo(zip)};zip.closeEntry()}
        }
    }
    fun read(context: Context, input: InputStream): Prepared {
        val directory=File(context.cacheDir,"restore-${UUID.randomUUID()}").apply {check(mkdirs())}
        try {
            val names=mutableSetOf<String>();var total=0L
            ZipInputStream(BufferedInputStream(input)).use {zip->
                while(true) {
                    val entry=zip.nextEntry ?: break
                    val name=entry.name
                    require(!entry.isDirectory && (name=="peygir.json" || Regex("audio/[1-9][0-9]{0,17}\\.wav").matches(name)) && names.add(name) && names.size<=10001) {"ساختار فایل پشتیبان معتبر نیست."}
                    val file=File(directory,if(name=="peygir.json") name else name.substringAfter('/'))
                    file.outputStream().use {out->
                        val buffer=ByteArray(8192);var length=0L
                        while(true) {val n=zip.read(buffer);if(n<0) break;length+=n;total+=n
                            require(total<=MAX_BYTES && (name!="peygir.json" || length<=MAX_JSON)) {"حجم فایل پشتیبان بیش از حد مجاز است."}
                            out.write(buffer,0,n)
                        }
                    }
                    zip.closeEntry()
                }
            }
            require("peygir.json" in names) {"این فایل پشتیبان پیگیر نیست."}
            val root=JSONObject(File(directory,"peygir.json").readText(Charsets.UTF_8))
            require(root.getString("format")=="peygir-backup" && root.getInt("version") in 1..6) {"نسخهٔ پشتیبان پشتیبانی نمی‌شود."}
            fun <T> list(key: String, parse: (JSONObject)->T): List<T> {
                val a=root.getJSONArray(key);require(a.length()<=100000) {"تعداد داده‌های پشتیبان بیش از حد مجاز است."}
                return (0 until a.length()).map {parse(a.getJSONObject(it))}
            }
            fun JSONObject.ref(key: String): Long?=if(isNull(key)) null else getLong(key)
            fun JSONObject.text(key: String)=getString(key).also {require(it.length<=200000)}
            val contacts=root.optJSONArray("contacts")?.strings().orEmpty();require(contacts.size<=100000 && contacts.all{it.isNotBlank() && it.length<=200})
            val meetings=list("meetings") {Meeting(it.getLong("id"),it.text("title"),it.getLong("date"),it.text("notes")).fromOrganization(it).copy(alertMinutes=if(it.isNull("localAlert")) null else it.getInt("localAlert"))}
            require(meetings.all{it.alertMinutes==null || it.alertMinutes in UserOptions.leads}) {"زمان یادآوری جلسه در پشتیبان معتبر نیست."}
            val tasks=list("tasks") {Resolution(it.getLong("id"),it.ref("meeting"),it.text("title"),it.text("description"),it.text("owner"),it.getLong("due"),Priority.valueOf(it.getString("priority")),Status.valueOf(it.getString("status"))).fromOrganization(it)}
            val reminders=list("reminders") {Reminder(it.getLong("id"),it.getLong("task"),it.getLong("at"),it.getBoolean("delivered"))}
            val history=list("history") {DueChange(it.getLong("id"),it.getLong("task"),it.getLong("old"),it.getLong("new"),it.getLong("changed"),DueChangeKind.valueOf(it.getString("kind")),it.text("reason"))}
            val followUps=if(root.getInt("version")==1) emptyList() else list("followUps") {FollowUp(it.getLong("id"),it.getLong("task"),it.text("text"),it.getLong("created"))}
            val backlog=if(root.getInt("version")<3) emptyList() else list("backlog") {BacklogItem(it.getLong("id"),it.text("title"),it.text("description"),it.text("owner"),it.getLong("created"))}
            val recordings=list("recordings") {
                val id=it.getLong("id");val file=File(directory,"$id.wav")
                require("audio/$id.wav" in names && file.isFile && sha(file)==it.getString("sha256")) {"فایل صوتی پشتیبان ناقص یا خراب است."}
                Recording(id,it.ref("meeting"),file.path,it.getLong("created"),it.getInt("seconds"),it.text("transcript"))
            }
            require(names==recordings.map {"audio/${it.id}.wav"}.toSet()+"peygir.json") {"پشتیبان دارای فایل اضافی است."}
            fun ids(values: List<Long>): Set<Long> {require(values.all {it>0 && it<=Int.MAX_VALUE} && values.distinct().size==values.size);return values.toSet()}
            val meetingIds=ids(meetings.map{it.id});val taskIds=ids(tasks.map{it.id})
            ids(reminders.map{it.id});ids(history.map{it.id});ids(recordings.map{it.id});ids(followUps.map{it.id})
            ids(backlog.map{it.id});require(backlog.all {it.title.isNotBlank() && it.created>0})
            fun day(d: Long)=d in WidgetDays.min..WidgetDays.max
            require(meetings.all {it.title.isNotBlank() && day(it.date) && runCatching{MeetingRecurrence.expand(it)}.isSuccess})
            fun accountRef(value: String?)=value==null || Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(value)
            require(meetings.all {it.guests.size<=200 && it.guests.all{n->n.isNotBlank() && n.length<=200} && it.guests.distinctBy{n->Owners.key(n)}.size==it.guests.size && it.attendees.size<=200 && it.attendees.distinct().size==it.attendees.size && it.attendees.all {id->accountRef(id)} && accountRef(it.series) && (it.repeat=="NONE" || (it.series!=null && it.until!=null && it.until in it.date..it.date+366))})
            require(tasks.all {accountRef(it.assignee) && accountRef(it.approvedBy) && it.submitted>=0 && it.approved>=0})
            require(tasks.none{t->meetings.any{it.id==t.meetingId && it.reserved}}) {"پشتیبان شامل مصوبه برای جلسهٔ رزرو است."}
            require(tasks.all{it.created>=0 && (it.extension==null || runCatching {
                val e=JSONObject(it.extension)
                e.getString("status") in listOf("PENDING","APPROVED","REJECTED") && e.getString("progress").isNotBlank() && e.getString("progress").length<=1000 && e.getString("reason").isNotBlank() && e.getString("reason").length<=2000 && day(e.getLong("due")) && day(e.getLong("baseDue")) && e.getLong("due")>e.getLong("baseDue") && accountRef(e.getString("requestedBy")) && e.getLong("requestedAt")>0
            }.getOrDefault(false))}) {"اطلاعات درخواست تمدید در پشتیبان معتبر نیست."}
            require(tasks.all {it.title.isNotBlank() && it.owner.isNotBlank() && day(it.due) && (it.meetingId==null || it.meetingId in meetingIds)})
            require(reminders.all {it.taskId in taskIds && it.at>0} && reminders.map{it.taskId to it.at}.distinct().size==reminders.size)
            require(history.all {it.taskId in taskIds && day(it.oldDue) && day(it.newDue) && it.changedAt>0})
            require(followUps.all {it.taskId in taskIds && it.text.isNotBlank() && it.text.length<=1000 && it.created>0})
            require(recordings.all {(it.meetingId==null || it.meetingId in meetingIds) && it.created>0 && it.seconds>=0})
            val p=root.getJSONObject("settings")
            require(!p.has("options") || p.isNull("options") || p.get("options") is JSONObject) {"قالب تنظیمات راحتی استفاده معتبر نیست."}
            val settings=Preferences(p.getString("theme"),p.getBoolean("dynamic"),p.getBoolean("daily"),p.getString("times"),p.optString("dictation","hold"),UserOptions.from(p.optJSONObject("options")))
            require(DictationMode.entries.any{it.key==settings.dictation}) {"روش ورود صوتی پشتیبان معتبر نیست."}
            require(settings.theme in setOf("system","light","dark") && settings.times.length<10000)
            require(settings.times.isEmpty() || settings.times.split(',').all {runCatching {LocalTime.parse(it)}.isSuccess})
            return Prepared(Snapshot(meetings,tasks,reminders,recordings,history,followUps=followUps,backlog=backlog,contacts=contacts),settings,directory)
        } catch(e:Exception) {directory.deleteRecursively();throw e}
    }
    /** Stage new files first; failed database/settings writes retain the current data and audio. */
    fun restore(context: Context, prepared: Prepared, store: Store = Store.get(context)) = store.withSnapshot {old->
        val directory=File(context.filesDir,"recordings").apply {check(isDirectory || mkdirs())}.canonicalFile
        val copied=mutableListOf<File>();val previous=settings(context)
        val prefs=context.getSharedPreferences("settings",0)
        val lastReview=if(prefs.contains("last_review")) prefs.getLong("last_review",0) else null
        var settingsTouched=false
        try {
            val recordings=prepared.data.recordings.map {r->
                val file=File(directory,"restored-${UUID.randomUUID()}.wav");copied+=file
                File(r.path).copyTo(file)
                r.copy(path=file.path)
            }
            store.replaceAll(prepared.data.copy(recordings=recordings)) {settingsTouched=true;applySettings(context,prepared.settings)}
        } catch(e:Exception) {
            copied.forEach{it.delete()}
            if(settingsTouched) runCatching {
                applySettings(context,previous)
                val edit=prefs.edit();if(lastReview==null) edit.remove("last_review") else edit.putLong("last_review",lastReview);edit.commit()
            }
            throw e
        }
        (old.recordings+old.draftRecordings).forEach {r->runCatching {val f=File(r.path).canonicalFile;if(f.parentFile==directory) f.delete()}}
        context.getSharedPreferences("drafts",0).edit().clear().apply()
    }
}
