package ir.peygir.app

import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class MistralOcrTest {
    private lateinit var context: Context
    private lateinit var settings: OcrSettings
    private val secret="test-only-not-a-real-api-key"
    @Before fun setup() {
        context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ocr_private",0).edit().clear().commit()
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey()
        settings=OcrSettings(context,OcrKeyCipher{key})
    }
    @Test fun encryptsAtRestPreservesSameProviderAndPreventsCrossProviderKeyReuse() {
        assertFalse(settings.profile().configured)
        settings.save(OcrProvider.MISTRAL,"mistral-ocr-4-1",secret)
        assertEquals(secret,settings.credentials().key)
        assertFalse(context.getSharedPreferences("ocr_private",0).all.toString().contains(secret))
        assertFalse(settings.credentials().toString().contains(secret))
        settings.save(OcrProvider.MISTRAL,"mistral-ocr-latest","")
        assertEquals(secret,settings.credentials().key)
        assertThrows(IllegalArgumentException::class.java) {settings.save(OcrProvider.AVALAI,"mistral-ocr-latest","")}
        assertEquals(OcrProvider.MISTRAL,settings.credentials().provider)
        settings.save(OcrProvider.AVALAI,"mistral-ocr-latest","different-test-only-key")
        assertEquals(OcrProvider.AVALAI,settings.credentials().provider)
        settings.clear();assertFalse(settings.profile().configured);assertFalse(settings.profile().preferOnline)
        assertThrows(OcrFailure::class.java) {settings.credentials()}
    }
    @Test fun tamperedCredentialAndInvalidModelFailWithoutExposingSecretOrLosingSettings() {
        settings.save(OcrProvider.MISTRAL,"mistral-ocr-4-1",secret)
        assertThrows(IllegalArgumentException::class.java) {settings.save(OcrProvider.MISTRAL,"bad\nmodel","new")}
        assertEquals(secret,settings.credentials().key)
        assertThrows(IllegalArgumentException::class.java) {settings.save(OcrProvider.MISTRAL,"mistral-ocr-4-1","bad\r\nkey")}
        context.getSharedPreferences("ocr_private",0).edit().putString("credential","broken").commit()
        val error=assertThrows(OcrFailure::class.java) {settings.credentials()}
        assertTrue(error.message!!.contains("دوباره وارد کنید"));assertFalse(error.toString().contains(secret))
    }
    @Test fun requestContainsOnlySelectedImageAndConfiguredModelWithNoReturnedImages() {
        val image=byteArrayOf(1,2,3,4)
        val payload=JSONObject(MistralOcr.requestBody("mistral-ocr-4-1",image).toString(Charsets.UTF_8))
        assertEquals(setOf("model","document","include_image_base64"),payload.keys().asSequence().toSet())
        assertEquals("mistral-ocr-4-1",payload.getString("model"));assertFalse(payload.getBoolean("include_image_base64"))
        val document=payload.getJSONObject("document")
        assertEquals("image_url",document.getString("type"))
        assertEquals("data:image/jpeg;base64,AQIDBA==",document.getString("image_url"))
        assertThrows(OcrFailure::class.java) {MistralOcr.requestBody("mistral-ocr-4-1",ByteArray(0))}
    }
    @Test fun responseOrdersPagesKeepsPersianDatesAndRemovesEmbeddedImageReferences() {
        val text=MistralOcr.responseText(OcrReply(200,"""{"pages":[{"index":1,"markdown":"مسئول: آقای احمدی\nمهلت: ۱۴۰۵/۰۸/۱۵"},{"index":0,"markdown":"تهیه گزارش فروش\n![عکس](img-0.jpeg)"}]}"""))
        assertEquals("تهیه گزارش فروش\n\nمسئول: آقای احمدی\nمهلت: ۱۴۰۵/۰۸/۱۵",text)
        val proposal=Extractor.extract(text).single()
        assertEquals("آقای احمدی",proposal.owner);assertEquals(Solar.toDate(1405,8,15),proposal.due)
        assertEquals("",MistralOcr.responseText(OcrReply(200,"""{"pages":[]}""")))
        assertThrows(OcrFailure::class.java) {MistralOcr.responseText(OcrReply(200,"not-json"))}
        assertThrows(OcrFailure::class.java) {MistralOcr.responseText(OcrReply(200,"""{"pages":[{"index":0}]}"""))}
        assertThrows(OcrFailure::class.java) {MistralOcr.responseText(OcrReply(200,"""{"pages":[{"index":0,"markdown":null}]}"""))}
        assertEquals("گزارش\nمسئول: احمدی",MistralOcr.responseText(OcrReply(200,"""{"pages":[{"index":0,"markdown":"# **گزارش**<br>مسئول: احمدی"}]}""")))
    }
    @Test fun errorsAreActionableWithoutEchoingServerBodiesOrSecrets() {
        mapOf(401 to "کلید",402 to "اعتبار",403 to "دسترسی",404 to "مدل",413 to "حجم",422 to "مدل",429 to "سهمیه",503 to "موقتاً",302 to "منتقل نشد").forEach{(status,expected)->
            val error=assertThrows(OcrFailure::class.java) {MistralOcr.responseText(OcrReply(status,"secret=$secret;image=private"))}
            assertTrue(error.message!!.contains(expected));assertFalse(error.toString().contains(secret));assertNull(error.cause)
        }
        assertFalse(MistralOcr.safeFailure(IOException(secret)).toString().contains(secret))
        assertTrue(MistralOcr.safeFailure(SocketTimeoutException(secret)).message!!.contains("دیر رسید"))
    }
    private class Connection(private val code: Int, private val response: ByteArray): HttpURLConnection(URL("https://api.mistral.ai/v1/ocr")) {
        var disconnected=false
        val posted=ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {disconnected=true}
        override fun usingProxy()=false
        override fun getOutputStream()=posted
        override fun getInputStream()=ByteArrayInputStream(response)
        override fun getErrorStream(): Nothing=error("Error bodies must not be read")
        override fun getResponseCode()=code
    }
    @Test fun transportUsesTlsCorrectProviderHeadersBoundedTimeoutAndNeverFollowsRedirects() {
        val connection=Connection(302,ByteArray(0));var destination=""
        val transport=OcrHttp {url->destination=url.toString();connection}
        val payload=MistralOcr.requestBody("mistral-ocr-latest",byteArrayOf(1))
        val reply=transport.post(OcrCredentials(OcrProvider.AVALAI,"mistral-ocr-latest",secret),payload,OcrCancellation())
        assertEquals("https://api.avalai.ir/v1/ocr",destination)
        assertEquals("POST",connection.requestMethod);assertFalse(connection.instanceFollowRedirects)
        assertEquals("Bearer $secret",connection.getRequestProperty("Authorization"))
        assertEquals(15_000,connection.connectTimeout);assertEquals(60_000,connection.readTimeout)
        assertArrayEquals(payload,connection.posted.toByteArray());assertTrue(connection.disconnected)
        assertEquals(302,reply.status);assertEquals("",reply.body)
    }
    @Test fun oversizedResponsesDisconnectAndCancelledCallsNeverOpenConnection() {
        val connection=Connection(200,ByteArray(1_048_577))
        assertThrows(OcrFailure::class.java) {OcrHttp {connection}.post(OcrCredentials(OcrProvider.MISTRAL,"mistral-ocr-4-1",secret),byteArrayOf(1),OcrCancellation())}
        assertTrue(connection.disconnected)
        val cancelled=OcrCancellation().apply{cancel()}
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            OcrHttp {error("Must not open a cancelled request")}.post(OcrCredentials(OcrProvider.MISTRAL,"mistral-ocr-4-1",secret),byteArrayOf(1),cancelled)
        }
    }
    @Test fun cancellationDisconnectsAnInFlightRequest() {
        val entered=CountDownLatch(1);val released=CountDownLatch(1)
        val connection=object: HttpURLConnection(URL("https://api.mistral.ai/v1/ocr")) {
            override fun connect() {}
            override fun disconnect() {released.countDown()}
            override fun usingProxy()=false
            override fun getOutputStream()=ByteArrayOutputStream()
            override fun getResponseCode(): Int {entered.countDown();check(released.await(5,TimeUnit.SECONDS));return 200}
        }
        val cancellation=OcrCancellation();val executor=Executors.newSingleThreadExecutor()
        try {
            val future=executor.submit<OcrReply> {OcrHttp {connection}.post(OcrCredentials(OcrProvider.MISTRAL,"mistral-ocr-4-1",secret),byteArrayOf(1),cancellation)}
            assertTrue(entered.await(5,TimeUnit.SECONDS));cancellation.cancel()
            val failure=assertThrows(java.util.concurrent.ExecutionException::class.java) {future.get(5,TimeUnit.SECONDS)}
            assertTrue(failure.cause is java.util.concurrent.CancellationException)
        } finally {cancellation.cancel();executor.shutdownNow()}
    }
}
