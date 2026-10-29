package ir.peygir.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.util.concurrent.Executors

class PeygirApplication: Application() {
    override fun onCreate() {super.onCreate();Accounts.initialize(this);PushRegistration.initialize(this);ServerNetwork.observe(this)}
}
internal object PushRegistration {
    private val worker=Executors.newSingleThreadExecutor()
    private fun prefs(c: Context)=Accounts.context(c).getSharedPreferences("push_private",0)
    private fun device(c: Context)=Accounts.root(c).getSharedPreferences("firebase_device",0)
    fun status(c: Context): String = when {
        prefs(c).getString("error",null)!=null->prefs(c).getString("error","").orEmpty()
        !SharedCredentials(c).alerts()->"یادآوری این گوشی خاموش است"
        !NotificationManagerCompat.from(c).areNotificationsEnabled()->"اجازهٔ اعلان را در تنظیمات گوشی فعال کنید"
        !prefs(c).getBoolean("configured",false)->"در انتظار اتصال به سرویس"
        (device(c).getString("token",null) ?: prefs(c).getString("token",null)).isNullOrBlank()->"سرویس آماده است؛ اتصال گوشی در حال انجام است"
        prefs(c).getString("registered",null).isNullOrBlank()->"در حال ثبت این گوشی"
        Accounts.list(c).size>1 && prefs(c).getInt("account_routing",0)<1->"برای مسیریابی دقیق اعلان‌ها، سرور را به نسخهٔ ۴.۶ به‌روز کنید"
        else->"این گوشی به سرویس اعلان متصل است"
    }
    fun initialize(c: Context) {
        val value=device(c).getString("config",null) ?: prefs(c).getString("config",null)?.also{device(c).edit().putString("config",it).commit()} ?: return
        runCatching {
            val base=Accounts.root(c).applicationContext
            val p=JSONObject(value)
            if(FirebaseApp.getApps(base).isEmpty()) FirebaseApp.initializeApp(base,FirebaseOptions.Builder().setProjectId(p.getString("projectId")).setApplicationId(p.getString("applicationId")).setGcmSenderId(p.getString("senderId")).setApiKey(p.getString("apiKey")).build())
            FirebaseMessaging.getInstance().isAutoInitEnabled=true
        }
    }
    fun configure(c: Context) {val app=Accounts.context(c).applicationContext;worker.execute {
        runCatching {
            val s=SharedCredentials(app).read() ?: return@runCatching
            val response=SharedHttp(s.endpoint,s.token,s.company).post("/v2/config",JSONObject())
            prefs(app).edit().putBoolean("configured",response.optBoolean("pushConfigured")).putInt("account_routing",response.optInt("accountRouting")).apply()
            response.optJSONObject("firebase")?.let {p->
                val saved=device(app).getString("config",null)?.let{JSONObject(it)}
                if(saved!=null && listOf("projectId","senderId","applicationId").any{saved.optString(it)!=p.optString(it)}) {
                    prefs(app).edit().putString("error","برای اعلان هم‌زمان شرکت‌ها، سرورها باید از پروژهٔ Firebase مشترک پیگیر استفاده کنند.").remove("registered").apply()
                    return@let
                }
                prefs(app).edit().putString("config",p.toString()).putString("sender",p.getString("senderId")).remove("error").commit()
                device(app).edit().putString("config",p.toString()).commit();initialize(app)
                FirebaseMessaging.getInstance().token.addOnSuccessListener {token->
                    device(app).edit().putString("token",token).apply()
                    Accounts.contexts(app).forEach{prefs(it).edit().remove("registered").apply()}
                    SharedSync.request(app)
                }
            }
        }
    }}
    fun register(c: Context,s: SharedSession) {
        val p=prefs(c);if(p.getString("error",null)!=null) {configure(c);return}
        if(!p.getBoolean("configured",false)) {configure(c);return}
        val token=device(c).getString("token",null) ?: p.getString("token",null)
        if(token==null) {if(p.getString("config",null)==null) configure(c);return}
        val enabled=SharedCredentials(c).alerts() && NotificationManagerCompat.from(c).areNotificationsEnabled()
        val signature=Accounts.route(c)+"|"+s.endpoint+"|"+s.company+"|"+s.userId+"|"+s.token+"|"+token+"|"+enabled
        // Never persist the session token a second time in a plaintext marker.
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        if(p.getString("registered",null)==digest && p.getInt("account_routing",0)>=1) return
        runCatching {val response=SharedHttp(s.endpoint,s.token,s.company).post("/v2/device",JSONObject().put("token",token).put("enabled",enabled).put("accountRoute",Accounts.route(c)));p.edit().putString("registered",digest).putInt("account_routing",if(response.optString("accountRoute")==Accounts.route(c)) 1 else 0).apply()}
    }
    fun newToken(c: Context,token: String) {device(c).edit().putString("token",token).apply();Accounts.contexts(c).forEach{prefs(it).edit().remove("registered").apply()};SharedJobs.immediate(c)}
}
class PeygirMessagingService: FirebaseMessagingService() {
    override fun onNewToken(token: String) {PushRegistration.newToken(this,token)}
    override fun onDeletedMessages() {SharedJobs.immediate(this)}
    override fun onMessageReceived(message: RemoteMessage) {
        val data=message.data
        val account=Accounts.pushTarget(this,data,message.from) ?: run{SharedJobs.immediate(this);return}
        SharedSync.withDelivery(account) {
            if(SharedCredentials(account).connected() && !account.getSharedPreferences("shared_status",0).getBoolean("auth_expired",false)) receiveAccount(account,data)
        }
    }
    private fun receiveAccount(account: Context,data: Map<String,String>) {
        val company=OrgAccess.company(account)
        val received=account.getSharedPreferences("push_private",0)
        val event=data["eventId"].orEmpty()
        val eventKey=if(company.isEmpty()) event else "$company|$event"
        val recent=received.getString("recent","").orEmpty().split('\n')
        if(event.isBlank() || eventKey in recent) {SharedJobs.immediate(this);return}
        if(!CompanyPush.shouldNotify(data["kind"],UserOptions.read(account).updates)) {
            received.edit().putString("recent",(recent.filter{it.isNotBlank()}+eventKey).takeLast(200).joinToString("\n")).apply()
            SharedJobs.immediate(this);return
        }
        if(SharedCredentials(account).alerts() && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("company_updates","مصوبات و دعوت جلسات",NotificationManager.IMPORTANCE_HIGH))
            val title=Accounts.profile(account)?.let{"${it.label} - ${CompanyPush.title(data["kind"])}"} ?: CompanyPush.title(data["kind"])
            val intent=Accounts.intent(account,Intent(this,MainActivity::class.java)).putExtra("shared_record",data["recordId"]).setData(android.net.Uri.parse("peygir://company/${Accounts.id(account)}/record/${android.net.Uri.encode(data["recordId"])}")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending=PendingIntent.getActivity(this,(Accounts.id(account)+"|"+data["recordId"]).hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(this,"company_updates").setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText("برای مشاهدهٔ اطلاعات تازه، پیگیر را باز کنید.").setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).addExtras(android.os.Bundle().apply{putString(Accounts.EXTRA,Accounts.id(account))}).build()
            runCatching {manager.notify("push:${Accounts.id(account)}",eventKey.hashCode(),notification);if(Accounts.profile(account)!=null && Accounts.id(account)!=Accounts.active(account)) Accounts.markUnread(account,Accounts.id(account));received.edit().putString("recent",(recent.filter{it.isNotBlank()}+eventKey).takeLast(200).joinToString("\n")).apply()}
        }
        SharedJobs.immediate(this)
    }
}

internal object CompanyPush {
    fun shouldNotify(kind: String?,mode: String)=mode!="essential" || kind in setOf("invitation","assignment","review")
    fun title(kind: String?)=when(kind) {
        "invitation"->"به جلسه دعوت شده‌اید"
        "assignment"->"مصوبه‌ای به شما واگذار شد"
        "meeting"->"برنامهٔ جلسه به‌روز شد"
        "review"->"گزارش انجام مصوبه برای بررسی"
        else->"مصوبهٔ سازمانی به‌روز شد"
    }
}
