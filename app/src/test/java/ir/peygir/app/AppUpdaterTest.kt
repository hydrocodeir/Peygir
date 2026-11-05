package ir.peygir.app

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class AppUpdaterTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private val source="https://updates.peygir.test/updates/android/latest.json"
    private fun manifest()=JSONObject().put("packageName",BuildConfig.APPLICATION_ID).put("versionCode",40).put("versionName","4.11.0").put("minSdk",26)
        .put("size",3).put("sha256","a".repeat(64)).put("apk","/updates/android/peygir-40.apk").put("notes","تغییرات نسخه")
    @Test fun manifestRequiresHttpsSameOriginPeygirPackageAndBoundedValues() {
        assertEquals("https://updates.peygir.test/updates/android/peygir-40.apk",AndroidUpdate.parse(manifest(),source).url)
        for(url in listOf("http://updates.peygir.test/latest.json","https://user:secret@updates.peygir.test/latest.json","https://updates.peygir.test/latest.json?token=secret","https://updates.peygir.test/latest.json#x"))
            assertThrows(IllegalArgumentException::class.java){AndroidUpdate.parse(manifest(),url)}
        for((key,value) in listOf("apk" to "https://evil.test/update.apk","apk" to "/updates/android/../secret.apk","packageName" to "other.app","versionCode" to 40.5,"size" to 1073741825L,"minSdk" to 1,"sha256" to "wrong"))
            assertThrows(IllegalArgumentException::class.java){AndroidUpdate.parse(manifest().put(key,value),source)}
    }
    @Suppress("DEPRECATION") @Test fun apkMustMatchChecksumPackageVersionAndInstalledSigner() {
        val file=File(context.cacheDir,"update-test.apk");file.writeBytes(byteArrayOf(1,2,3))
        val sha=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it.toInt() and 255)}
        val info=AndroidUpdate.parse(manifest().put("sha256",sha),source)
        val pm=context.packageManager;val shadow=shadowOf(pm)
        val installed=shadow.getInternalMutablePackageInfo(context.packageName)
        fun signing(bytes: ByteArray)=ReflectionHelpers.callConstructor(SigningInfo::class.java).also{shadowOf(it).setSignatures(arrayOf(Signature(bytes)))}
        installed.versionCode=39;installed.signingInfo=signing(byteArrayOf(9,8,7))
        val archive=PackageInfo().apply{packageName=context.packageName;versionCode=40;signingInfo=installed.signingInfo}
        shadow.setPackageArchiveInfo(file.absolutePath,archive)
        AppUpdater.verify(context,file,info)
        file.writeBytes(byteArrayOf(1,2,4))
        assertThrows(IllegalArgumentException::class.java){AppUpdater.verify(context,file,info)}
        file.writeBytes(byteArrayOf(1,2,3))
        archive.signingInfo=signing(byteArrayOf(1,1,1))
        assertThrows(IllegalArgumentException::class.java){AppUpdater.verify(context,file,info)}
        archive.signingInfo=installed.signingInfo;archive.versionCode=41
        assertThrows(IllegalArgumentException::class.java){AppUpdater.verify(context,file,info)}
        archive.versionCode=40;archive.packageName="other.app"
        assertThrows(IllegalArgumentException::class.java){AppUpdater.verify(context,file,info)}
    }
    @Test fun reminderPreferencesRejectInvalidBackupsAndOldBackupsKeepDefaults() {
        assertEquals(UserOptions(),UserOptions.from(JSONObject()))
        for((key,value) in listOf("progressEnabled" to "false","progressPercent" to 0,"progressPercent" to 100,"progressPercent" to 50.5,"taskReminders" to 1))
            assertThrows(IllegalArgumentException::class.java){UserOptions.from(JSONObject().put(key,value))}
        assertFalse(CompanyPush.shouldNotify("assignment","off"))
    }
}
