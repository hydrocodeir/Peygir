package ir.peygir.app

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun followUpTime(at: Long): String {
    val date=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
    return "${Solar.label(date.toLocalDate())} • ${date.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm",Locale.ROOT)).fa()}"
}

@Composable fun FollowUpPanel(notes: List<FollowUp>, busy: Boolean, save: (String,()->Unit)->Unit) {
    var writing by rememberSaveable {mutableStateOf(false)}
    var text by rememberSaveable {mutableStateOf("")}
    var expanded by rememberSaveable {mutableStateOf(false)}
    var visible by rememberSaveable {mutableIntStateOf(5)}
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("نتیجهٔ پیگیری",Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
            if(!writing) TextButton(onClick={writing=true},enabled=!busy) {Icon(Icons.Outlined.AddComment,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("ثبت نتیجهٔ پیگیری")}
        }
        notes.firstOrNull()?.let {latest->
            Text(latest.text,style=MaterialTheme.typography.bodyMedium)
            Text("آخرین پیگیری: ${followUpTime(latest.created)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(writing) {
            OutlinedTextField(text,{text=it.take(1000)},label={Text("نتیجهٔ کوتاه پیگیری")},placeholder={Text("مثلاً منتظر تأیید مالی است")},modifier=Modifier.fillMaxWidth(),maxLines=3,enabled=!busy)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick={save(text) {text="";writing=false}},enabled=!busy && text.isNotBlank()) {Text("ثبت نتیجه")}
                TextButton(onClick={text="";writing=false},enabled=!busy) {Text("انصراف")}
            }
        }
        if(notes.size>1) {
            TextButton(onClick={expanded=!expanded},contentPadding=PaddingValues(0.dp)) {Text(if(expanded) "بستن سوابق پیگیری" else "سوابق پیگیری (${notes.size.fa()})")}
            if(expanded) {
                notes.drop(1).take(visible).forEach {note->
                    HorizontalDivider();Text(note.text,style=MaterialTheme.typography.bodyMedium)
                    Text(followUpTime(note.created),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(notes.size-1>visible) TextButton(onClick={visible+=10}) {Text("نمایش بیشتر")}
            }
        }
    }}
}

@Composable fun OwnerTasksDialog(owner: String, tasks: List<Resolution>, dismiss: ()->Unit) {
    var onlyLate by rememberSaveable {mutableStateOf(false)}
    val today=LocalDate.now().toEpochDay()
    val open=remember(tasks,owner) {Owners.openTasks(tasks,owner)}
    val late=open.count{it.due<today}
    val shown=if(onlyLate) open.filter{it.due<today} else open
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().testTag("owner_tasks")) {Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=dismiss) {Icon(Icons.Outlined.Close,"بستن مصوبات مسئول")}
                Column {Text(owner,style=MaterialTheme.typography.titleLarge);Text("${open.size.fa()} مصوبهٔ باز • ${late.fa()} معوق",style=MaterialTheme.typography.labelMedium)}
            }
            Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(!onlyLate,{onlyLate=false},label={Text("همهٔ باز")})
                FilterChip(onlyLate,{onlyLate=true},label={Text("معوق")})
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(shown.isEmpty()) item {Text(if(onlyLate) "این مسئول مصوبهٔ معوق ندارد." else "این مسئول مصوبهٔ باز ندارد.",style=MaterialTheme.typography.bodyMedium)}
                items(shown,key={it.id}) {task->
                    OutlinedCard(Modifier.fillMaxWidth().testTag("owner_task_${task.id}")) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(task.title,style=MaterialTheme.typography.titleSmall)
                        Text("سررسید: ${Solar.label(LocalDate.ofEpochDay(task.due))}",style=MaterialTheme.typography.bodySmall,color=if(task.due<today) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${task.status.label} • اولویت ${task.priority.label}",style=MaterialTheme.typography.labelSmall)
                    }}
                }
            }
        }}
    }
}
