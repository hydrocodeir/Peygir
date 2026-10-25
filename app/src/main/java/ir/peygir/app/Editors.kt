package ir.peygir.app

import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.*
import org.json.JSONObject

fun Resolution.encode(): String = JSONObject().apply { put("id",id); put("meeting",meetingId); put("title",title);put("description",description);put("owner",owner);put("due",due);put("priority",priority.name);put("status",status.name);put("delays",delayCount) }.toString()
fun decodeTask(s: String) = JSONObject(s).let { Resolution(it.getLong("id"),if(it.isNull("meeting")) null else it.getLong("meeting"),it.getString("title"),it.getString("description"),it.getString("owner"),it.getLong("due"),Priority.valueOf(it.getString("priority")),Status.valueOf(it.getString("status")),it.optInt("delays",0)) }

@Composable fun SolarPicker(initial: LocalDate, dismiss: () -> Unit, choose: (LocalDate) -> Unit) {
    var anchor by rememberSaveable { mutableStateOf(Solar.monthStart(initial).toEpochDay()) }
    var picked by rememberSaveable { mutableStateOf(initial.toEpochDay()) }
    var manual by rememberSaveable { mutableStateOf(Solar.numeric(initial)) }
    var error by rememberSaveable { mutableStateOf(false) }
    val month=LocalDate.ofEpochDay(anchor)
    val next=Solar.nextMonth(month)
    val parts=Solar.parts(month)
    val leading=(month.dayOfWeek.value+1)%7
    val days=java.time.temporal.ChronoUnit.DAYS.between(month,next).toInt()
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
      Box(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),contentAlignment=Alignment.Center) {
       Surface(Modifier.widthIn(max=400.dp).fillMaxWidth().height(640.dp),shape=RoundedCornerShape(28.dp),color=MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
         Text("انتخاب تاریخ شمسی",style=MaterialTheme.typography.titleLarge)
         Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick={ anchor=Solar.previousMonth(month).toEpochDay() },enabled=parts.year>1200 || parts.month>1) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"ماه قبل") }
                Text("${Solar.months[parts.month-1]} ${parts.year.fa()}",Modifier.weight(1f),textAlign=TextAlign.Center,fontWeight=FontWeight.Bold)
                IconButton(onClick={ anchor=next.toEpochDay() },enabled=parts.year<1600 || parts.month<12) { Icon(Icons.AutoMirrored.Outlined.ArrowForward,"ماه بعد") }
            }
            Row { listOf("ش","ی","د","س","چ","پ","ج").forEach { Text(it,Modifier.weight(1f),textAlign=TextAlign.Center,style=MaterialTheme.typography.labelMedium) } }
            Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
             repeat((leading+days+6)/7) { row ->
                Row {
                    repeat(7) { col ->
                        val d=row*7+col-leading+1
                        Box(Modifier.weight(1f).height(44.dp),contentAlignment=Alignment.Center) {
                            if(d in 1..days) {
                                val date=month.plusDays(d.toLong()-1)
                                val selected=date.toEpochDay()==picked
                                val shape=RoundedCornerShape(14.dp)
                                Box(Modifier.fillMaxWidth().height(44.dp).then(if(date==LocalDate.now() && !selected) Modifier.border(1.dp,MaterialTheme.colorScheme.primary,shape) else Modifier).background(if(selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,shape).clickable {picked=date.toEpochDay();manual=Solar.numeric(date);error=false},contentAlignment=Alignment.Center) {
                                    Text(d.fa(),style=MaterialTheme.typography.bodyMedium,color=if(selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
             }
            }
            OutlinedTextField(value=manual,onValueChange={ manual=it;error=false },label={Text("تاریخ؛ مانند ۱۴۰۵/۰۸/۱۵")},singleLine=true,isError=error,modifier=Modifier.fillMaxWidth())
            if(error) Text("تاریخ شمسی معتبر وارد کنید (۱۲۰۰ تا ۱۶۰۰).",color=MaterialTheme.colorScheme.error)
         }
         Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={ val now=LocalDate.now();anchor=Solar.monthStart(now).toEpochDay();picked=now.toEpochDay();manual=Solar.numeric(now);error=false }) { Text("امروز") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick=dismiss) {Text("انصراف")}
            Button(onClick={val date=Solar.parse(manual);if(date==null) error=true else choose(date)}) {Text("انتخاب")}
         }
        }
       }
      }
    }
}

@Composable fun FullEditor(title: String, busy: Boolean, dismiss: () -> Unit, save: () -> Unit, canSave: Boolean = true, feedback: String = "", saveLabel: String = "ذخیره", content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest={ if(!busy) dismiss() },properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=dismiss,enabled=!busy) { Icon(Icons.Outlined.Close,"بستن") }
                    Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    Button(onClick=save,enabled=!busy && canSave) { Text(if(busy) "در حال ثبت…" else saveLabel) }
                }
                HorizontalDivider()
                if(feedback.isNotBlank()) Text(feedback,Modifier.padding(horizontal=16.dp,vertical=8.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
            }
        }
    }
}

@Composable fun MeetingEditor(meeting: Meeting, busy: Boolean, dismiss: () -> Unit, save: (Meeting) -> Unit, delete: (() -> Unit)? = null, feedback: String = "") {
    var title by rememberSaveable { mutableStateOf(meeting.title) }
    var notes by rememberSaveable { mutableStateOf(meeting.notes) }
    var day by rememberSaveable { mutableStateOf(meeting.date) }
    var picker by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(false) }
    FullEditor(if(meeting.id==0L) "جلسهٔ تازه" else "ویرایش جلسه",busy,dismiss,{ if(title.isBlank()) error=true else save(meeting.copy(title=title,date=day,notes=notes)) },feedback=feedback) {
        Text("تصمیم‌های خوب، پیگیری می‌خواهند.",style=MaterialTheme.typography.bodyLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(title,{title=it;error=false},label={Text("عنوان جلسه *")},isError=error,singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={picker=true},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Outlined.CalendarMonth,null);Spacer(Modifier.width(8.dp));Text(Solar.label(LocalDate.ofEpochDay(day))) }
        OutlinedTextField(notes,{notes=it},label={Text("یادداشت جلسه")},minLines=5,modifier=Modifier.fillMaxWidth())
        if(error) Text("عنوان جلسه را وارد کنید.",color=MaterialTheme.colorScheme.error)
        if(delete!=null) TextButton(onClick=delete,enabled=!busy) {Icon(Icons.Outlined.DeleteOutline,null);Spacer(Modifier.width(8.dp));Text("حذف جلسه",color=MaterialTheme.colorScheme.error)}
    }
    if(picker) SolarPicker(LocalDate.ofEpochDay(day),{picker=false}) {day=it.toEpochDay();picker=false}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun TaskEditor(task: Resolution, meetings: List<Meeting>, reminders: List<Reminder>, busy: Boolean, dismiss: () -> Unit, save: (Resolution,List<Long>) -> Unit, delete: (() -> Unit)?, recordings: List<Recording> = emptyList(), accepted: Set<String> = emptySet(), recordingId: Long? = null, refresh: ()->Unit = {}, message: (String)->Unit = {}, proposalSelected: (String)->Unit = {}, initialSource: String = "", batchChanged: (String)->Unit = {}, history: List<DueChange> = emptyList(), reschedule: ((Resolution,List<Long>,DueChangeKind,String)->Unit)? = null, feedback: String = "", draftSession: String? = null, recordingUsed: (Long?)->Unit = {}, allTasks: List<Resolution> = emptyList(), followUps: List<FollowUp> = emptyList(), onFollowUp: ((String,()->Unit)->Unit)? = null) {
    val context=LocalContext.current
    var title by rememberSaveable { mutableStateOf(task.title) }
    var description by rememberSaveable { mutableStateOf(task.description) }
    var owner by rememberSaveable { mutableStateOf(task.owner) }
    var day by rememberSaveable { mutableStateOf(task.due) }
    var priority by rememberSaveable { mutableStateOf(task.priority.name) }
    var status by rememberSaveable { mutableStateOf(task.status.name) }
    var meetingId by rememberSaveable { mutableStateOf(task.meetingId ?: 0L) }
    var meetingMenu by remember { mutableStateOf(false) }
    var dates by rememberSaveable { mutableStateOf(reminders.map { it.at }.joinToString(",")) }
    var autoFollowUp by rememberSaveable {mutableStateOf(task.id==0L && reminders.isEmpty())}
    val autoTime=remember(day) {DefaultFollowUp.forDue(day)}
    val times=if(autoFollowUp) listOfNotNull(autoTime) else dates.split(',').mapNotNull(String::toLongOrNull).sorted()
    var dateFor by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }
    var inputBusy by remember { mutableStateOf(false) }
    var inputIsVoice by rememberSaveable {mutableStateOf(false)}
    var changeDue by rememberSaveable {mutableStateOf<String?>(null)}
    var showHistory by rememberSaveable {mutableStateOf(false)}
    var ownerFocused by remember {mutableStateOf(false)}
    var ownerDetails by rememberSaveable {mutableStateOf<String?>(null)}
    val focus=LocalFocusManager.current
    val suggestedOwners=remember(allTasks,owner) {Owners.suggestions(allTasks,owner)}
    var databaseTimes by remember {mutableStateOf(reminders.map{it.at}.toSet())}
    LaunchedEffect(task.status) {status=task.status.name}
    LaunchedEffect(reminders.map{it.at}.toSet()) {
        val current=reminders.map{it.at}.toSet()
        if(!autoFollowUp) {
            val removed=databaseTimes-current;val added=current-databaseTimes
            dates=(dates.split(',').mapNotNull(String::toLongOrNull).toSet()-removed+added).sorted().joinToString(",")
        }
        databaseTimes=current
    }
    FullEditor(if(task.id==0L) "مصوبهٔ تازه" else "جزئیات مصوبه",busy,dismiss,{
        error=when {title.isBlank()->"عنوان مصوبه را وارد کنید."; owner.isBlank()->"مسئول انجام کار را مشخص کنید."; else->""}
        if(error.isEmpty()) save(task.copy(title=title,description=description,owner=owner,due=day,priority=Priority.valueOf(priority),status=Status.valueOf(status),meetingId=meetingId.takeIf{it>0}),if(autoFollowUp) listOfNotNull(DefaultFollowUp.forDue(day)) else times)
    },canSave=!inputBusy,feedback=feedback) {
        CaptureInput(meetingId.takeIf{it>0},recordings,accepted,task.id==0L,recordingId,busy,{inputBusy=it},{p->title=p.title;owner=p.owner;description=p.source;if(!inputIsVoice){p.due?.let{day=it.toEpochDay()};priority=p.priority.name};error="";proposalSelected(p.key())},refresh,message,initialSource,batchChanged,draftSession,{inputIsVoice=it!=null;recordingUsed(it)})
        OutlinedTextField(title,{title=it;error=""},label={Text("عنوان مصوبه *")},isError=error.isNotBlank() && title.isBlank(),modifier=Modifier.fillMaxWidth(),minLines=1,maxLines=3)
        OutlinedTextField(owner,{owner=it;error=""},label={Text("مسئول انجام *")},isError=error.isNotBlank() && owner.isBlank(),leadingIcon={Icon(Icons.Outlined.PersonOutline,null)},modifier=Modifier.fillMaxWidth().onFocusChanged{ownerFocused=it.isFocused},singleLine=true)
        if(ownerFocused && suggestedOwners.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            suggestedOwners.forEach {name->InputChip(selected=false,onClick={owner=name;error="";focus.clearFocus()},label={Text(name)},modifier=Modifier.testTag("owner_suggestion_$name"))}
        }
        if(task.id>0) {
            TextButton(onClick={focus.clearFocus();ownerDetails=owner.trim()},enabled=owner.isNotBlank() && !busy,contentPadding=PaddingValues(0.dp),modifier=Modifier.testTag("owner_open_tasks")) {
                Icon(Icons.Outlined.PersonOutline,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(owner);Spacer(Modifier.width(8.dp));Text("• مصوبات باز",style=MaterialTheme.typography.labelMedium)
            }
        }
        Box {
            TextButton(onClick={meetingMenu=true},contentPadding=PaddingValues(0.dp)) { Icon(Icons.Outlined.Groups,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(meetings.find{it.id==meetingId}?.title ?: "جلسه (اختیاری)");Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp)) }
            DropdownMenu(meetingMenu,{meetingMenu=false}) {
                DropdownMenuItem(text={Text("بدون جلسه")},onClick={meetingId=0;meetingMenu=false})
                meetings.forEach { m -> DropdownMenuItem(text={Text(m.title)},onClick={meetingId=m.id;meetingMenu=false}) }
            }
        }
        OutlinedButton(onClick={dateFor="due"},modifier=Modifier.fillMaxWidth()) { Icon(Icons.Outlined.CalendarMonth,null);Spacer(Modifier.width(8.dp));Text("سررسید | ${Solar.label(LocalDate.ofEpochDay(day))}") }
        if(task.id>0) {
            if(Status.valueOf(status)!=Status.DONE) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick={changeDue=DueChangeKind.EXTEND.name},enabled=!busy && !inputBusy,modifier=Modifier.weight(1f)){Text("تمدید مهلت")}
                OutlinedButton(onClick={changeDue=DueChangeKind.POSTPONE.name},enabled=!busy && !inputBusy,modifier=Modifier.weight(1f)){Text("به تعویق انداختن")}
            }
            if(task.delayCount>0) Text("${task.delayCount.fa()} بار موعد این مصوبه عقب رفته است.",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)
            if(history.isNotEmpty()) {
                TextButton(onClick={showHistory=!showHistory}) {Text(if(showHistory) "بستن تاریخچه" else "تاریخچهٔ موعد (${history.size.fa()})")}
                if(showHistory) DueHistoryList(history)
            }
        }
        if(task.id>0) {
            if(onFollowUp!=null) FollowUpPanel(followUps,busy,onFollowUp)
            OutlinedButton(onClick={
                val shown=task.copy(title=title,owner=owner,due=day)
                try {context.startActivity(TaskShare.chooser(shown))} catch(_:Exception) {message("اشتراک‌گذاری باز نشد؛ دوباره تلاش کنید.")}
            },enabled=!busy && title.isNotBlank() && owner.isNotBlank(),modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.Share,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("اشتراک‌گذاری خلاصهٔ مصوبه")}
        }
        if(autoFollowUp) Text(if(autoTime!=null) "پیگیری پیش‌فرض: یک روز قبل از سررسید" else "روز قبل از سررسید گذشته است؛ زمان پیگیری را دستی اضافه کنید.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        times.forEach { at ->
            val dt=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("${Solar.label(dt.toLocalDate())} | ${dt.toLocalTime().toString().fa()}",style=MaterialTheme.typography.bodyMedium);if(at<System.currentTimeMillis()) Text(if(reminders.any{it.at==at && it.delivered}) "ارسال شده" else "زمان گذشته",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                IconButton(onClick={dates=times.filter {it!=at}.joinToString(",");autoFollowUp=false}) {Icon(Icons.Outlined.Close,"حذف یادآوری")}
            }
        }
        OutlinedButton(onClick={dateFor="reminder"},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.AddAlarm,null);Spacer(Modifier.width(8.dp));Text("افزودن زمان پیگیری")}
        TextButton(onClick={more=!more},contentPadding=PaddingValues(0.dp)) {Icon(if(more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null,Modifier.size(18.dp));Text("اولویت، وضعیت و توضیحات")}
        if(more) {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Priority.entries.forEach { p -> FilterChip(selected=priority==p.name,onClick={priority=p.name},label={Text(p.label)}) } }
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { Status.entries.forEach { s -> FilterChip(selected=status==s.name,onClick={status=s.name},label={Text(s.label)},modifier=Modifier.testTag("task_status_${s.name}")) } }
            OutlinedTextField(description,{description=it},label={Text("شرح کار و توضیحات")},minLines=2,modifier=Modifier.fillMaxWidth())
        }
        if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
        if(delete!=null) TextButton(onClick={confirmDelete=true},enabled=!busy) {Icon(Icons.Outlined.DeleteOutline,null);Text("حذف مصوبه",color=MaterialTheme.colorScheme.error)}
    }
    changeDue?.let {kind->ChangeDueDialog(task,DueChangeKind.valueOf(kind),busy,{changeDue=null},feedback) {newDay,reason,reminder->
        error=when {title.isBlank()->"عنوان مصوبه را وارد کنید.";owner.isBlank()->"مسئول انجام کار را مشخص کنید.";else->""}
        if(error.isBlank()) {
            val updated=task.copy(title=title,owner=owner,description=description,due=newDay,priority=Priority.valueOf(priority),status=Status.valueOf(status),meetingId=meetingId.takeIf{it>0})
            val rs=(reminders.filter{it.delivered}.map{it.at}+listOfNotNull(reminder)).distinct().sorted()
            if(reschedule!=null) reschedule(updated,rs,DueChangeKind.valueOf(kind),reason) else save(updated,rs)
        } else changeDue=null
    }}
    if(dateFor.isNotEmpty()) SolarPicker(LocalDate.ofEpochDay(day),{dateFor=""}) { picked ->
        val target=dateFor;dateFor=""
        if(target=="due") day=picked.toEpochDay() else {
            val now=LocalTime.now().plusMinutes(5)
            TimePickerDialog(context,{_,h,m ->
                val at=picked.atTime(h,m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if(at<=System.currentTimeMillis()) error="زمان پیگیری باید در آینده باشد." else {dates=(times+at).distinct().sorted().joinToString(",");autoFollowUp=false;error=""}
            },now.hour,now.minute,true).show()
        }
    }
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("حذف مصوبه؟")},text={Text("«${task.title}» و یادآوری‌های آن حذف می‌شوند.")},confirmButton={TextButton(onClick={confirmDelete=false;delete?.invoke()}){Text("حذف",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("انصراف")}})
    ownerDetails?.let {name->OwnerTasksDialog(name,allTasks) {ownerDetails=null}}
}

