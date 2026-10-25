package ir.peygir.app

import android.media.MediaPlayer
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Run only after the person holding the phone explicitly says they are ready to speak. */
class DeviceMicTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun captureSpeechAndPlayBack() {
        org.junit.Assume.assumeTrue("Interactive test: pass -e liveMic true only when ready to speak",InstrumentationRegistry.getArguments().getString("liveMic")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        compose.activity.runOnUiThread {compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}
        compose.waitUntil(30000) {compose.onAllNodesWithText("برای پیگیری").fetchSemanticsNodes().isNotEmpty()}
        val capture=AudioCapture(context)
        var file: File?=null
        try {
            capture.start()
            Thread.sleep(10000)
            val recording=capture.stop(null) ?: error("No recording returned")
            file=File(recording.path)
            val bytes=file.readBytes()
            val pcm=ByteBuffer.wrap(bytes,44,bytes.size-44).order(ByteOrder.LITTLE_ENDIAN)
            var peak=0
            while(pcm.remaining()>=2) peak=maxOf(peak,kotlin.math.abs(pcm.short.toInt()))
            val start=System.currentTimeMillis()
            val text=OfflineText.transcribe(context,file.absolutePath)
            val millis=System.currentTimeMillis()-start
            val player=MediaPlayer()
            try {player.setDataSource(file.absolutePath);player.prepare();player.start();Thread.sleep(11000)} finally {player.release()}
            val result=JSONObject().put("seconds",recording.seconds).put("peak",peak).put("transcribeMillis",millis).put("transcript",text).put("proposals",Extractor.extract(text).size)
            File(context.cacheDir,"device-test").apply{mkdirs()}.resolve("live-microphone.json").writeText(result.toString())
            assertTrue("Microphone samples are silent",peak>100)
            assertTrue("Persian speech was not recognized",text.isNotBlank())
        } finally {if(capture.active) capture.stop(null)?.let{File(it.path).delete()};file?.delete()}
    }
}
