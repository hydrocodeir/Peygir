package ir.peygir.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File

@Composable fun CaptureInput(meetingId: Long?, recordings: List<Recording>, accepted: Set<String>, batchEnabled: Boolean, recordingId: Long?, busy: Boolean, onBusy: (Boolean)->Unit, onProposal: (Proposal)->Unit, refresh: ()->Unit, message: (String)->Unit, initialSource: String = "", batchChanged: (String)->Unit = {}, draftSession: String? = null, recordingUsed: (Long?)->Unit = {}) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var mode by rememberSaveable {mutableStateOf("write")}
    var text by rememberSaveable {mutableStateOf(if(batchEnabled && recordingId==null) initialSource else "")}
    var preparedRecording by rememberSaveable {mutableStateOf<Long?>(null)}
    var prepared by rememberSaveable {mutableStateOf(if(batchEnabled && recordingId==null) initialSource else "")}
    var applyRevision by rememberSaveable {mutableIntStateOf(0)}
    var showText by rememberSaveable {mutableStateOf(false)}
    var archive by rememberSaveable {mutableStateOf(false)}
    var help by rememberSaveable {mutableStateOf(false)}
    var archiveLimit by rememberSaveable {mutableIntStateOf(10)}
    var selected by rememberSaveable {mutableStateOf("")}
    var work by remember {mutableStateOf("")}
    var feedback by rememberSaveable {mutableStateOf("")}
    var recording by remember {mutableStateOf(false)}
    var elapsed by remember {mutableIntStateOf(0)}
    val capture=remember {AudioCapture(context.applicationContext)}
    val recordingMeeting=remember {longArrayOf(0)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latestRefresh by rememberUpdatedState(refresh)
    fun report(value: String) {feedback=value;message(value)}
    val latestMessage by rememberUpdatedState(::report)
    val latestProposal by rememberUpdatedState(onProposal)
    val available=remember(prepared,accepted,preparedRecording) {if(prepared.isBlank()) emptyList() else (if(preparedRecording!=null) listOfNotNull(VoiceCommands.extract(prepared)) else Extractor.extract(prepared)).filterNot{it.key() in accepted}}
    val locked=busy || recording || work.isNotEmpty()
    LaunchedEffect(recording,work) {onBusy(recording || work.isNotEmpty())}
    LaunchedEffect(prepared,accepted,preparedRecording,applyRevision) {
        available.firstOrNull()?.let {selected=it.key();latestProposal(it)}
    }
    fun persist(value: String) {text=value}
    fun prepare(value: String, fromRecording: Long? = null) {
        if(value.isBlank()) {report("متنی تشخیص داده نشد؛ می‌توانید بنویسید یا دوباره امتحان کنید.");return}
        persist(value);preparedRecording=fromRecording;prepared=value;recordingUsed(fromRecording);applyRevision++
        if(batchEnabled) batchChanged(if(fromRecording==null) value else "")
        if(fromRecording!=null && VoiceCommands.extract(value)==null) {showText=true;report("ابتدا «عنوان» و متن کار، سپس «مسئول» و نام شخص را بگویید. تاریخ را از تقویم انتخاب کنید.");return}
        if(fromRecording!=null) feedback="عنوان و مسئول در فرم وارد شدند؛ آن‌ها را بررسی و سررسید را انتخاب کنید."
        if(fromRecording==null && Extractor.extract(value).isEmpty()) {showText=true;report("متن را اصلاح کنید و «استخراج مصوبات» را بزنید.")}
    }
    fun recognize(r: Recording) {
        if(work.isNotEmpty()) return
        mode="voice"
        if(r.transcript.isNotBlank()) {prepare(r.transcript,r.id);return}
        work="در حال تبدیل صدا به متن…"
        scope.launch {
            try {
                val result=withContext(Dispatchers.IO) {
                    OfflineText.transcribe(context,r.path).also {value->
                        Store.get(context).transcript(r.id,value)
                    }
                }
                prepare(result,r.id);refresh()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {report("تبدیل صدا ناموفق بود؛ فایل محفوظ است و می‌توانید دوباره امتحان کنید.")}
            finally {work=""}
        }
    }
    fun finishCapture(convert: Boolean = true) {
        if(!recording) return
        recording=false;work="در حال ذخیرهٔ صدا…"
        scope.launch {
            try {
                val saved=withContext(Dispatchers.IO + NonCancellable) {capture.stop(recordingMeeting[0].takeIf{it>0})?.let{r->if(draftSession!=null) Store.get(context).stageRecording(r,draftSession) else r.copy(id=Store.get(context).saveRecording(r))}}
                refresh();work=""
                if(saved!=null && convert) recognize(saved)
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {report("ذخیرهٔ صدا ناموفق بود.")}
            finally {if(work=="در حال ذخیرهٔ صدا…") work=""}
        }
    }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP && capture.active) finishCapture(false)}
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            Reminders.background {
                val saved=runCatching {capture.stop(recordingMeeting[0].takeIf{it>0})?.let{r->if(draftSession!=null) Store.get(context).stageRecording(r,draftSession)?.id else Store.get(context).saveRecording(r)}}
                if(saved.getOrNull()!=null || saved.isFailure) android.os.Handler(android.os.Looper.getMainLooper()).post {latestRefresh();if(saved.isFailure) latestMessage("ذخیرهٔ صدا ناموفق بود.")}
            }
        }
    }
    LaunchedEffect(recording) {if(recording) {elapsed=0;while(isActive){delay(1000);elapsed++}}}
    LaunchedEffect(recordingId) {if(recordingId!=null) mode="voice"}
    fun startCapture() {try {feedback="";capture.start();recordingMeeting[0]=meetingId ?: 0;recording=true} catch(_:Exception){report("میکروفون در دسترس نیست؛ مجوز آن را بررسی کنید.")}}
    val microphone=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {if(it) startCapture() else report("برای ضبط صدا، مجوز میکروفون لازم است.")}
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf(Triple("write","نوشتن",Icons.Outlined.Edit),Triple("voice","صدا",Icons.Outlined.MicNone)).forEach {(value,label,icon)->
                FilterChip(selected=(if(mode=="voice") "voice" else "write")==value,onClick={mode=value},enabled=!locked,modifier=Modifier.weight(1f).testTag("input_$value"),label={Text(label)},leadingIcon={Icon(icon,null,Modifier.size(18.dp))})
            }
        }
        if(mode=="voice") {
            Text("بگویید: «عنوان، تهیه گزارش فروش؛ مسئول، آقای احمدی»",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.primary)
            TextButton(onClick={help=!help}){Text("راهنمای گفتار")}
            if(help) Text("ابتدا عنوان و متن کار، سپس مسئول و نام شخص را بگویید؛ تاریخ را از تقویم انتخاب کنید. پس از ذخیره، فایل خام حذف و متن حفظ می‌شود.",style=MaterialTheme.typography.bodySmall)
            recordings.find{it.id==recordingId}?.let {r->RecordingCard(r,null,locked,{recognize(it)},{prepare(r.transcript,r.id)},{scope.launch {try {withContext(Dispatchers.IO){Store.get(context).deleteRecording(r.id);File(r.path).delete()};refresh()} catch(_:Exception){message("حذف فایل ناموفق بود.")}}})}
            FilledTonalButton(onClick={if(recording) finishCapture() else if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) startCapture() else microphone.launch(Manifest.permission.RECORD_AUDIO)},enabled=!busy && work.isEmpty(),modifier=Modifier.fillMaxWidth()) {
                Icon(if(recording) Icons.Outlined.StopCircle else Icons.Outlined.MicNone,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(if(recording) "پایان ضبط و تبدیل - ${elapsed.fa()} ثانیه" else "ضبط مصوبه")
            }
        }
        if(work.isNotEmpty()) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {CircularProgressIndicator(Modifier.size(18.dp),strokeWidth=2.dp);Text(work,style=MaterialTheme.typography.bodySmall)}
        if(feedback.isNotBlank()) Surface(color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.small,modifier=Modifier.fillMaxWidth().testTag("input_feedback")) {Text(feedback,Modifier.padding(12.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSecondaryContainer)}
        if(available.isNotEmpty() && (mode!="voice" || preparedRecording!=null)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)){if(mode!="voice" && available.size>1) CountLabel("پیشنهادها",available.size);Text(if(mode=="voice") "عنوان و مسئول آماده‌اند؛ سررسید را انتخاب و ذخیره کنید." else if(available.size==1) "پیشنهاد آماده است؛ اصلاح و ذخیره کنید." else "هر مورد را بررسی و ذخیره کنید.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)}
                if(available.size>1) TextButton(onClick={val i=available.indexOfFirst{it.key()==selected};available[(i+1)%available.size].let{selected=it.key();onProposal(it)}}){Text("مورد بعدی")}
            }
        }
        if(showText && (mode!="voice" || preparedRecording!=null)) {
            OutlinedTextField(text,{persist(it)},label={Text("متن ورودی")},placeholder={Text(if(mode=="voice") "عنوان، تهیه گزارش فروش؛ مسئول، آقای احمدی" else "هر مصوبه در یک خط؛ مسئول: احمدی؛ مهلت: ۱۴۰۵/۰۸/۱۵")},minLines=3,maxLines=7,modifier=Modifier.fillMaxWidth().testTag("capture_text"),enabled=!locked)
            Row {TextButton(onClick={showText=false}){Text("جمع کردن متن")};Spacer(Modifier.weight(1f));TextButton(onClick={prepare(text,if(mode=="voice") preparedRecording else null)},enabled=text.isNotBlank() && !locked){Text(if(mode=="voice") "اعمال متن گفتار" else "استخراج مصوبات")}}
        } else Row(verticalAlignment=Alignment.CenterVertically) {
            if(mode!="voice" || preparedRecording!=null) TextButton(onClick={showText=true},enabled=!locked,contentPadding=PaddingValues(0.dp)){Text(if(mode=="voice") "بازبینی متن گفتار" else if(text.isBlank()) "ورود چند مصوبه از متن" else "بازبینی متن ورودی",style=MaterialTheme.typography.labelMedium)}
            Spacer(Modifier.weight(1f))
            if(recordings.isNotEmpty()) TextButton(onClick={archive=!archive},enabled=!locked,contentPadding=PaddingValues(0.dp)){Text(if(archive) "بستن فایل‌ها" else "صداهای ذخیره‌شده",style=MaterialTheme.typography.labelMedium)}
        }
        if(archive) {
            val files=recordings.filter{it.id!=recordingId && (meetingId==null || it.meetingId==meetingId)}
            files.take(archiveLimit).forEach {r->RecordingCard(r,null,locked,{recognize(it)},{prepare(r.transcript,r.id)},{scope.launch {try {withContext(Dispatchers.IO){Store.get(context).deleteRecording(r.id);File(r.path).delete()};refresh()} catch(_:Exception){message("حذف فایل ناموفق بود.")}}})}
            if(files.size>archiveLimit) TextButton(onClick={archiveLimit+=10}) {Text("نمایش صداهای بیشتر")}
        }
    }
}
