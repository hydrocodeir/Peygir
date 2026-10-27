package ir.peygir.app

import android.app.TimePickerDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.*

@Composable fun ChangeDueDialog(task: Resolution, kind: DueChangeKind, busy: Boolean, dismiss: ()->Unit, feedback: String = "", apply: (Long,String,Long?)->Unit) {
    val context=LocalContext.current
    var day by rememberSaveable {mutableLongStateOf(maxOf(task.due,LocalDate.now().toEpochDay())+1)}
    var reason by rememberSaveable {mutableStateOf("")}
    var remind by rememberSaveable {mutableStateOf(true)}
    var customTime by rememberSaveable {mutableStateOf<String?>(null)}
    val defaultAt=remember(day) {DefaultFollowUp.forDue(day)}
    val selectedTime=customTime?.let(LocalTime::parse) ?: defaultAt?.let{Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalTime()} ?: LocalTime.of(9,0)
    var picker by rememberSaveable {mutableStateOf(false)}
    var error by rememberSaveable {mutableStateOf("")}
    FullEditor(kind.label,busy,dismiss,{
        val at=if(!remind) null else if(customTime==null) DefaultFollowUp.forDue(day) else LocalDate.ofEpochDay(day).minusDays(1).atTime(selectedTime).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        error=when {remind && at==null->"زمان پیش‌فرض گذشته است؛ زمان پیگیری را انتخاب کنید یا یادآوری را بردارید.";day<=task.due || day<LocalDate.now().toEpochDay()->"موعد جدید باید پس از موعد قبلی و از امروز به بعد باشد.";at!=null && at<=System.currentTimeMillis()->"زمان یادآوری باید در آینده باشد.";else->""}
        if(error.isBlank()) apply(day,reason,at)
    },feedback=feedback,saveLabel="ثبت تغییر موعد") {
        Text(task.title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold)
        Text("موعد فعلی: ${Solar.label(LocalDate.ofEpochDay(task.due))}",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={picker=true},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("موعد جدید - ${Solar.label(LocalDate.ofEpochDay(day))}")}
        Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf(1L to "یک روز",3L to "سه روز",7L to "یک هفته").forEach{(days,label)->OutlinedButton(onClick={day=maxOf(task.due,LocalDate.now().toEpochDay())+days;error=""},enabled=!busy,contentPadding=PaddingValues(horizontal=10.dp)){Text(label)}}
        }
        OutlinedTextField(reason,{reason=it},label={Text("دلیل (اختیاری)")},modifier=Modifier.fillMaxWidth(),maxLines=3,enabled=!busy)
        Row(verticalAlignment=Alignment.CenterVertically) {Text("پیگیری یک روز قبل از موعد جدید",Modifier.weight(1f));Switch(remind,{remind=it},enabled=!busy)}
        if(remind) TextButton(onClick={TimePickerDialog(context,{_,h,m->customTime=LocalTime.of(h,m).toString()},selectedTime.hour,selectedTime.minute,true).show()},enabled=!busy) {Text("ساعت یادآوری: ${selectedTime.toString().fa()}")}
        Text("یادآوری‌های ارسال‌نشده با زمان تازه جایگزین می‌شوند. موعد قبلی و دلیل تغییر در تاریخچه می‌مانند.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    }
    if(picker) SolarPicker(LocalDate.ofEpochDay(day),{picker=false}) {day=it.toEpochDay();picker=false;error=""}
}

@Composable fun DueHistoryList(changes: List<DueChange>) {
    changes.forEach {change->
        Column(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
            val at=Instant.ofEpochMilli(change.changedAt).atZone(ZoneId.systemDefault())
            Text("${change.kind.label} - ${Solar.numeric(at.toLocalDate())} - ${at.toLocalTime().withSecond(0).withNano(0).toString().fa()}",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            Text("از ${Solar.label(LocalDate.ofEpochDay(change.oldDue))}\nبه ${Solar.label(LocalDate.ofEpochDay(change.newDue))}",style=MaterialTheme.typography.bodySmall)
            if(change.reason.isNotBlank()) Text("دلیل: ${change.reason}",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
        }
    }
}
