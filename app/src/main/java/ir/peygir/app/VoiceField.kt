package ir.peygir.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

// Tests can deliver recognized words through the same field insertion and selection path.
internal val LocalDictationHandler=staticCompositionLocalOf<((String,(String)->Unit)->Unit)?>{null}

@Composable internal fun DictationButton(label: String,enabled: Boolean=true,recognized: (String)->Unit) {
    var open by remember{mutableStateOf(false)}
    val handler=LocalDictationHandler.current
    val mode=rememberDictationMode()
    if(mode==DictationMode.HOLD && handler==null) HoldDictationButton(label,enabled,recognized,{open=true})
    else IconButton(onClick={if(handler==null) open=true else handler(label,recognized)},enabled=enabled,modifier=Modifier.size(48.dp)){Icon(Icons.Outlined.Mic,"ورود صوتی - $label")}
    if(open) FieldDictation(label,{open=false}){recognized(it);open=false}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ChoiceRow(choices: Map<String,String>,selected: String,label: String,choose: (String)->Unit) {
    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth().testTag("choice_$label")) {
        choices.forEach{(key,text)->FilterChip(selected==key,{choose(key)},label={Text(text)},modifier=Modifier.then(if(label=="وضعیت مصوبه") Modifier.testTag("task_status_$key") else Modifier))}
    }
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
        trailingIcon={Row(Modifier.padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {trailingIcon?.invoke();DictationButton(voiceLabel,enabled,::insert)}},shape=shape,textStyle=textStyle)
}

/** Consume only this button's touch stream; IconButton keeps keyboard and TalkBack actions. */
private fun Modifier.holdDictation(enabled: Boolean,start: ()->Unit,release: ()->Unit,cancel: ()->Unit)=pointerInput(enabled) {
    if(enabled) awaitEachGesture {
        val down=awaitFirstDown(pass=PointerEventPass.Initial)
        down.consume()
        var released=false
        try {
            start()
            while(true) {
                val event=awaitPointerEvent(PointerEventPass.Initial)
                val pointer=event.changes.firstOrNull{it.id==down.id} ?: break
                val outside=pointer.position.x<0 || pointer.position.y<0 || pointer.position.x>size.width || pointer.position.y>size.height
                val cancelled=pointer.isConsumed || outside || event.changes.any{it.id!=down.id && it.pressed}
                pointer.consume()
                if(cancelled) break
                if(!pointer.pressed) {released=true;break}
            }
        } finally {if(released) release() else cancel()}
    }
}

@Composable private fun HoldDictationButton(label: String,enabled: Boolean,recognized: (String)->Unit,accessibleStart: ()->Unit) {
    val context=LocalContext.current
    val haptic=LocalHapticFeedback.current
    val hintOffset=with(LocalDensity.current){(-72).dp.roundToPx()}
    val session=rememberFieldSpeech(recognized)
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {granted->
        session.hint(if(granted) "میکروفون آماده است؛ دوباره نگه دارید و صحبت کنید." else "برای ورود صوتی، مجوز میکروفون لازم است.")
    }
    val start by rememberUpdatedState<()->Unit>({
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) {
            if(session.start()) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else permission.launch(Manifest.permission.RECORD_AUDIO)
    })
    val release by rememberUpdatedState<()->Unit>({session.finish()})
    val cancel by rememberUpdatedState<()->Unit>({if(session.recording) session.discard()})
    val hint=when {session.recording->"صحبت کنید - برای تبدیل رها کنید";session.busy->"در حال تبدیل گفتار…";else->session.error}
    LaunchedEffect(session.error){if(session.error.isNotBlank()){delay(6000);session.hint("")}}
    DisposableEffect(enabled){onDispose{session.discard()}}
    Box {
        IconButton(onClick=accessibleStart,enabled=enabled && !session.busy,
            colors=IconButtonDefaults.iconButtonColors(containerColor=if(session.recording) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor=if(session.recording) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.primary),
            modifier=Modifier.size(48.dp).testTag("hold_mic_$label")
                .holdDictation(enabled && !session.busy,{start()},{release()},{cancel()})
                .semantics{stateDescription=if(session.recording) "در حال ضبط" else if(session.busy) "در حال تبدیل" else "نگه دارید و صحبت کنید"}) {
            if(session.busy) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
            else Icon(if(session.recording) Icons.Outlined.GraphicEq else Icons.Outlined.Mic,"ورود صوتی - $label")
        }
        if(hint.isNotBlank()) Popup(alignment=Alignment.TopCenter,offset=IntOffset(0,hintOffset),properties=PopupProperties(focusable=false)) {
            Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.inverseSurface,tonalElevation=4.dp,modifier=Modifier.widthIn(max=280.dp).semantics{liveRegion=LiveRegionMode.Polite}) {
                Column(Modifier.padding(horizontal=16.dp,vertical=10.dp)) {
                    Text(hint,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.inverseOnSurface)
                    if(session.recording) Text("برای لغو، انگشت را از دکمه بیرون ببرید",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.inverseOnSurface)
                }
            }
        }
    }
}

@Composable private fun FieldDictation(label: String,dismiss: ()->Unit,recognized: (String)->Unit) {
    val context=LocalContext.current.applicationContext
    val session=rememberFieldSpeech(recognized,dismiss)
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){if(it) session.start() else session.hint("برای ورود صوتی، مجوز میکروفون لازم است.")}
    LaunchedEffect(Unit){if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) session.start() else permission.launch(Manifest.permission.RECORD_AUDIO)}
    AlertDialog(onDismissRequest={session.discard();dismiss()},icon={Icon(Icons.Outlined.Mic,null)},
        title={Text("ورود صوتی - $label")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text(if(session.busy) "در حال تبدیل گفتار روی گوشی…" else if(session.recording) "صحبت کنید، سپس «پایان» را بزنید. متن پیش از ذخیره قابل ویرایش است." else session.error)
            if(session.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }},confirmButton={TextButton(onClick={if(session.recording) session.finish() else if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED) session.start() else permission.launch(Manifest.permission.RECORD_AUDIO)},enabled=!session.busy){Text(if(session.recording) "پایان" else "تلاش دوباره")}},
        dismissButton={TextButton(onClick={session.discard();dismiss()}){Text("انصراف")}})
}
