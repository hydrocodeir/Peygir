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
    override fun onCreate() {super.onCreate();PushRegistration.initialize(this)}
}
internal object PushRegistration {
    private val worker=Executors.newSingleThreadExecutor()
    private fun prefs(c: Context)=c.getSharedPreferences("push_private",0)
    fun status(c: Context): String = when {
        !SharedCredentials(c).alerts()->"یادآوری این گوشی خاموش است"
        !NotificationManagerCompat.from(c).areNotificationsEnabled()->"اجازهٔ اعلان را در تنظیمات گوشی فعال کنید"
        !prefs(c).getBoolean("configured",false)->"در انتظار اتصال به سرویس"
        prefs(c).getString("token",null).isNullOrBlank()->"سرویس آماده است؛ اتصال گوشی در حال انجام است"
        prefs(c).getString("registered",null).isNullOrBlank()->"در حال ثبت این گوشی"
        else->"این گوشی به سرویس اعلان متصل است"
    }
    fun initialize(c: Context) {
        val value=prefs(c).getString("config",null) ?: return
        runCatching {
            val p=JSONObject(value)
            if(FirebaseApp.getApps(c).isEmpty()) FirebaseApp.initializeApp(c,FirebaseOptions.Builder().setProjectId(p.getString("projectId")).setApplicationId(p.getString("applicationId")).setGcmSenderId(p.getString("senderId")).setApiKey(p.getString("apiKey")).build())
            FirebaseMessaging.getInstance().isAutoInitEnabled=true
        }
    }
    fun configure(c: Context) {val app=c.applicationContext;worker.execute {
        runCatching {
            val s=SharedCredentials(app).read() ?: return@runCatching
            val response=SharedHttp(s.endpoint,s.token).post("/v2/config",JSONObject())
            prefs(app).edit().putBoolean("configured",response.optBoolean("pushConfigured")).apply()
            response.optJSONObject("firebase")?.let {p->prefs(app).edit().putString("config",p.toString()).commit();initialize(app)
                FirebaseMessaging.getInstance().token.addOnSuccessListener {token->prefs(app).edit().putString("token",token).remove("registered").apply();SharedSync.request(app)}
            }
        }
    }}
    fun register(c: Context,s: SharedSession) {
        val p=prefs(c);val token=p.getString("token",null)
        if(token==null) {if(p.getString("config",null)==null) configure(c);return}
        val enabled=SharedCredentials(c).alerts() && NotificationManagerCompat.from(c).areNotificationsEnabled()
        val signature=s.userId+"|"+s.token+"|"+token+"|"+enabled
        // Never persist the session token a second time in a plaintext marker.
        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
        if(p.getString("registered",null)==digest) return
        runCatching {SharedHttp(s.endpoint,s.token).post("/v2/device",JSONObject().put("token",token).put("enabled",enabled));p.edit().putString("registered",digest).apply()}
    }
    fun newToken(c: Context,token: String) {prefs(c).edit().putString("token",token).remove("registered").apply();SharedJobs.immediate(c)}
}
class PeygirMessagingService: FirebaseMessagingService() {
    override fun onNewToken(token: String) {PushRegistration.newToken(this,token)}
    override fun onDeletedMessages() {SharedJobs.immediate(this)}
    override fun onMessageReceived(message: RemoteMessage) {
        val data=message.data
        if(data["userId"]!=OrgAccess.id(this) || !SharedCredentials(this).connected()) return
        val received=getSharedPreferences("push_private",0)
        val event=data["eventId"].orEmpty()
        val recent=received.getString("recent","").orEmpty().split('\n')
        if(event.isBlank() || event in recent) {SharedJobs.immediate(this);return}
        if(SharedCredentials(this).alerts() && NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            val manager=getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("company_updates","مصوبات و دعوت جلسات",NotificationManager.IMPORTANCE_HIGH))
            val title=when(data["kind"]) {"meeting"->"برنامهٔ جلسه به‌روز شد";"review"->"گزارش انجام مصوبه برای بررسی";else->"مصوبهٔ سازمانی به‌روز شد"}
            val intent=Intent(this,MainActivity::class.java).putExtra("shared_record",data["recordId"]).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pending=PendingIntent.getActivity(this,data["recordId"].hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification=NotificationCompat.Builder(this,"company_updates").setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText("برای مشاهدهٔ اطلاعات تازه، پیگیر را باز کنید.").setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            runCatching {manager.notify(event.hashCode(),notification);received.edit().putString("recent",(recent.filter{it.isNotBlank()}+event).takeLast(200).joinToString("\n")).apply()}
        }
        SharedJobs.immediate(this)
    }
}
