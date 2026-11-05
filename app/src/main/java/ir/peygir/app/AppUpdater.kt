package ir.peygir.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

internal data class AndroidUpdate(val code: Long,val version: String,val size: Long,val sha256: String,val minSdk: Int,val url: String,val notes: String) {
    companion object {
        const val MAX_APK=1024L*1024*1024
        fun source(value: String): URL {
            val u=URL(value)
            require(u.protocol=="https" && u.host.isNotBlank() && u.userInfo==null && u.ref==null && u.query==null && (u.port==-1 || u.port==443)) {"نشانی به‌روزرسانی باید HTTPS و بدون اطلاعات ورود باشد."}
            return u
        }
        fun parse(p: JSONObject,source: String): AndroidUpdate {
            fun number(key: String): Long {val n=p.get(key);require(n is Number && n.toDouble().isFinite() && n.toDouble()==n.toLong().toDouble());return n.toLong()}
            val base=AndroidUpdate.source(source)
            val code=number("versionCode");val size=number("size");val sdk=number("minSdk")
            val version=p.getString("versionName");val sha=p.getString("sha256")
            require(p.getString("packageName")==BuildConfig.APPLICATION_ID && code in 1..Int.MAX_VALUE && size in 1..MAX_APK && sdk in 26..100 && version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) && sha.matches(Regex("[a-f0-9]{64}"))) {"اطلاعات نسخهٔ جدید معتبر نیست."}
            val path=p.getString("apk")
            require(path=="/updates/android/peygir-$code.apk") {"مسیر فایل نصب معتبر نیست."}
            val target=source(URL(base,path).toString())
            require(target.host==base.host && target.port==base.port)
            val notes=p.optString("notes","");require(notes.length<=12000)
            return AndroidUpdate(code,version,size,sha,sdk.toInt(),target.toString(),notes)
        }
    }
}

internal data class AppUpdateState(val busy: Boolean=false,val downloading: Boolean=false,val info: AndroidUpdate?=null,val progress: Int=0,val ready: Boolean=false,val message: String="",val error: Boolean=false,val source: String="")

/** No session cookies/tokens are sent. Updates are public, HTTPS and verified before installation. */
internal object AppUpdater {
    val state=MutableStateFlow(AppUpdateState())
    private val worker=Executors.newSingleThreadExecutor()
    private val cancelled=AtomicBoolean(false)
    private fun prefs(c: Context)=Accounts.root(c).getSharedPreferences("app_updates",0)
    private fun file(c: Context)=File(Accounts.root(c).cacheDir,"updates/update.apk")
    fun source(c: Context)=prefs(c).getString("source",null) ?: ((SharedCredentials(c).read()?.endpoint ?: "https://peygir.hydrocode.ir").trimEnd('/')+"/updates/android/latest.json")
    @Synchronized fun saveSource(c: Context,value: String) {
        check(!state.value.busy)
        AndroidUpdate.source(value)
        prefs(c).edit().putString("source",value).remove("ready_manifest").remove("ready_source").apply()
        file(c).delete();state.value=AppUpdateState(source=value)
    }
    private fun connection(url: String): HttpsURLConnection {
        val c=AndroidUpdate.source(url).openConnection() as HttpsURLConnection
        c.connectTimeout=15000;c.readTimeout=30000;c.instanceFollowRedirects=false
        c.setRequestProperty("Accept-Encoding","identity")
        c.setRequestProperty("User-Agent","Peygir/${BuildConfig.VERSION_NAME}")
        return c
    }
    @Synchronized fun check(c: Context) {
        if(state.value.busy) return
        val app=Accounts.root(c).applicationContext;val endpoint=source(c)
        state.value=AppUpdateState(busy=true,source=endpoint)
        worker.execute {
            runCatching {
                val conn=connection(endpoint)
                val raw=try {
                    require(conn.responseCode==200) {if(conn.responseCode==404) "هنوز نسخه‌ای روی سرور به‌روزرسانی منتشر نشده است." else "سرور به‌روزرسانی در دسترس نیست؛ دوباره تلاش کنید."}
                    val bytes=conn.inputStream.use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096);while(out.size()<=65536){val n=input.read(buffer);if(n<0) break;out.write(buffer,0,n)};out.toByteArray()}
                    require(bytes.size<=65536) {"اطلاعات نسخه بیش از اندازه بزرگ است."}
                    bytes.toString(Charsets.UTF_8)
                } finally {conn.disconnect()}
                val info=AndroidUpdate.parse(JSONObject(raw),endpoint)
                require(info.minSdk<=Build.VERSION.SDK_INT) {"نسخهٔ جدید به اندروید جدیدتری نیاز دارد."}
                val newer=info.code>BuildConfig.VERSION_CODE
                val ready=newer && prefs(app).getString("ready_manifest",null)==raw && prefs(app).getString("ready_source",null)==endpoint && runCatching{verify(app,file(app),info);true}.getOrDefault(false)
                if(!ready) {file(app).delete();prefs(app).edit().remove("ready_manifest").remove("ready_source").apply()}
                // The exact manifest is kept only after a successful APK download.
                manifests[info.url]=raw
                state.value=AppUpdateState(info=info,ready=ready,source=endpoint,message=if(newer) "نسخهٔ ${info.version.fa()} آمادهٔ دریافت است." else "برنامه به‌روز است.")
            }.onFailure{state.value=AppUpdateState(source=endpoint,error=true,message=readable(it))}
        }
    }
    private val manifests=mutableMapOf<String,String>()
    fun cancel(){cancelled.set(true)}
    @Synchronized fun download(c: Context) {
        val old=state.value;val info=old.info ?: return
        if(old.busy || info.code<=BuildConfig.VERSION_CODE) return
        val app=Accounts.root(c).applicationContext
        cancelled.set(false);state.value=old.copy(busy=true,downloading=true,ready=false,progress=0,error=false,message="در حال دریافت فایل نصب…")
        worker.execute {
            val target=file(app);target.parentFile!!.mkdirs()
            val part=File(target.parentFile,"update.part")
            runCatching {
                val conn=connection(info.url)
                try {
                    require(conn.responseCode==200) {"دریافت فایل نصب ناموفق بود؛ دوباره تلاش کنید."}
                    require(conn.contentLengthLong<0 || conn.contentLengthLong==info.size) {"اندازهٔ فایل نصب با اطلاعات نسخه هماهنگ نیست."}
                    var bytes=0L;var last=0L
                    conn.inputStream.use {input->part.outputStream().use {output->
                        val buffer=ByteArray(65536)
                        while(true) {
                            check(!cancelled.get()) {"دریافت لغو شد."}
                            val n=input.read(buffer);if(n<0) break
                            bytes+=n;require(bytes<=info.size) {"حجم فایل نصب معتبر نیست."}
                            output.write(buffer,0,n)
                            val now=System.currentTimeMillis()
                            if(now-last>=200) {state.value=state.value.copy(progress=(bytes*100/info.size).toInt());last=now}
                        }
                    }}
                    require(bytes==info.size) {"دریافت فایل کامل نشد؛ دوباره تلاش کنید."}
                } finally {conn.disconnect()}
                check(!cancelled.get()) {"دریافت لغو شد."}
                verify(app,part,info)
                target.delete();check(part.renameTo(target)) {"ذخیرهٔ فایل نصب ناموفق بود."}
                prefs(app).edit().putString("ready_manifest",manifests[info.url]).putString("ready_source",old.source).commit()
                state.value=old.copy(progress=100,ready=true,message="فایل بررسی شد و آمادهٔ نصب است.",error=false)
            }.onFailure {
                part.delete();target.delete();prefs(app).edit().remove("ready_manifest").remove("ready_source").apply()
                state.value=old.copy(ready=false,error=!cancelled.get(),message=readable(it))
            }
        }
    }
    @Suppress("DEPRECATION") private fun signers(p: PackageInfo): Set<String> {
        val signatures=if(Build.VERSION.SDK_INT>=28) p.signingInfo?.apkContentsSigners else p.signatures
        return signatures.orEmpty().map{digest(it.toByteArray())}.toSet()
    }
    private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    @Suppress("DEPRECATION") fun verify(c: Context,apk: File,info: AndroidUpdate) {
        require(apk.isFile && apk.length()==info.size) {"فایل نصب کامل نیست."}
        val hash=MessageDigest.getInstance("SHA-256")
        apk.inputStream().use{input->val buf=ByteArray(65536);while(true){val n=input.read(buf);if(n<0) break;hash.update(buf,0,n)}}
        val actual=hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
        require(actual==info.sha256) {"صحت فایل نصب تأیید نشد؛ دوباره دریافت کنید."}
        val pm=c.packageManager
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive=pm.getPackageArchiveInfo(apk.absolutePath,flags) ?: error("فایل نصب معتبر نیست.")
        val installed=pm.getPackageInfo(c.packageName,flags)
        require(archive.packageName==c.packageName && PackageInfoCompat.getLongVersionCode(archive)==info.code && info.code>PackageInfoCompat.getLongVersionCode(installed)) {"فایل نصب با این برنامه یا نسخه سازگار نیست."}
        require(signers(installed).isNotEmpty() && signers(archive)==signers(installed)) {"امضای فایل نصب با نسخهٔ نصب‌شده یکسان نیست."}
    }
    fun install(c: Context) {
        val current=state.value;val info=current.info ?: return
        require(current.ready && !current.busy && file(c).isFile && file(c).length()==info.size) {"ابتدا فایل نصب را دریافت کنید."}
        if(!c.packageManager.canRequestPackageInstalls()) {
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${c.packageName}")))
            state.value=current.copy(message="اجازهٔ نصب را فعال کنید، به پیگیر برگردید و «نصب نسخهٔ جدید» را بزنید.")
            return
        }
        val uri=FileProvider.getUriForFile(c,"${c.packageName}.updates",file(c))
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
    private fun readable(t: Throwable)=if(t is IllegalArgumentException || t is IllegalStateException) t.message ?: "عملیات ناموفق بود." else "ارتباط یا ذخیرهٔ فایل ناموفق بود؛ اینترنت و فضای خالی گوشی را بررسی و دوباره تلاش کنید."
}
