package ir.peygir.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.*
import android.net.Uri
import androidx.core.content.ContextCompat
import com.googlecode.tesseract.android.TessBaseAPI
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.*
import java.util.zip.ZipInputStream
import java.util.concurrent.atomic.AtomicBoolean

data class PhotoText(val text: String, val confidence: Int)

object PhotoImages {
    fun read(context: Context, uri: Uri, maxDimension: Int = 2600, turns: Int = 0): Bitmap {
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,bounds) }
        require(bounds.outWidth>0 && bounds.outHeight>0) { "عکس قابل خواندن نیست" }
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maxDimension) sample*=2
        val bitmap=context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888 }) } ?: error("عکس قابل خواندن نیست")
        val orientation=runCatching { context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL) } }.getOrDefault(1)
        val matrix=Matrix().apply {
            when(orientation) { 2->setScale(-1f,1f);3->setRotate(180f);4->setScale(1f,-1f);5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(270f);postScale(-1f,1f)};8->setRotate(270f) }
            postRotate((turns.mod(4)*90).toFloat())
        }
        return if(matrix.isIdentity) bitmap else try {Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)} finally {bitmap.recycle()}
    }
}

object OfflineText {
    fun ocr(context: Context, uri: Uri): String = readPhoto(context,uri).text
    @Synchronized fun readPhoto(context: Context, uri: Uri, turns: Int = 0): PhotoText {
        val root=File(context.filesDir,"ocr")
        val data=File(root,"tessdata").apply { mkdirs() }
        listOf("fas","eng").forEach { lang ->
            val file=File(data,"$lang.traineddata")
            if(!file.exists()) {
                val tmp=File(data,"$lang.tmp")
                context.assets.open("tessdata/$lang.traineddata").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                check(tmp.renameTo(file))
            }
        }
        val bitmap=PhotoImages.read(context,uri,turns=turns)
        val tess=TessBaseAPI()
        return try {
            check(tess.init(root.absolutePath,"fas+eng",TessBaseAPI.OEM_LSTM_ONLY)) { "راه‌اندازی OCR ناموفق بود" }
            tess.setVariable("user_defined_dpi","300")
            tess.pageSegMode=TessBaseAPI.PageSegMode.PSM_AUTO
            tess.setImage(bitmap)
            val first=PhotoText(tess.utF8Text.orEmpty().trim(),tess.meanConfidence())
            if(first.text.isNotBlank() && first.confidence>=55) first else {
                tess.clear()
                tess.setVariable("thresholding_method","2")
                tess.pageSegMode=TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
                tess.setImage(bitmap)
                val second=PhotoText(tess.utF8Text.orEmpty().trim(),tess.meanConfidence())
                fun score(value: PhotoText) = value.text.count{it.isLetterOrDigit()}*(value.confidence+20)
                if(score(second)>score(first)) second else first
            }
        } finally { tess.recycle(); bitmap.recycle() }
    }
    private fun unpack(context: Context): File {
        val base=File(context.filesDir,"speech")
        val model=File(base,"vosk-model-small-fa-0.42")
        val ready=File(base,"ready-v1")
        if(ready.exists()) return model
        base.mkdirs()
        ZipInputStream(context.assets.open("vosk-model-small-fa-0.42.zip")).use { zip ->
            while(true) {
                val entry=zip.nextEntry ?: break
                val file=File(base,entry.name)
                require(file.canonicalPath.startsWith(base.canonicalPath+File.separator))
                if(entry.isDirectory) file.mkdirs() else { file.parentFile!!.mkdirs(); file.outputStream().use { zip.copyTo(it) } }
                zip.closeEntry()
            }
        }
        check(File(model,"am/final.mdl").exists())
        ready.writeText("1")
        return model
    }
    @Synchronized fun transcribe(context: Context, path: String): String {
        val result=mutableListOf<String>()
        Model(unpack(context).absolutePath).use { model ->
            Recognizer(model,16000f).use { rec ->
                FileInputStream(path).use { input ->
                    check(input.skip(44)==44L)
                    val buffer=ByteArray(8000)
                    while(true) {
                        val size=input.read(buffer)
                        if(size<0) break
                        if(rec.acceptWaveForm(buffer,size)) JSONObject(rec.result).optString("text").takeIf { it.isNotBlank() }?.let { result+=it }
                    }
                    JSONObject(rec.finalResult).optString("text").takeIf { it.isNotBlank() }?.let { result+=it }
                }
            }
        }
        return result.joinToString("\n")
    }
}

class AudioCapture(private val context: Context,private val temporary: Boolean=false) {
    private val running=AtomicBoolean(false)
    private var recorder: AudioRecord?=null
    private var thread: Thread?=null
    private var file: File?=null
    private var failure: Throwable?=null
    val active get()=running.get()
    @Synchronized fun start() {
        check(!active)
        check(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
        val size=maxOf(4096,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT))
        val rec=AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size*2)
        check(rec.state==AudioRecord.STATE_INITIALIZED) { rec.release(); "میکروفون در دسترس نیست" }
        val dest=File(File(if(temporary) context.cacheDir else context.filesDir,if(temporary) "dictation" else "recordings").apply { mkdirs() },"voice-${java.util.UUID.randomUUID()}.wav")
        failure=null
        try { rec.startRecording(); check(rec.recordingState==AudioRecord.RECORDSTATE_RECORDING) } catch(e:Exception) { rec.release(); throw e }
        file=dest; recorder=rec; running.set(true)
        thread=Thread {
            try {
                RandomAccessFile(dest,"rw").use { out ->
                    out.write(ByteArray(44))
                    val buffer=ByteArray(size)
                    var total=0
                    while(running.get()) {
                        val n=rec.read(buffer,0,buffer.size)
                        if(n>0) { out.write(buffer,0,n); total+=n } else if(n<0 && running.get()) error("خطای دریافت صدا")
                    }
                    out.seek(0); out.write(wavHeader(total))
                }
            } catch(e:Throwable) { failure=e; running.set(false) }
        }.apply { name="Peygir-audio"; start() }
    }
    @Synchronized fun stop(meetingId: Long?): Recording? {
        val rec=recorder ?: return null
        running.set(false)
        runCatching { rec.stop() }
        thread?.join(3000)
        rec.release(); recorder=null
        val dest=file ?: return null
        if(failure!=null || dest.length()<=44) { dest.delete(); throw IOException("صدا ذخیره نشد؛ دوباره امتحان کنید",failure) }
        return Recording(meetingId=meetingId,path=dest.absolutePath,created=System.currentTimeMillis(),seconds=((dest.length()-44)/32000).toInt())
    }
    companion object {
        fun wavHeader(size: Int): ByteArray {
            val b=java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()); b.putInt(size+36); b.put("WAVEfmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(16000); b.putInt(32000); b.putShort(2); b.putShort(16); b.put("data".toByteArray()); b.putInt(size)
            return b.array()
        }
    }
}
