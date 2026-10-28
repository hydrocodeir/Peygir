package ir.peygir.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File

internal enum class DictationMode(val key: String,val title: String,val help: String) {
    TAP("tap","لمس و پایان","میکروفون را لمس کنید، صحبت کنید و «پایان» را بزنید."),
    HOLD("hold","نگه‌داشتن و صحبت","میکروفون را نگه دارید و صحبت کنید؛ با رهاکردن، متن وارد می‌شود."),
    KEYBOARD("keyboard","نوار گفتار بالای کیبورد","روی فیلد متن بزنید؛ میکروفون نوار بالای کیبورد را نگه دارید و برای تبدیل رها کنید.");
    companion object {
        const val PREFERENCE="dictation_mode"
        fun read(context: Context)=from(context.getSharedPreferences("settings",0).getString(PREFERENCE,null))
        fun from(key: String?)=entries.firstOrNull{it.key==key} ?: HOLD
    }
}

@Composable internal fun rememberDictationMode(): DictationMode {
    val context=LocalContext.current
    val prefs=remember(context){context.getSharedPreferences("settings",0)}
    var mode by remember(prefs){mutableStateOf(DictationMode.read(context))}
    DisposableEffect(prefs) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,key->if(key==null || key==DictationMode.PREFERENCE) mode=DictationMode.read(context)}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return mode
}

internal interface FieldSpeechEngine {
    fun start()
    fun stop(): File?
    fun transcribe(file: File): String
}
internal val LocalFieldSpeechEngine=staticCompositionLocalOf<(Context)->FieldSpeechEngine>{{context->
    val capture=AudioCapture(context,temporary=true)
    object: FieldSpeechEngine {
        override fun start()=capture.start()
        override fun stop()=capture.stop(null)?.let{File(it.path)}
        override fun transcribe(file: File)=OfflineText.transcribe(context,file.path)
    }
}}

/** Owns one temporary clip; cancellation can never insert late text into another form. */
@Stable internal class FieldSpeechSession(private val engine: FieldSpeechEngine,private val scope: CoroutineScope,private val recognized: (String)->Unit) {
    var recording by mutableStateOf(false);private set
    var busy by mutableStateOf(false);private set
    var error by mutableStateOf("");private set
    private var generation=0
    private var conversion: Job?=null
    fun hint(value: String) {error=value}
    fun start(): Boolean {
        if(recording || busy) return false
        return runCatching{engine.start()}.fold({recording=true;error="";true},{error="میکروفون در دسترس نیست؛ دوباره امتحان کنید.";false})
    }
    fun finish() {
        if(!recording) return
        recording=false
        val file=runCatching{engine.stop()}.getOrNull()
        if(file==null) {error="صدا دریافت نشد؛ دوباره امتحان کنید.";return}
        busy=true
        val ticket=++generation
        conversion=scope.launch {
            try {
                val words=withContext(Dispatchers.IO){try {engine.transcribe(file)} finally {file.delete()}}
                ensureActive()
                if(ticket==generation) {
                    if(words.isBlank()) error="گفتاری تشخیص داده نشد؛ دوباره بگویید."
                    else recognized(words.replace('\n',' ').trim())
                }
            } catch(e: CancellationException) {throw e}
            catch(_:Exception) {if(ticket==generation) error="تبدیل گفتار ناموفق بود؛ دوباره امتحان کنید."}
            finally {file.delete();if(ticket==generation) busy=false}
        }.also{it.invokeOnCompletion{file.delete()}}
    }
    fun discard() {
        generation++;conversion?.cancel();conversion=null
        runCatching{engine.stop()?.delete()}
        recording=false;busy=false;error=""
    }
}

@Composable internal fun rememberFieldSpeech(recognized: (String)->Unit,stopped: ()->Unit={}): FieldSpeechSession {
    val context=LocalContext.current.applicationContext
    val factory=LocalFieldSpeechEngine.current
    val engine=remember(context,factory){factory(context)}
    val scope=rememberCoroutineScope()
    val latestRecognized by rememberUpdatedState(recognized)
    val latestStopped by rememberUpdatedState(stopped)
    val session=remember(engine,scope){FieldSpeechSession(engine,scope){latestRecognized(it)}}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle,session) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP){session.discard();latestStopped()}}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);session.discard()}
    }
    LaunchedEffect(session.recording){if(session.recording){delay(60_000);session.finish()}}
    return session
}
