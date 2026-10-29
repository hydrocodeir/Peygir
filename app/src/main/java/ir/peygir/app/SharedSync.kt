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
    private val prefs=Accounts.context(context).getSharedPreferences("shared_private",0)
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
    val connection=ConnectionTracker()
    val revision=MutableStateFlow(0L)
    val working=MutableStateFlow(false)
    private val worker=Executors.newFixedThreadPool(3)
    private val running=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val locks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    private val deliveryLocks=java.util.concurrent.ConcurrentHashMap<String,Any>()
    private fun profileKey(c: Context)=Accounts.id(c).ifEmpty{if(Accounts.profile(c,Accounts.LEGACY)!=null) Accounts.LEGACY else ""}
    internal fun <T> withDelivery(c: Context,block: ()->T): T=synchronized(deliveryLocks.getOrPut(profileKey(c)){Any()},block)
    private fun profileLock(c: Context)=locks.getOrPut(profileKey(c)){Any()}
    fun login(endpoint: String,username: String,password: String,company: String=""): SharedSession {
        val host=validSharedEndpoint(endpoint);val code=companyCode(company);val r=SharedHttp(host,company=code).post("/v2/login",JSONObject().put("username",username.trim()).put("password",password))
        verifyLoginCompany(code,r)
        return SharedSession(host,r.getString("token"),r.getString("workspace"),r.getString("workspaceName"),r.getString("name"),r.getString("role"),r.getString("userId"),code)
    }
    private val trackers=java.util.concurrent.ConcurrentHashMap<String,ConnectionTracker>()
    private fun tracker(c: Context)=if(Accounts.id(c).isEmpty()) connection else trackers.getOrPut(Accounts.id(c)){ConnectionTracker()}
    fun showConnection(c: Context) {connection.state.value=tracker(c).state.value;working.value=connection.state.value.checking}
    private fun publish(c: Context) {if(Accounts.id(c)==Accounts.active(c)) {showConnection(c);working.value=tracker(c).state.value.checking}}
    @Synchronized fun connect(context: Context,session: SharedSession,credentials: (Context)->SharedCredentials={SharedCredentials(it)}) {
        Accounts.initialize(context){credentials(it).read()}
        val existing=Accounts.matching(context,session)
        val id=existing?.id ?: if(Accounts.list(context).isEmpty()) Accounts.LEGACY else java.util.UUID.randomUUID().toString()
        val scoped=Accounts.context(context,id)
        synchronized(profileLock(scoped)) {
        val store=Store.get(scoped);val shared=SharedStore(store);val old=shared.binding()
        val principal=shared.principal()
        check(principal.isEmpty() || principal==session.userId) {"حساب با دفتر این شرکت همخوان نیست."}
        if(session.role=="deputy" && principal.isEmpty()) check(store.snapshot().let{it.tasks.isEmpty() && it.meetings.isEmpty()}) {"ورود کاربر باید روی دفتر خالی انجام شود؛ ابتدا از اطلاعات فعلی پشتیبان بگیرید."}
        shared.bind(session.endpoint,session.workspace,session.company)
        try {credentials(scoped).save(session)} catch(e:Exception) {if(old.isEmpty()) shared.detach();throw e}
        scoped.getSharedPreferences("shared_status",0).edit().clear().apply();tracker(scoped).reset()
        shared.principal(session.userId);OrgAccess.set(scoped,session)
        Accounts.register(context,id,session);Accounts.select(context,id)
        PushRegistration.configure(scoped);SharedJobs.schedule(scoped);ServerNetwork.observe(scoped);revision.value++
        }
    }
    @Synchronized fun disconnect(context: Context,detach: Boolean=false) {
        val scoped=Accounts.context(context)
        synchronized(profileLock(scoped)) {
        withDelivery(scoped) {
        val credentials=SharedCredentials(scoped);val session=runCatching{credentials.read()}.getOrNull()
        if(Accounts.profile(scoped)!=null || session?.userId?.isNotBlank()==true) {
            Reminders.cancel(scoped);SharedStore(Store.get(scoped)).clearScopedData(detachBinding=true);Accounts.cancelNotifications(scoped);Store.get(scoped).externalChanges.value++
        }
        credentials.clear();OrgAccess.set(scoped,null);tracker(scoped).reset();scoped.getSharedPreferences("shared_status",0).edit().clear().apply()
        if(detach) SharedStore(Store.get(scoped)).detach()
        if(Accounts.profile(scoped)!=null) Accounts.remove(scoped,Accounts.id(scoped))
        if(Accounts.list(scoped).isEmpty()){SharedJobs.cancel(scoped);ServerNetwork.stop();connection.reset();Organization.members.value=emptyList();Organization.contacts.value=emptyList()}
        ReviewWidget.updateAll(scoped)
        if(session!=null) worker.execute{runCatching{SharedHttp(session.endpoint,session.token,session.company).post("/v1/logout",JSONObject())}}
        revision.value++
        }
        }
    }
    fun request(context: Context,force: Boolean=true) {
        val first=Accounts.context(context).applicationContext
        (listOf(first)+Accounts.contexts(first)).distinctBy{Accounts.id(it)}.forEach {app->
            val id=Accounts.id(app)
            if(SharedCredentials(app).connected() && !app.getSharedPreferences("shared_status",0).getBoolean("auth_expired",false) && (force || tracker(app).due()) && running.add(id)) {
                worker.execute {try {if(!perform(app) && !app.getSharedPreferences("shared_status",0).getBoolean("auth_expired",false)) SharedJobs.immediate(app)} finally {running.remove(id)}}
            }
        }
    }
    fun performAll(context: Context,force: Boolean=true): Boolean {
        val first=Accounts.context(context)
        val contexts=(listOf(first)+Accounts.contexts(context)).distinctBy{Accounts.id(it)}
        var ok=true
        contexts.forEach {c->if(SharedCredentials(c).connected() && (force || tracker(c).due())) {if(!perform(c) && !c.getSharedPreferences("shared_status",0).getBoolean("auth_expired",false)) ok=false}}
        if(!ok) SharedJobs.immediate(context)
        return ok
    }
    fun perform(source: Context,credentialFactory: (Context)->SharedCredentials={SharedCredentials(it)},transportFactory: (SharedSession)->SharedTransport={SharedHttp(it.endpoint,it.token,it.company)}): Boolean = synchronized(profileLock(Accounts.context(source))) {performLocked(source,credentialFactory,transportFactory)}
    private fun performLocked(source: Context,credentialFactory: (Context)->SharedCredentials,transportFactory: (SharedSession)->SharedTransport): Boolean {
        val context=Accounts.context(source)
        val state=tracker(context)
        val credentials=credentialFactory(context);if(!credentials.connected()) return true
        val prefs=context.getSharedPreferences("shared_status",0)
        if(prefs.getBoolean("auth_expired",false)) {if(!state.state.value.loginRequired) state.failure(true);publish(context);return true}
        state.start();publish(context)
        return try {
            var session=credentials.read() ?: return true
            val store=Store.get(context);val shared=SharedStore(store)
            val before=store.snapshot().tasks.associateBy{it.id}
            check(shared.binding()==sharedBinding(session.endpoint,session.workspace,session.company)) {"فضای مشترک با اطلاعات این گوشی همخوان نیست."}
            OrgAccess.set(context,session)
            SyncEngine.exchange(shared,transportFactory(session)) {response->
                if(response.optString("userId").isNotBlank() && (session.userId!=response.getString("userId") || session.role!=response.getString("role") || session.name!=response.optString("name",session.name) || session.workspaceName!=response.optString("workspaceName",session.workspaceName))) {
                    session=SharedSession(session.endpoint,session.token,session.workspace,response.optString("workspaceName",session.workspaceName),response.optString("name",session.name),response.getString("role"),response.getString("userId"),session.company);credentials.save(session);OrgAccess.set(context,session);Accounts.profile(context)?.let{Accounts.register(context,it.id,session)}
                }
                if(session.userId.isNotBlank()) shared.principal(session.userId)
            }
            store.publishPeople(Organization.users(store),Organization.contactNames(store))
            PushRegistration.register(context,session)
            val after=store.snapshot().tasks.associateBy{it.id}
            before.forEach{(id,old)->val current=after[id];if(current==null || current.due!=old.due || current.status==Status.DONE) Accounts.cancelTask(context,id.toInt())}
            prefs.edit().putLong("last",System.currentTimeMillis()).remove("error").remove("auth_expired").apply()
            store.externalChanges.value++;Reminders.process(context);ReviewWidget.updateAll(context);state.success();publish(context);true
        } catch(e:Exception) {
            if(e is SharedHttpError && e.code==401 && OrgAccess.id(context).isNotBlank()) {
                withDelivery(context) {
                    prefs.edit().putBoolean("auth_expired",true).commit()
                    val store=Store.get(context);SharedStore(store).clearScopedData();store.publishPeople(emptyList(),emptyList());Accounts.cancelNotifications(context);store.externalChanges.value++;ReviewWidget.updateAll(context)
                }
            }
            val message=if(e is SharedHttpError) e.message else e.message?.takeIf{it.any{c->c in 'آ'..'ی'}} ?: "اتصال برقرار نشد؛ تغییرات روی گوشی محفوظ‌اند و دوباره ارسال می‌شوند."
            prefs.edit().putString("error",message).putBoolean("auth_expired",e is SharedHttpError && e.code==401).apply();state.failure(e is SharedHttpError && e.code==401);publish(context);false
        } finally {publish(context);revision.value++}
    }
}
internal object SharedJobs {
    const val PERIODIC=7001
    fun schedule(context: Context) {
        if(Accounts.contexts(context).none{SharedCredentials(it).connected()}) return
        val service=ComponentName(context,SharedJobService::class.java)
        context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(PERIODIC,service).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    fun immediate(context: Context) {
        if(Accounts.contexts(context).none{SharedCredentials(it).connected()}) return
        val b=JobInfo.Builder(7002,ComponentName(context,SharedJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setBackoffCriteria(30000,JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        if(android.os.Build.VERSION.SDK_INT>=31) b.setExpedited(true) else b.setMinimumLatency(0)
        context.getSystemService(JobScheduler::class.java).schedule(b.build())
    }
    fun cancel(context: Context) {context.getSystemService(JobScheduler::class.java).cancel(PERIODIC);context.getSystemService(JobScheduler::class.java).cancel(7002)}
}
class SharedJobService: JobService() {
    private val worker=Executors.newSingleThreadExecutor()
    private var active: java.util.concurrent.Future<*>?=null
    private var stopped=AtomicBoolean(false)
    override fun onStartJob(params: JobParameters): Boolean {val flag=AtomicBoolean(false);stopped=flag;active=worker.submit{val ok=SharedSync.performAll(applicationContext);if(!flag.get()) jobFinished(params,!ok)};return true}
    override fun onStopJob(params: JobParameters): Boolean {stopped.set(true);active?.cancel(true);return true}
    override fun onDestroy() {worker.shutdown();super.onDestroy()}
}
