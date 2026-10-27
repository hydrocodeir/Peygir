package ir.peygir.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File
import java.time.*

@Composable fun RecordingCard(r: Recording, meeting: String?, busy: Boolean, transcribe: (Recording)->Unit, useText: ()->Unit, delete: ()->Unit) {
    val scope=rememberCoroutineScope()
    var player by remember {mutableStateOf<MediaPlayer?>(null)}
    var playing by remember {mutableStateOf(false)}
    var confirm by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {player?.release();player=null;playing=false}}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);player?.release()}
    }
    val dt=Instant.ofEpochMilli(r.created).atZone(ZoneId.systemDefault())
    OutlinedCard {Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text("${Solar.label(dt.toLocalDate())} - ${dt.toLocalTime().withSecond(0).withNano(0).toString().fa()}",style=MaterialTheme.typography.titleSmall);Text("${r.seconds.fa()} ثانیه${meeting?.let{" - $it"}.orEmpty()}",style=MaterialTheme.typography.labelSmall)}
            IconButton(enabled=!busy,onClick={
                if(playing) {player?.release();player=null;playing=false} else {
                    error=""
                    scope.launch {
                        val p=MediaPlayer()
                        try {
                            withContext(Dispatchers.IO) {p.setDataSource(r.path);p.prepare()}
                            p.setOnCompletionListener {it.release();player=null;playing=false}
                            p.setOnErrorListener {mp,_,_->mp.release();player=null;playing=false;error="پخش فایل ناموفق بود";true}
                            player=p;p.start();playing=true
                        } catch(_:Exception) {p.release();error="پخش فایل ناموفق بود"}
                    }
                }
            }) {Icon(if(playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,if(playing) "توقف پخش" else "پخش صدا")}
        }
        if(r.transcript.isNotBlank()) Text(r.transcript,style=MaterialTheme.typography.bodySmall,maxLines=4,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        Row {
            TextButton(enabled=!busy && !playing,onClick={transcribe(r)}) {Text("تبدیل به متن")}
            if(r.transcript.isNotBlank()) TextButton(enabled=!busy,onClick=useText) {Text("افزودن متن")}
            Spacer(Modifier.weight(1f))
            IconButton(onClick={confirm=true},enabled=!busy && !playing) {Icon(Icons.Outlined.DeleteOutline,"حذف فایل صوتی")}
        }
    }}
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("حذف فایل صوتی؟")},text={Text("فایل صوتی و متن ذخیره‌شدهٔ آن حذف می‌شوند.")},confirmButton={TextButton(onClick={confirm=false;delete()}){Text("حذف")}},dismissButton={TextButton(onClick={confirm=false}){Text("انصراف")}})
}
