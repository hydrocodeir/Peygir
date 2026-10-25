package ir.peygir.app

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*

/** Uses the real Android Keystore and isolated test preferences; never makes a network call. */
class DeviceOcrKeyTest {
    @Test fun storesAndReopensEncryptedKeyWithoutTouchingUserCredentials() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val name="ocr-key-test-${java.util.UUID.randomUUID()}"
        val context=object: ContextWrapper(base) {
            override fun getApplicationContext(): Context=this
            override fun getSharedPreferences(requested: String, mode: Int)=super.getSharedPreferences(if(requested=="ocr_private") name else requested,mode)
        }
        try {
            val secret="test-only-${java.util.UUID.randomUUID()}"
            OcrSettings(context).save(OcrProvider.MISTRAL,"mistral-ocr-4-1",secret)
            assertFalse(context.getSharedPreferences("ocr_private",0).all.toString().contains(secret))
            val reopened=OcrSettings(context)
            assertEquals(secret,reopened.credentials().key)
            assertEquals(OcrProvider.MISTRAL,reopened.credentials().provider)
            reopened.clear();assertFalse(reopened.profile().configured)
        } finally {base.deleteSharedPreferences(name)}
    }
}
