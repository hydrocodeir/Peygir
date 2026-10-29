package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal data class KeyboardSpeechTarget(val owner: Any,val label: String,val insert: (String)->Unit)

/** Scoped to one window. Losing the editor cancels audio before another editor can receive text. */
@Stable internal class KeyboardSpeechState {
    var target by mutableStateOf<KeyboardSpeechTarget?>(null);private set
    var working by mutableStateOf(false);private set
    private var cancelOwner: Any?=null
    private var cancel: (()->Unit)?=null
    var canDeliver: ()->Boolean={true}
    fun focus(value: KeyboardSpeechTarget) {
        if(target?.owner!==value.owner) {cancel?.invoke();working=false}
        if(target!=value) target=value
    }
    fun blur(owner: Any) {
        if(target?.owner===owner) {cancel?.invoke();target=null;working=false}
    }
    fun bind(owner: Any,discard: ()->Unit) {cancelOwner=owner;cancel=discard}
    fun unbind(owner: Any) {if(cancelOwner===owner){cancel=null;cancelOwner=null;working=false}}
    fun work(owner: Any,value: Boolean) {if(target?.owner===owner && cancelOwner===owner) working=value}
    fun cancelActive() {cancel?.invoke()}
    fun deliver(owner: Any,words: String) {if(canDeliver()) target?.takeIf{it.owner===owner}?.insert?.invoke(words)}
}

@Composable internal fun LinkedMeetingSearch(meetings: List<Meeting>,query: String,change: (String)->Unit,dismiss: ()->Unit,choose: (Long)->Unit) {
    val focus=remember{FocusRequester()}
    val shown=remember(meetings,query){meetings.filter{PeopleSuggestions.matches(it.title,query)}.sortedWith(MeetingSchedule.order)}
    val list=rememberLazyListState()
    LaunchedEffect(query){list.scrollToItem(0)}
    Dialog(onDismissRequest=dismiss,properties=speechDialogProperties()) {
        KeyboardSpeechHost {
            Surface(Modifier.fillMaxSize()) {Column(Modifier.safeDrawingPadding().imePadding().testTag("linked_meeting_search")) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=dismiss){Icon(Icons.Outlined.Close,"بستن انتخاب جلسه")}
                    Text("انتخاب جلسهٔ مرتبط",style=MaterialTheme.typography.titleMedium)
                }
                VoiceTextField(query,change,voiceLabel="جست‌وجوی جلسهٔ مرتبط",label={Text("جست‌وجوی جلسه")},singleLine=true,
                    modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).focusRequester(focus).testTag("linked_meeting_query"))
                TextButton(onClick={choose(0)},modifier=Modifier.padding(horizontal=8.dp)){Text("بدون جلسه")}
                CountLabel("جلسات",shown.size,Modifier.padding(horizontal=16.dp))
                LazyColumn(Modifier.weight(1f).testTag("linked_meeting_results"),state=list,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(shown.isEmpty()) item{Text("جلسه‌ای پیدا نشد.")}
                    items(shown,key={it.id}) {m->
                        OutlinedCard(onClick={choose(m.id)},modifier=Modifier.fillMaxWidth().testTag("linked_meeting_${m.id}")){Text(m.title,Modifier.padding(16.dp))}
                    }
                }
            }}
            LaunchedEffect(Unit){focus.requestFocus()}
        }
    }
}
internal val LocalKeyboardSpeech=staticCompositionLocalOf<KeyboardSpeechState?>{null}
// Tests inject only the IME insets; production always uses the current window's real keyboard.
internal val LocalSpeechImeInsets=staticCompositionLocalOf<WindowInsets?>{null}
internal val LocalSpeechWindowFocused=staticCompositionLocalOf<Boolean?>{null}

@Composable internal fun speechDialogProperties()=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=rememberDictationMode()!=DictationMode.KEYBOARD)

/** Install inside each editor/search Dialog, so its keyboard and focus belong to the same window. */
@Composable internal fun KeyboardSpeechHost(enabled: Boolean=true,content: @Composable ()->Unit) {
    val mode=rememberDictationMode()
    if(mode!=DictationMode.KEYBOARD) {content();return}
    val state=remember{KeyboardSpeechState()}
    val ime=LocalSpeechImeInsets.current ?: WindowInsets.ime
    val window=LocalWindowInfo.current
    val focusOverride=LocalSpeechWindowFocused.current
    val density=LocalDensity.current
    val active=focusOverride ?: window.isWindowFocused
    val visible=enabled && active && ime.getBottom(density)>0
    SideEffect{state.canDeliver={enabled && (focusOverride ?: window.isWindowFocused) && ime.getBottom(density)>0}}
    DisposableEffect(state){onDispose{state.cancelActive()}}
    CompositionLocalProvider(LocalKeyboardSpeech provides state) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(ime).testTag("keyboard_speech_host")) {
        val compact=maxHeight<240.dp
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()){content()}
            val target=state.target
            if(visible && target!=null) key(target.owner) {
                HoldDictationButton(target.label,true,{state.deliver(target.owner,it)},{},keyboard=state,owner=target.owner,compact=compact)
            }
        }
        }
    }
}
