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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

// Tests can deliver recognized words through the same field insertion and selection path.
internal val LocalDictationHandler=staticCompositionLocalOf<((String,(String)->Unit)->Unit)?>{null}

@Composable internal fun DictationButton(label: String,enabled: Boolean=true,recognized: (String)->Unit) {
    var open by remember{mutableStateOf(false)}
    val handler=LocalDictationHandler.current
    val mode=rememberDictationMode()
    if(mode!=DictationMode.TAP && handler==null) HoldDictationButton(label,enabled,recognized,{open=true})
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
    val mode=rememberDictationMode()
    val keyboard=LocalKeyboardSpeech.current
    val owner=remember{Any()}
    var focused by remember{mutableStateOf(false)}
    val latestInsert by rememberUpdatedState<(String)->Unit>(::insert)
    val latestEnabled by rememberUpdatedState(enabled)
    val deliver=remember{ {words: String->if(latestEnabled) latestInsert(words)} }
    val bar=mode==DictationMode.KEYBOARD && keyboard!=null
    DisposableEffect(keyboard,owner) {onDispose{keyboard?.blur(owner)}}
    SideEffect {
        if(bar && enabled && focused) keyboard?.focus(KeyboardSpeechTarget(owner,voiceLabel,deliver))
        else keyboard?.blur(owner)
    }
    val fieldModifier=modifier.onFocusChanged{state->
        focused=state.isFocused
        if(bar && enabled && focused) keyboard?.focus(KeyboardSpeechTarget(owner,voiceLabel,deliver)) else keyboard?.blur(owner)
    }
    val endIcon: (@Composable ()->Unit)?=if(bar) trailingIcon else ({Row(Modifier.padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {trailingIcon?.invoke();DictationButton(voiceLabel,enabled,::insert)}})
    OutlinedTextField(editing,{editing=it;onValueChange(it.text)},modifier=fieldModifier,label=label,placeholder=placeholder,
        enabled=enabled,isError=isError,singleLine=singleLine,minLines=minLines,maxLines=maxLines,leadingIcon=leadingIcon,
        trailingIcon=endIcon,shape=shape,textStyle=textStyle)
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

@Composable internal fun HoldDictationButton(label: String,enabled: Boolean,recognized: (String)->Unit,accessibleStart: ()->Unit,keyboard: KeyboardSpeechState?=null,owner: Any?=null,compact: Boolean=false) {
    val context=LocalContext.current
    val haptic=LocalHapticFeedback.current
    val hintOffset=with(LocalDensity.current){(-72).dp.roundToPx()}
    val session=rememberFieldSpeech(recognized)
    var accessibleRecording by remember{mutableStateOf(false)}
    val softwareKeyboard=LocalSoftwareKeyboardController.current
    val focus=LocalFocusManager.current
    DisposableEffect(keyboard,owner,session) {
        if(owner!=null) keyboard?.bind(owner,session::discard)
        onDispose{if(owner!=null) keyboard?.unbind(owner)}
    }
    SideEffect{if(owner!=null) keyboard?.work(owner,session.recording || session.busy)}
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
    val button: @Composable ()->Unit = {
        IconButton(onClick={if(keyboard==null) accessibleStart() else if(session.recording) {session.finish();accessibleRecording=false} else {accessibleRecording=true;start()}},enabled=enabled && !session.busy,
            colors=IconButtonDefaults.iconButtonColors(containerColor=if(session.recording) MaterialTheme.colorScheme.errorContainer else if(keyboard!=null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor=if(session.recording) MaterialTheme.colorScheme.onErrorContainer else if(keyboard!=null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary),
            modifier=Modifier.size(if(keyboard==null || compact) 48.dp else 56.dp).testTag(if(keyboard==null) "hold_mic_$label" else "keyboard_speech_mic")
                .then(if(keyboard==null) Modifier else Modifier.focusProperties{canFocus=false})
                .holdDictation(enabled && !session.busy,{accessibleRecording=false;start()},{release()},{cancel()})
                .semantics{stateDescription=if(session.recording) "در حال ضبط" else if(session.busy) "در حال تبدیل" else "نگه دارید و صحبت کنید"}) {
            if(session.busy) CircularProgressIndicator(Modifier.size(22.dp),strokeWidth=2.dp)
            else Icon(if(session.recording) Icons.Outlined.GraphicEq else Icons.Outlined.Mic,"ورود صوتی - $label")
        }
    }
    if(keyboard!=null) {
        Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh,tonalElevation=3.dp,modifier=Modifier.fillMaxWidth().testTag("keyboard_speech_bar").semantics{liveRegion=LiveRegionMode.Polite}) {
            Row(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(horizontal=12.dp,vertical=if(compact) 4.dp else 8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                button()
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text(label,style=if(compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("keyboard_speech_label"))
                    Text(if(accessibleRecording && session.recording) "برای پایان دوباره میکروفون را بزنید" else hint.ifBlank{"نگه دارید و صحبت کنید"},style=if(compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,maxLines=if(compact) 1 else 2,overflow=TextOverflow.Ellipsis)
                }
                IconButton(onClick={if(session.recording || session.busy || session.error.isNotBlank()) session.discard() else {focus.clearFocus();softwareKeyboard?.hide()}},modifier=Modifier.size(48.dp).focusProperties{canFocus=false}.testTag("keyboard_speech_cancel")) {
                    Icon(if(session.recording || session.busy || session.error.isNotBlank()) Icons.Outlined.Close else Icons.Outlined.KeyboardHide,if(session.recording || session.busy || session.error.isNotBlank()) "لغو ورود صوتی" else "بستن کیبورد")
                }
            }
        }
    } else Box {
        button()
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
