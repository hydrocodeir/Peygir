package ir.peygir.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class OcrProvider(val label: String, val endpoint: String, val defaultModel: String) {
    MISTRAL("Mistral مستقیم","https://api.mistral.ai/v1/ocr","mistral-ocr-4-1"),
    AVALAI("AvalAI","https://api.avalai.ir/v1/ocr","mistral-ocr-latest")
}
data class OcrProfile(val provider: OcrProvider, val model: String, val configured: Boolean, val preferOnline: Boolean)
class OcrCredentials(val provider: OcrProvider, val model: String, val key: String) {
    override fun toString()="OcrCredentials(provider=$provider, model=$model, key=[hidden])"
}
class OcrFailure(message: String): IOException(message)

internal class OcrKeyCipher(private val key: ()->SecretKey = ::androidOcrKey) {
    fun encrypt(value: String, provider: OcrProvider): String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key());cipher.updateAAD(provider.name.toByteArray())
        return Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    fun decrypt(value: String, provider: OcrProvider): String {
        val parts=value.split(':');require(parts.size==2)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        cipher.updateAAD(provider.name.toByteArray())
        return cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }
}
@Synchronized private fun androidOcrKey(): SecretKey {
    val alias="peygir-ocr-key-v1"
    val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    (store.getKey(alias,null) as? SecretKey)?.let{return it}
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
}

class OcrSettings internal constructor(context: Context, private val cipher: OcrKeyCipher = OcrKeyCipher()) {
    private val prefs=context.applicationContext.getSharedPreferences("ocr_private",Context.MODE_PRIVATE)
    fun profile(): OcrProfile {
        val provider=runCatching{OcrProvider.valueOf(prefs.getString("provider",null).orEmpty())}.getOrDefault(OcrProvider.MISTRAL)
        return OcrProfile(provider,prefs.getString("model",provider.defaultModel) ?: provider.defaultModel,prefs.contains("credential"),prefs.getBoolean("online",false))
    }
    @Synchronized fun save(provider: OcrProvider, model: String, newKey: String) {
        val validModel=model.trim()
        require(Regex("mistral-ocr-[A-Za-z0-9._-]{1,60}").matches(validModel)) {"نام مدل OCR معتبر نیست."}
        val old=profile()
        val key=newKey.trim()
        require(key.isNotEmpty() || (old.configured && old.provider==provider)) {"کلید API همین ارائه‌دهنده را وارد کنید."}
        require(key.length<=1024 && key.all{it.code in 33..126}) {"کلید API نباید فاصله یا خط جدید داشته باشد."}
        val encrypted=if(key.isEmpty()) prefs.getString("credential",null)!! else try {cipher.encrypt(key,provider)} catch(_:Exception) {throw OcrFailure("ذخیرهٔ امن کلید ناموفق بود؛ دوباره تلاش کنید.")}
        check(prefs.edit().putString("provider",provider.name).putString("model",validModel).putString("credential",encrypted).putBoolean("online",true).commit()) {"ذخیرهٔ تنظیمات ناموفق بود."}
    }
    @Synchronized fun credentials(): OcrCredentials {
        val p=profile()
        if(!p.configured) throw OcrFailure("ابتدا کلید API را در تنظیمات Mistral وارد کنید.")
        val key=try {cipher.decrypt(prefs.getString("credential",null)!!,p.provider)} catch(_:Exception) {throw OcrFailure("کلید ذخیره‌شده قابل خواندن نیست؛ آن را دوباره وارد کنید.")}
        return OcrCredentials(p.provider,p.model,key)
    }
    fun preferOnline(value: Boolean) {prefs.edit().putBoolean("online",value && profile().configured).apply()}
    @Synchronized fun clear() {check(prefs.edit().clear().commit()) {"حذف کلید ناموفق بود."}}
}

internal class OcrCancellation {
    private val cancelled=AtomicBoolean(false)
    private val connection=AtomicReference<HttpURLConnection?>(null)
    fun check() {if(cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException()}
    fun attach(value: HttpURLConnection) {connection.set(value);if(cancelled.get()){value.disconnect();throw CancellationException()}}
    fun cancel() {cancelled.set(true);connection.get()?.disconnect()}
    fun close() {connection.getAndSet(null)?.disconnect()}
}
internal data class OcrReply(val status: Int, val body: String)
internal class OcrHttp(private val open: (URL)->HttpURLConnection = {it.openConnection() as HttpURLConnection}) {
    fun post(credentials: OcrCredentials, body: ByteArray, cancellation: OcrCancellation): OcrReply {
        cancellation.check()
        val connection=open(URL(credentials.provider.endpoint))
        cancellation.attach(connection)
        try {
            connection.requestMethod="POST";connection.instanceFollowRedirects=false
            connection.connectTimeout=15_000;connection.readTimeout=60_000;connection.useCaches=false
            connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
            connection.setRequestProperty("Accept","application/json")
            connection.setRequestProperty("Authorization","Bearer ${credentials.key}")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use {it.write(body)}
            cancellation.check()
            val status=connection.responseCode
            cancellation.check()
            // Error bodies can echo a key or image. They never reach logs, UI or disk.
            val response=if(status in 200..299) connection.inputStream.use {readLimited(it,cancellation)} else ""
            return OcrReply(status,response)
        } finally {cancellation.close()}
    }
    private fun readLimited(input: InputStream, cancellation: OcrCancellation): String {
        val out=ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {
            cancellation.check()
            val count=input.read(buffer);if(count<0) break
            if(out.size()+count>1_048_576) throw OcrFailure("پاسخ سرویس بیش از حد بزرگ است؛ از بخش کوچک‌تری عکس بگیرید.")
            out.write(buffer,0,count)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}

fun interface OnlinePhotoReader {
    suspend fun read(context: Context, uri: Uri, turns: Int, credentials: OcrCredentials): String
}
object MistralOcr: OnlinePhotoReader {
    private val worker=Executors.newSingleThreadExecutor {r->Thread(r,"Peygir-OCR").apply{isDaemon=true}}
    override suspend fun read(context: Context, uri: Uri, turns: Int, credentials: OcrCredentials): String = withTimeoutOrNull(90_000) {request(context,uri,turns,credentials)} ?: throw OcrFailure("مهلت خواندن آنلاین تمام شد؛ عکس محفوظ است، دوباره تلاش کنید.")
    private suspend fun request(context: Context, uri: Uri, turns: Int, credentials: OcrCredentials): String = suspendCancellableCoroutine {continuation->
        val cancellation=OcrCancellation()
        val future=worker.submit {
            try {
                cancellation.check()
                val bitmap=try {PhotoImages.read(context,uri,2600,turns)} catch(_:Exception) {throw OcrFailure("عکس قابل خواندن نیست؛ عکس دیگری بگیرید یا انتخاب کنید.")}
                val jpeg=try {ByteArrayOutputStream().use{out->check(bitmap.compress(Bitmap.CompressFormat.JPEG,90,out));out.toByteArray()}} finally {bitmap.recycle()}
                cancellation.check()
                val body=requestBody(credentials.model,jpeg)
                val reply=OcrHttp().post(credentials,body,cancellation)
                val text=responseText(reply)
                if(continuation.isActive) continuation.resume(text)
            } catch(e:Exception) {
                if(continuation.isActive) continuation.resumeWithException(safeFailure(e))
            } finally {cancellation.close()}
        }
        continuation.invokeOnCancellation {cancellation.cancel();future.cancel(true)}
    }
    internal fun requestBody(model: String, jpeg: ByteArray): ByteArray {
        if(jpeg.isEmpty() || jpeg.size>8_388_608) throw OcrFailure("حجم عکس مناسب نیست؛ از بخش کوچک‌تری عکس بگیرید.")
        return JSONObject().put("model",model).put("document",JSONObject().put("type","image_url")
            .put("image_url","data:image/jpeg;base64,"+Base64.encodeToString(jpeg,Base64.NO_WRAP)))
            .put("include_image_base64",false).toString().toByteArray(Charsets.UTF_8)
    }
    internal fun responseText(reply: OcrReply): String {
        if(reply.status !in 200..299) throw OcrFailure(when(reply.status) {
            401->"کلید API معتبر نیست؛ آن را در تنظیمات Mistral اصلاح کنید."
            402->"اعتبار حساب کافی نیست؛ حساب سرویس را شارژ کنید."
            403->"دسترسی سرویس مجاز نیست؛ مجوز حساب یا دسترسی منطقه را بررسی کنید."
            404,422->"مدل یا درخواست OCR پذیرفته نشد؛ مدل را در تنظیمات Mistral بررسی کنید."
            413->"حجم عکس بیش از حد مجاز است؛ از بخش کوچک‌تری عکس بگیرید."
            429->"سهمیه یا تعداد درخواست‌های سرویس تمام شده است؛ اعتبار را بررسی و کمی بعد دوباره تلاش کنید."
            in 300..399->"نشانی سرویس تغییر کرده است؛ برای حفاظت از کلید، درخواست منتقل نشد."
            in 500..599->"سرویس OCR موقتاً در دسترس نیست؛ کمی بعد دوباره تلاش کنید."
            else->"سرویس عکس را نپذیرفت؛ تنظیمات را بررسی یا دوباره عکس بگیرید."
        })
        return try {
            val pages=JSONObject(reply.body).getJSONArray("pages")
            val ordered=(0 until pages.length()).map{pages.getJSONObject(it)}.sortedBy{it.optInt("index",0)}
            ordered.joinToString("\n\n") {page->
                val markdown=page.get("markdown")
                require(markdown is String)
                markdown.replace(Regex("!\\[[^\\n]*?\\]\\([^\\n)]*\\)"),"")
                    .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"),"")
                    .replace("**","").replace("__","")
                    .replace(Regex("(?i)<br\\s*/?>"),"\n").trim()
            }.trim()
        } catch(_:Exception) {throw OcrFailure("پاسخ OCR قابل خواندن نیست؛ دوباره تلاش کنید.")}
    }
    internal fun safeFailure(e: Exception): OcrFailure = when(e) {
        is OcrFailure->e
        is SocketTimeoutException->OcrFailure("پاسخ سرویس دیر رسید. عکس محفوظ است؛ دوباره تلاش کنید.")
        is SSLException->OcrFailure("اتصال امن به سرویس برقرار نشد؛ شبکه و ساعت گوشی را بررسی کنید.")
        else->OcrFailure("اتصال به OCR ناموفق بود؛ اینترنت را بررسی یا از حالت آفلاین استفاده کنید.")
    }
}
