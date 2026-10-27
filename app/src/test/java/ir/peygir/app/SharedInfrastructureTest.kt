package ir.peygir.app

import android.app.Application
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.KeyGenerator
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class SharedInfrastructureTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun setup(){context.getSharedPreferences("shared_private",0).edit().clear().commit()}
    @After fun cleanup(){context.getSharedPreferences("shared_private",0).edit().clear().commit()}
    @Test fun companyRoutingHeaderIsValidatedAndIsNotAddedToLegacyRequests() {
        val scoped=MockConnection(200,"{\"ok\":true}")
        SharedHttp("https://unit.test","token",companyCode(" COMPANY-A ")){scoped}.post("/v2/config",JSONObject())
        assertEquals("company-a",scoped.getRequestProperty("X-Peygir-Company"));assertTrue(scoped.closed)
        val legacy=MockConnection(200,"{\"ok\":true}")
        SharedHttp("https://unit.test","token"){legacy}.post("/v2/config",JSONObject())
        assertNull(legacy.getRequestProperty("X-Peygir-Company"))
        assertThrows(IllegalArgumentException::class.java){companyCode("../another")}
        val session=SharedSession("https://unit.test","token","id","شرکت","مدیر","manager","user", "company-a")
        assertEquals("company-a",SharedSession.from(session.json()).company)
        assertEquals("",SharedSession.from(session.json().also{it.remove("company")}).company)
        verifyLoginCompany("",JSONObject())
        verifyLoginCompany("company-a",JSONObject().put("company","company-a"))
        assertThrows(IllegalStateException::class.java){verifyLoginCompany("company-a",JSONObject())}
        assertThrows(IllegalStateException::class.java){verifyLoginCompany("company-a",JSONObject().put("company","company-b"))}
    }
    @Test fun httpsTransportSendsBearerAndJsonWithoutFollowingRedirects() {
        val connection=MockConnection(200,"{\"ok\":true}")
        val response=SharedHttp("https://unit.test","test-token"){connection}.post("/v1/sync",JSONObject().put("cursor",4))
        assertTrue(response.getBoolean("ok"));assertEquals("Bearer test-token",connection.getRequestProperty("Authorization"));assertFalse(connection.instanceFollowRedirects)
        assertEquals(4,JSONObject(connection.sent.toString("UTF-8")).getInt("cursor"));assertTrue(connection.closed)
        assertThrows(IllegalArgumentException::class.java){validSharedEndpoint("http://unit.test")}
        assertThrows(IllegalArgumentException::class.java){validSharedEndpoint("https://user:password@unit.test")}
        assertThrows(IllegalArgumentException::class.java){validSharedEndpoint("https://unit.test/path?secret=x")}
    }
    @Test fun rejectedSessionsAndRedirectsReturnErrorsAndCloseConnection() {
        val denied=MockConnection(401,"{\"error\":\"نشست تمام شده\"}")
        val e=assertThrows(SharedHttpError::class.java){SharedHttp("https://unit.test","x"){denied}.post("/v1/sync",JSONObject())}
        assertEquals(401,e.code);assertTrue(denied.closed)
        val redirected=MockConnection(302,"")
        assertThrows(SharedHttpError::class.java){SharedHttp("https://unit.test","x"){redirected}.post("/v1/sync",JSONObject())}
        assertFalse(redirected.instanceFollowRedirects)
    }
    @Test fun sessionIsEncryptedLocalAlertChoicePersistsAndBackupExcludesCredentials() {
        val key=KeyGenerator.getInstance("AES").apply{init(256)}.generateKey();val credentials=SharedCredentials(context){key}
        val token="a".repeat(64);val session=SharedSession("https://unit.test",token,"workspace","دفتر","دستیار","assistant")
        credentials.save(session);assertTrue(credentials.connected());assertFalse(credentials.alerts())
        val raw=context.getSharedPreferences("shared_private",0).getString("session","").orEmpty()
        assertFalse(raw.contains(token));assertFalse(raw.contains("unit.test"));assertEquals(token,credentials.read()!!.token)
        credentials.alerts(true);credentials.save(session);assertTrue(credentials.alerts())
        context.deleteDatabase("credential-backup.db")
        val store=Store(context,"credential-backup.db")
        try {
            val bytes=ByteArrayOutputStream().also{Backup.write(context,it,store)}.toByteArray()
            ZipInputStream(bytes.inputStream()).use{z->z.nextEntry;val json=z.readBytes().toString(Charsets.UTF_8);assertFalse(json.contains(token));assertFalse(json.contains("shared_private"));assertFalse(json.contains("session"))}
        } finally {store.close()}
        credentials.clear();assertFalse(credentials.connected());assertNull(credentials.read())
    }
    private class MockConnection(private val code: Int,private val response: String): HttpURLConnection(URL("https://unit.test")) {
        val sent=ByteArrayOutputStream();var closed=false
        override fun connect(){}
        override fun disconnect(){closed=true}
        override fun usingProxy()=false
        override fun getOutputStream()=sent
        override fun getInputStream()=ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        override fun getErrorStream()=inputStream
        override fun getResponseCode()=code
    }
}
