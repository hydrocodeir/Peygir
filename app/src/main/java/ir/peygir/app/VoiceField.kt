package ir.peygir.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File

// Tests can deliver recognized words through the same field insertion and selection path.
internal val LocalDictationHandler=staticCompositionLocalOf<((String,(String)->Unit)->Unit)?>{null}

@Composable internal fun DictationButton(label: String,enabled: Boolean=true,recognized: (String)->Unit) {
    var open by remember{mutableStateOf(false)}
    val handler=LocalDictationHandler.current
    IconButton(onClick={if(handler==null) open=true else handler(label,recognized)},enabled=enabled){Icon(Icons.Outlined.Mic,"ورود صوتی - $label")}
    if(open) FieldDictation(label,{open=false}){recognized(it);open=false}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun VoiceChoiceRow(choices: Map<String,String>,selected: String,label: String,choose: (String)->Unit) {
    var query by remember{mutableStateOf("")}
    val matches=choices.filterValues{PeopleSuggestions.matches(it,query)}
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        DictationButton(label){query=it}
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.weight(1f)) {
            matches.forEach{(key,text)->FilterChip(selected==key,{query="";choose(key)},label={Text(text)},modifier=Modifier.then(if(label=="وضعیت مصوبه") Modifier.testTag("task_status_$key") else Modifier))}
        }
    }
    if(query.isNotBlank()) TextButton(onClick={query=""}){Text(if(matches.isEmpty()) "گزینه‌ای پیدا نشد - نمایش همه" else "نمایش همهٔ گزینه‌ها")}
}

internal fun insertDictation(value: TextFieldValue,words: String): TextFieldValue {
    val start=value.selection.min.coerceIn(0,value.text.length)
    val end=value.selection.max.coerceIn(start,value.text.length)
    val before=value.text.substring(0,start);val after=value.text.substring(end)
    val inserted=(if(before.isNotEmpty() && !before.last().isWhitespace()) " " else "")+words.trim()+(if(after.isNotEmpty() && !after.first().isWhitespace()) " " else "")
    return TextFieldValue(before+inserted+after,TextRange(before.length+inserted.length))
}

/** One text field for typing or offline dictation. Speech never submits a form. */
@Composable fun VoiceTextField(value: String,onValueChange: (String)->Unit,modifier: Modifier=Modifier,
    label: @Composable (()->Unit)?=null,placeholder: @Composable (()->Unit)?=null,
    enabled: Boolean=true,isError: Boolean=false,singleLine: Boolean=false,minLines: Int=1,maxLines: Int=Int.MAX_VALUE,
    leadingIcon: @Composable (()->Unit)?=null,trailingIcon: @Composable (()->Unit)?=null,
    shape: androidx.compose.ui.graphics.Shape=MaterialTheme.shapes.extraSmall,textStyle: TextStyle=LocalTextStyle.current,
    voiceLabel: String="این فیلد") {
    var editing by rememberSaveable(stateSaver=TextFieldValue.Saver){mutableStateOf(TextFieldValue(value,TextRange(value.length)))}
    if(editing.text!=value) editing=TextFieldValue(value,TextRange(value.length))
    val latestChange by rememberUpdatedState(onValueChange)
    fun insert(words: String) {val updated=insertDictation(editing,words);editing=updated;latestChange(updated.text)}
    OutlinedTextField(editing,{editing=it;onValueChange(it.text)},modifier=modifier,label=label,placeholder=placeholder,
        enabled=enabled,isError=isError,singleLine=singleLine,minLines=minLines,maxLines=maxLines,leadingIcon=leadingIcon,
        trailingIcon={Row {trailingIcon?.invoke();DictationButton(voiceLabel,enabled,::insert)}},shape=shape,textStyle=textStyle)
}

@Composable private fun FieldDictation(label: String,dismiss: ()->Unit,recognized: (String)->Unit) {
    val context=LocalContext.current.applicationContext
    val capture=remember {AudioCapture(context,temporary=true)}
    val scope=rememberCoroutineScope()
    var recording by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    val latestRecognized by rememberUpdatedState(recognized)
    val latestDismiss by rememberUpdatedState(dismiss)
    fun discard() {runCatching{capture.stop(null)?.let{File(it.path).delete()}};recording=false}
    fun start() {runCatching{capture.start()}.onSuccess{recording=true;error=""}.onFailure{error=it.message ?: "میکروفون در دسترس نیست."}}
    fun finish() {
        recording=false
        val audio=runCatching{capture.stop(null)}.getOrElse{error=it.message.orEmpty();null} ?: return
        busy=true
        scope.launch {
            try {
                val words=withContext(Dispatchers.IO){try {OfflineText.transcribe(context,audio.path)} finally {File(audio.path).delete()}}
                if(words.isBlank()) error="گفتاری تشخیص داده نشد؛ دوباره بگویید." else latestRecognized(words.replace('\n',' ').trim())
            } catch(e: CancellationException) {throw e} catch(_:Exception) {error="تبدیل گفتار ناموفق بود؛ دوباره امتحان کنید."}
            finally {busy=false;File(audio.path).delete()}
        }
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it) start() else error="برای ورود صوتی، مجوز میکروفون لازم است."}
    LaunchedEffect(Unit){if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) start() else permission.launch(Manifest.permission.RECORD_AUDIO)}
    LaunchedEffect(recording){if(recording){delay(60_000);finish()}}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP && capture.active){discard();latestDismiss()}}
        lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);discard()}
    }
    AlertDialog(onDismissRequest={discard();dismiss()},icon={Icon(Icons.Outlined.Mic,null)},
        title={Text("ورود صوتی - $label")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(if(busy) "در حال تبدیل گفتار روی گوشی…" else if(recording) "صحبت کنید، سپس «پایان» را بزنید. متن پیش از ذخیره قابل ویرایش است." else error)
            if(recording && error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }},confirmButton={TextButton(onClick={if(recording) finish() else if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) start() else permission.launch(Manifest.permission.RECORD_AUDIO)},enabled=!busy){Text(if(recording) "پایان" else "تلاش دوباره")}},
        dismissButton={TextButton(onClick={discard();dismiss()}){Text("انصراف")}})
}
