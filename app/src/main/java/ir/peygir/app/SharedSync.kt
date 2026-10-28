package ir.peygir.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class SharedSession(val endpoint: String,val token: String,val workspace: String,val workspaceName: String,val name: String,val role: String,val userId: String="",val company: String="") {
    override fun toString()="SharedSession(name=$name, role=$role, token=[hidden])"
    fun json()=JSONObject().put("endpoint",endpoint).put("token",token).put("workspace",workspace).put("workspaceName",workspaceName).put("name",name).put("role",role).put("userId",userId).put("company",company)
    companion object {fun from(p: JSONObject)=SharedSession(p.getString("endpoint"),p.getString("token"),p.getString("workspace"),p.getString("workspaceName"),p.getString("name"),p.getString("role"),p.optString("userId"),p.optString("company"))}
}
internal fun companyCode(value: String)=value.trim().lowercase(java.util.Locale.ROOT).also {require(it.isEmpty() || it.matches(Regex("[a-z0-9][a-z0-9-]{2,31}"))) {"کد شرکت باید ۳ تا ۳۲ نویسهٔ انگلیسی، عدد یا خط تیره باشد."}}
internal fun verifyLoginCompany(company: String,response: JSONObject) {check(response.optString("company")==company) {"پاسخ ورود مربوط به شرکت انتخاب‌شده نیست؛ نشانی سرویس و کد شرکت را بررسی کنید."}}
internal fun sharedBinding(endpoint: String,workspace: String,company: String="")=if(company.isEmpty()) "$endpoint|$workspace" else "$endpoint|company:$company|$workspace"
internal class SharedCredentials(context: Context,private val key: ()->SecretKey = ::sharedKey) {
    private val prefs=context.applicationContext.getSharedPreferences("shared_private",0)
    fun connected()=prefs.contains("session")
    fun alerts()=!connected() || prefs.getBoolean("alerts",true)
    fun alerts(value: Boolean) {prefs.edit().putBoolean("alerts",value).apply()}
    fun read(): SharedSession? {
        val stored=prefs.getString("session",null) ?: return null
        val parts=stored.split(':');check(parts.size==2)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));cipher.updateAAD("peygir-shared-v1".toByteArray())
        return SharedSession.from(JSONObject(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)).toString(Charsets.UTF_8)))
    }
    fun save(session: SharedSession) {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD("peygir-shared-v1".toByteArray())
        val stored=Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(session.json().toString().toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
        val edit=prefs.edit().putString("session",stored)
        if(!prefs.contains("alerts")) edit.putBoolean("alerts",session.role!="assistant")
        check(edit.commit()) {"ذخیرهٔ ورود ناموفق بود."}
    }
    fun clear() {check(prefs.edit().remove("session").commit())}
}
@Synchronized private fun sharedKey(): SecretKey {
    val alias="peygir-shared-v1";val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    (store.getKey(alias,null) as? SecretKey)?.let{return it}
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
}
internal class SharedHttpError(val code: Int,message: String): IOException(message)
internal fun validSharedEndpoint(value: String): String {
    val u=URL(value.trim().trimEnd('/'))
    require(u.protocol=="https" && u.host.isNotBlank() && u.userInfo==null && u.query==null && u.ref==null && (u.path.isBlank() || u.path=="/")) {"نشانی سرویس باید HTTPS و بدون مسیر اضافی باشد."}
    return u.toString().trimEnd('/')
}
internal fun interface SharedTransport {fun post(path: String,body: JSONObject): JSONObject}
internal class SharedHttp(private val endpoint: String,private val token: String="",private val company: String="",private val open: (URL)->HttpURLConnection = {it.openConnection() as HttpURLConnection}): SharedTransport {
    override fun post(path: String,body: JSONObject): JSONObject {
        val connection=open(URL(validSharedEndpoint(endpoint)+path))
        try {
            connection.requestMethod="POST";connection.connectTimeout=15000;connection.readTimeout=20000;connection.instanceFollowRedirects=false;connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setRequestProperty("Accept","application/json")
            if(company.isNotEmpty()) connection.setRequestProperty("X-Peygir-Company",companyCode(company))
            if(token.isNotBlank()) connection.setRequestProperty("Authorization","Bearer $token")
            val bytes=body.toString().toByteArray(Charsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.size);connection.outputStream.use{it.write(bytes)}
            val code=connection.responseCode;val input=if(code in 200..299) connection.inputStream else connection.errorStream
            val response=input?.use {stream->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true) {val n=stream.read(buffer);if(n<0) break;check(out.size()+n<=16*1024*1024);out.write(buffer,0,n)};out.toString("UTF-8")}.orEmpty()
            val json=runCatching{JSONObject(response)}.getOrDefault(JSONObject())
            if(code !in 200..299) throw SharedHttpError(code,json.optString("error").takeIf{it.any{c->c in 'آ'..'ی'}} ?: "ارتباط با فضای مشترک ناموفق بود.")
            return json
        } finally {connection.disconnect()}
    }
}
internal object SyncEngine {
    fun exchange(shared: SharedStore,transport: SharedTransport,responseReceived: (JSONObject)->Unit = {}): Int {
        var count=0
        repeat(50) {
            if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Sync interrupted")
            val pending=shared.prepare();val response=transport.post("/v2/sync",JSONObject().put("protocol",2).put("inventory",shared.inventory()).put("cursor",shared.cursor()).put("operations",JSONArray(pending.map{it.operation})))
            responseReceived(response)
            val results=response.getJSONArray("results")
            check(results.length()==pending.size) {"پاسخ همگام‌سازی ناقص است."}
            for(i in 0 until results.length()) {
                val result=results.getJSONObject(i);val item=pending.find{it.operation.getString("id")==result.getString("opId")} ?: error("پاسخ همگام‌سازی معتبر نیست.")
                check(!result.isNull("record")) {"ارتباط یک مورد با فضای مشترک از دست رفته است؛ پشتیبانی را بررسی کنید."}
                shared.receive(result.getJSONObject("record"),item,result.getBoolean("ok"));count++
            }
            response.optJSONArray("users")?.let {shared.receiveDirectory(it)}
            response.optJSONArray("contacts")?.let {shared.receiveContacts(it)}
            val changes=response.getJSONArray("changes")
            for(i in 0 until changes.length()) {shared.receive(changes.getJSONObject(i));count++}
            val cursor=response.getLong("cursor");if(response.optInt("protocol")==2) shared.cursor(0) else {check(cursor>=shared.cursor());shared.cursor(cursor)}
            if(!response.getBoolean("hasMore") && shared.prepare(1).isEmpty()) return count
        }
        return count
    }
}
internal object SharedSync {
    val revision=MutableStateFlow(0L)
    val working=MutableStateFlow(false)
    private val worker=Executors.newSingleThreadExecutor()
    private val running=AtomicBoolean(false)
    fun login(endpoint: String,username: String,password: String,company: String=""): SharedSession {
        val host=validSharedEndpoint(endpoint);val code=companyCode(company);val r=SharedHttp(host,company=code).post("/v2/login",JSONObject().put("username",username.trim()).put("password",password))
        verifyLoginCompany(code,r)
        return SharedSession(host,r.getString("token"),r.getString("workspace"),r.getString("workspaceName"),r.getString("name"),r.getString("role"),r.getString("userId"),code)
    }
    @Synchronized fun connect(context: Context,session: SharedSession) {
        val shared=SharedStore(Store.get(context));val old=shared.binding()
        val principal=shared.principal()
        check(principal.isEmpty() || principal==session.userId) {"ابتدا از حساب قبلی خارج شوید تا اطلاعات آن از این گوشی پاک شود."}
        if(session.role=="deputy" && principal.isEmpty()) check(Store.get(context).snapshot().let{it.tasks.isEmpty() && it.meetings.isEmpty()}) {"ورود معاون باید روی دفتر خالی انجام شود؛ ابتدا از اطلاعات فعلی پشتیبان بگیرید."}
        shared.bind(session.endpoint,session.workspace,session.company)
        try {SharedCredentials(context).save(session)} catch(e:Exception) {if(old.isEmpty()) shared.detach();throw e}
        shared.principal(session.userId);OrgAccess.set(context,session);PushRegistration.configure(context);SharedJobs.schedule(context);revision.value++
    }
    @Synchronized fun disconnect(context: Context,detach: Boolean=false) {
        val credentials=SharedCredentials(context);val session=runCatching{credentials.read()}.getOrNull()
        if(session?.userId?.isNotBlank()==true) {
            SharedStore(Store.get(context)).clearScopedData(detachBinding=true);Organization.members.value=emptyList();Organization.contacts.value=emptyList()
            context.getSystemService(android.app.NotificationManager::class.java).cancelAll();Store.get(context).externalChanges.value++;ReviewWidget.updateAll(context)
        }
        credentials.clear();OrgAccess.set(context,null);SharedJobs.cancel(context)
        if(detach) SharedStore(Store.get(context)).detach()
        if(session!=null) worker.execute{runCatching{SharedHttp(session.endpoint,session.token,session.company).post("/v1/logout",JSONObject())}}
        revision.value++
    }
    fun request(context: Context) {
        val app=context.applicationContext
        if(!SharedCredentials(app).connected() || !running.compareAndSet(false,true)) return
        worker.execute {try {perform(app)} finally {running.set(false)}}
    }
    @Synchronized fun perform(context: Context): Boolean {
        val credentials=SharedCredentials(context);if(!credentials.connected()) return true
        val prefs=context.getSharedPreferences("shared_status",0);working.value=true
        return try {
            var session=credentials.read() ?: return true
            val store=Store.get(context);val shared=SharedStore(store)
            val before=store.snapshot().tasks.associateBy{it.id}
            check(shared.binding()==sharedBinding(session.endpoint,session.workspace,session.company)) {"فضای مشترک با اطلاعات این گوشی همخوان نیست."}
            OrgAccess.set(context,session)
            SyncEngine.exchange(shared,SharedHttp(session.endpoint,session.token,session.company)) {response->
                if(response.optString("userId").isNotBlank() && (session.userId!=response.getString("userId") || session.role!=response.getString("role") || session.name!=response.optString("name",session.name) || session.workspaceName!=response.optString("workspaceName",session.workspaceName))) {
                    session=SharedSession(session.endpoint,session.token,session.workspace,response.optString("workspaceName",session.workspaceName),response.optString("name",session.name),response.getString("role"),response.getString("userId"),session.company);credentials.save(session);OrgAccess.set(context,session)
                }
                if(session.userId.isNotBlank()) shared.principal(session.userId)
            }
            Organization.members.value=Organization.users(store);Organization.contacts.value=Organization.contactNames(store)
            PushRegistration.register(context,session)
            val after=store.snapshot().tasks.associateBy{it.id}
            before.forEach{(id,old)->val current=after[id];if(current==null || current.due!=old.due || current.status==Status.DONE) context.getSystemService(android.app.NotificationManager::class.java).cancel(id.toInt())}
            prefs.edit().putLong("last",System.currentTimeMillis()).remove("error").remove("auth_expired").apply()
            store.externalChanges.value++;Reminders.process(context);ReviewWidget.updateAll(context);true
        } catch(e:Exception) {
            if(e is SharedHttpError && e.code==401 && OrgAccess.id(context).isNotBlank()) {
                SharedStore(Store.get(context)).clearScopedData();Organization.members.value=emptyList();Organization.contacts.value=emptyList();context.getSystemService(android.app.NotificationManager::class.java).cancelAll();Store.get(context).externalChanges.value++;ReviewWidget.updateAll(context)
            }
            val message=if(e is SharedHttpError) e.message else e.message?.takeIf{it.any{c->c in 'آ'..'ی'}} ?: "اتصال برقرار نشد؛ تغییرات روی گوشی محفوظ‌اند و دوباره ارسال می‌شوند."
            prefs.edit().putString("error",message).putBoolean("auth_expired",e is SharedHttpError && e.code==401).apply();false
        } finally {working.value=false;revision.value++}
    }
}
internal object SharedJobs {
    const val PERIODIC=7001
    fun schedule(context: Context) {
        if(!SharedCredentials(context).connected()) return
        val service=ComponentName(context,SharedJobService::class.java)
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(PERIODIC,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    fun immediate(context: Context) {
        if(!SharedCredentials(context).connected()) return
        val b=JobInfo.Builder(7002,ComponentName(context,SharedJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
        if(android.os.Build.VERSION.SDK_INT>=31) b.setExpedited(true) else b.setMinimumLatency(0)
        context.getSystemService(JobScheduler::class.java).schedule(b.build())
    }
    fun cancel(context: Context) {context.getSystemService(JobScheduler::class.java).cancel(PERIODIC);context.getSystemService(JobScheduler::class.java).cancel(7002)}
}
class SharedJobService: JobService() {
    private val worker=Executors.newSingleThreadExecutor()
    private var active: java.util.concurrent.Future<*>?=null
    private var stopped=AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {val flag=AtomicBoolean(false);stopped=flag;active=worker.submit{val ok=SharedSync.perform(applicationContext);if(!flag.get()) jobFinished(params,!ok)};return true}
    override fun onStopJob(params: JobParameters): Boolean {stopped.set(true);active?.cancel(true);return true}
    override fun onDestroy() {worker.shutdown();super.onDestroy()}
}
