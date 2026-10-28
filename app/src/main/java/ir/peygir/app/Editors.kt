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

fun Resolution.encode(): String = JSONObject().apply { put("id",id); put("meeting",meetingId); put("title",title);put("description",description);put("owner",owner);put("due",due);put("priority",priority.name);put("status",status.name);put("delays",delayCount);taskOrganization(this@encode) }.toString()
fun decodeTask(s: String) = JSONObject(s).let { Resolution(it.getLong("id"),if(it.isNull("meeting")) null else it.getLong("meeting"),it.getString("title"),it.getString("description"),it.getString("owner"),it.getLong("due"),Priority.valueOf(it.getString("priority")),Status.valueOf(it.getString("status")),it.optInt("delays",0)).fromOrganization(it) }

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

@Composable fun FullEditor(title: String, busy: Boolean, dismiss: () -> Unit, save: () -> Unit, canSave: Boolean = true, feedback: String = "", saveLabel: String = "ذخیره", dismissible: Boolean = true, actions: List<PageAction> = emptyList(), content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest={ if(!busy && dismissible) dismiss() },properties=speechDialogProperties()) {
        KeyboardSpeechHost(enabled=!busy) {
        val voiceWorking=LocalKeyboardSpeech.current?.working==true
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    if(dismissible) IconButton(onClick=dismiss,enabled=!busy) { Icon(Icons.Outlined.Close,"بستن") }
                    Text(title,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                    Button(onClick=save,enabled=!busy && canSave && !voiceWorking) { Text(if(busy) "در حال ثبت…" else saveLabel) }
                    PageActions("گزینه‌های فرم",actions,!busy)
                }
                HorizontalDivider()
                if(feedback.isNotBlank()) Text(feedback,Modifier.padding(horizontal=16.dp,vertical=8.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),content=content)
            }
        }
        }
    }
}

@Composable fun MeetingEditor(meeting: Meeting,busy: Boolean,dismiss: ()->Unit,save: (Meeting)->Unit,delete: (()->Unit)?=null,feedback: String="",initialStart: Int?=null,allMeetings: List<Meeting> = emptyList(),copied: Boolean=false) {
    var title by rememberSaveable {mutableStateOf(meeting.title)}
    var notes by rememberSaveable {mutableStateOf(meeting.notes)}
    var day by rememberSaveable {mutableStateOf(meeting.date)}
    var picker by rememberSaveable {mutableStateOf(false)}
    var error by rememberSaveable {mutableStateOf(false)}
    val context=LocalContext.current
    val defaults=rememberUserOptions()
    val users by Organization.members.collectAsState()
    var start by rememberSaveable {mutableStateOf(if(meeting.id>0 || copied) meeting.start else initialStart?.takeIf{it in 0..1438})}
    var end by rememberSaveable {mutableStateOf(if(meeting.id>0 || copied) meeting.end else initialStart?.takeIf{it in 0..1438}?.let{minOf(it+defaults.duration,1439)})}
    var alertMinutes by rememberSaveable {mutableStateOf(meeting.alertMinutes)}
    var location by rememberSaveable {mutableStateOf(meeting.location)}
    var guests by rememberSaveable {mutableStateOf(org.json.JSONArray(meeting.guests).toString())}
    val contacts by Organization.contacts.collectAsState()
    var attendees by rememberSaveable {mutableStateOf(meeting.attendees.joinToString(","))}
    var repeat by rememberSaveable {mutableStateOf(meeting.repeat)}
    var until by rememberSaveable {mutableLongStateOf(meeting.until ?: minOf(meeting.date+90,WidgetDays.max))}
    var more by rememberSaveable {mutableStateOf(false)}
    var untilPicker by remember {mutableStateOf(false)}
    var showConflicts by remember {mutableStateOf(false)}
    var validation by remember {mutableStateOf("")}
    var previous by remember {mutableStateOf(meeting)}
    var incoming by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(meeting) {
        fun <T> merge(old: T,current: T,new: T,assign: (T)->Unit) {if(current==old) assign(new) else if(new!=old && new!=current) incoming=true}
        merge(previous.title,title,meeting.title){title=it};merge(previous.notes,notes,meeting.notes){notes=it};merge(previous.date,day,meeting.date){day=it}
        merge(previous.start,start,meeting.start){start=it};merge(previous.end,end,meeting.end){end=it};merge(previous.location,location,meeting.location){location=it}
        merge(previous.attendees.joinToString(","),attendees,meeting.attendees.joinToString(",")){attendees=it};merge(org.json.JSONArray(previous.guests).toString(),guests,org.json.JSONArray(meeting.guests).toString()){guests=it};merge(previous.repeat,repeat,meeting.repeat){repeat=it}
        merge(previous.until ?: minOf(previous.date+90,WidgetDays.max),until,meeting.until ?: minOf(meeting.date+90,WidgetDays.max)){until=it}
        previous=meeting
    }
    val value=meeting.copy(title=title,date=day,notes=notes,start=start,end=end,location=location,guests=org.json.JSONArray(guests).strings(),attendees=attendees.split(',').filter{it.isNotBlank()}.distinct(),repeat=repeat,until=if(repeat=="NONE") null else until,alertMinutes=alertMinutes)
    val conflicts=remember(day,start,end,attendees,repeat,until,meeting.id,allMeetings){MeetingConflicts.find(value,allMeetings)}
    FullEditor(if(copied) "کپی جلسه" else if(meeting.id==0L) "جلسهٔ تازه" else "ویرایش جلسه",busy,dismiss,{
        if(title.isBlank()) error=true else {
            runCatching{MeetingSchedule.requireTime(value);MeetingRecurrence.expand(value)}.onSuccess{save(value)}.onFailure{validation=it.message.orEmpty();if(repeat!="NONE") more=true}
        }
    },canSave=!incoming,feedback=listOf(feedback,validation,if(error) "عنوان جلسه را وارد کنید." else "").filter{it.isNotBlank()}.distinct().joinToString("\n"),saveLabel=if(conflicts.isEmpty()) "ذخیره" else "ثبت با تداخل",actions=if(delete==null) emptyList() else listOf(PageAction("حذف جلسه",delete,destructive=true))) {
        if(incoming) IncomingEditorChanges("${meeting.title}\n${Solar.label(LocalDate.ofEpochDay(meeting.date))}\n${meeting.notes}",{
            title=meeting.title;notes=meeting.notes;day=meeting.date;start=meeting.start;end=meeting.end;location=meeting.location;attendees=meeting.attendees.joinToString(",");guests=org.json.JSONArray(meeting.guests).toString();repeat=meeting.repeat;until=meeting.until ?: minOf(meeting.date+90,WidgetDays.max);incoming=false
        },{incoming=false})
        if(copied) Text("نسخهٔ تازه با همان افراد؛ تاریخ و ساعت را بررسی کنید.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        VoiceTextField(title,{title=it;error=false},voiceLabel="عنوان جلسه",label={Text("عنوان جلسه *")},isError=error,singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedButton(onClick={picker=true},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.CalendarMonth,null);Spacer(Modifier.width(8.dp));Text(Solar.label(LocalDate.ofEpochDay(day)))}
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={TimePickerDialog(context,{_,h,m->start=h*60+m;if(end==null || end!!<=start!!) end=minOf(start!!+defaults.duration,1439);validation=""},(start ?: 540)/60,(start ?: 540)%60,true).show()},modifier=Modifier.weight(1f).testTag("meeting_start_time")){Text(start?.let{"شروع: ${"%02d:%02d".format(it/60,it%60).fa()}"} ?: "ساعت شروع")}
            OutlinedButton(onClick={TimePickerDialog(context,{_,h,m->end=h*60+m;validation=""},(end ?: 600)/60,(end ?: 600)%60,true).show()},modifier=Modifier.weight(1f).testTag("meeting_end_time")){Text(end?.let{"پایان: ${"%02d:%02d".format(it/60,it%60).fa()}"} ?: "ساعت پایان")}
        }
        Text("ساعت شروع و پایان الزامی است.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        ParticipantsField(value.attendees,value.guests,users,contacts+allMeetings.flatMap{it.guests}){ids,names->attendees=ids.joinToString(",");guests=org.json.JSONArray(names).toString()}
        if(conflicts.isNotEmpty()) {
            OutlinedCard(Modifier.fillMaxWidth().testTag("meeting_conflicts"),colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {Icon(Icons.Outlined.WarningAmber,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));CountLabel("تداخل زمانی افراد مشترک",conflicts.size,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onErrorContainer)}
                    conflicts.take(2).forEach{conflict->MeetingConflictText(conflict,users)}
                    Text("می‌توانید زمان یا افراد را تغییر دهید، یا با تداخل ثبت کنید.",style=MaterialTheme.typography.bodySmall)
                    if(conflicts.size>2) TextButton(onClick={showConflicts=true}) {CountLabel("همهٔ تداخل‌ها",conflicts.size,color=LocalContentColor.current)}
                }
            }
        }
        TextButton(onClick={more=!more},modifier=Modifier.fillMaxWidth().testTag("meeting_more_details")) {
            Icon(if(more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null);Spacer(Modifier.width(6.dp));Text("جزئیات بیشتر")
            if(!more && (location.isNotBlank() || notes.isNotBlank() || repeat!="NONE")) {Spacer(Modifier.width(8.dp));Text("دارای اطلاعات",style=MaterialTheme.typography.labelSmall)}
        }
        if(more) {
            MeetingAlertPicker(alertMinutes){alertMinutes=it}
            VoiceTextField(location,{location=it},voiceLabel="محل جلسه",label={Text("محل یا لینک جلسه")},modifier=Modifier.fillMaxWidth())
            val locations=allMeetings.map{it.location}.filter{it.isNotBlank() && it!=location && PeopleSuggestions.matches(it,location)}.distinct().take(4)
            if(locations.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){locations.forEach{place->SuggestionChip(onClick={location=place},label={Text(place)})}}
            if(meeting.id==0L) {
                ChoiceRow(MeetingRecurrence.labels,repeat,"تکرار جلسه"){repeat=it;validation=""}
                if(repeat!="NONE") {OutlinedButton(onClick={untilPicker=true},modifier=Modifier.fillMaxWidth()){Text("پایان تکرار: ${Solar.label(LocalDate.ofEpochDay(until))}")};Text("هر نوبت، جلسه و مصوبات جداگانه دارد. حداکثر تا یک سال.",style=MaterialTheme.typography.bodySmall)}
            } else if(meeting.series!=null) Text("ویرایش فقط روی این نوبت جلسه اعمال می‌شود.",style=MaterialTheme.typography.bodySmall)
            VoiceTextField(notes,{notes=it},voiceLabel="یادداشت جلسه",label={Text("یادداشت جلسه")},minLines=3,modifier=Modifier.fillMaxWidth())
        }
    }
    if(picker) SolarPicker(LocalDate.ofEpochDay(day),{picker=false}) {day=it.toEpochDay();validation="";picker=false}
    if(untilPicker) SolarPicker(LocalDate.ofEpochDay(until),{untilPicker=false}) {until=it.toEpochDay();validation="";untilPicker=false}
    if(showConflicts) AlertDialog(onDismissRequest={showConflicts=false},title={Text("تداخل‌های زمانی")},text={androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max=360.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){items(conflicts.size){index->MeetingConflictText(conflicts[index],users)}}},confirmButton={TextButton(onClick={showConflicts=false}){Text("بستن")}})
}

@Composable private fun MeetingConflictText(conflict: MeetingConflict,users: List<CompanyUser>) {
    val known=users.filter{it.id in conflict.people}.map{it.name}
    val people=if(known.size==conflict.people.size) known.joinToString("، ") else "${conflict.people.size.fa()} شرکت‌کنندهٔ مشترک"
    val m=conflict.meeting
    Text("${m.title} - ${Solar.label(LocalDate.ofEpochDay(m.date))} - ${m.clockLabel()}\n$people",style=MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun TaskEditor(task: Resolution, meetings: List<Meeting>, reminders: List<Reminder>, busy: Boolean, dismiss: () -> Unit, save: (Resolution,List<Long>) -> Unit, delete: (() -> Unit)?, recordings: List<Recording> = emptyList(), accepted: Set<String> = emptySet(), recordingId: Long? = null, refresh: ()->Unit = {}, message: (String)->Unit = {}, proposalSelected: (String)->Unit = {}, initialSource: String = "", batchChanged: (String)->Unit = {}, history: List<DueChange> = emptyList(), reschedule: ((Resolution,List<Long>,DueChangeKind,String)->Unit)? = null, feedback: String = "", draftSession: String? = null, recordingUsed: (Long?)->Unit = {}, allTasks: List<Resolution> = emptyList(), followUps: List<FollowUp> = emptyList(), onFollowUp: ((String,()->Unit)->Unit)? = null, approval: ((Status)->Unit)? = null) {
    val context=LocalContext.current
    var title by rememberSaveable { mutableStateOf(task.title) }
    var description by rememberSaveable { mutableStateOf(task.description) }
    var owner by rememberSaveable { mutableStateOf(task.owner) }
    var assignee by rememberSaveable {mutableStateOf(task.assignee)}
    val contacts by Organization.contacts.collectAsState()
    val companyUsers by Organization.members.collectAsState()
    var day by rememberSaveable { mutableStateOf(task.due) }
    var priority by rememberSaveable { mutableStateOf(task.priority.name) }
    var status by rememberSaveable { mutableStateOf(task.status.name) }
    var meetingId by rememberSaveable { mutableStateOf(task.meetingId ?: 0L) }
    val keyboardSpeech=rememberDictationMode()==DictationMode.KEYBOARD
    var meetingMenu by remember { mutableStateOf(false) }
    var meetingQuery by remember {mutableStateOf("")}
    var dates by rememberSaveable { mutableStateOf(reminders.map { it.at }.joinToString(",")) }
    var autoFollowUp by rememberSaveable {mutableStateOf(task.id==0L && reminders.isEmpty())}
    val autoTime=remember(day) {DefaultFollowUp.forDue(day)}
    val times=if(autoFollowUp) listOfNotNull(autoTime) else dates.split(',').mapNotNull(String::toLongOrNull).sorted()
    var dateFor by rememberSaveable { mutableStateOf("") }
    var error by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }
    var inputBusy by remember { mutableStateOf(false) }
    var editingInput by rememberSaveable {mutableStateOf(false)}
    var inputIsVoice by rememberSaveable {mutableStateOf(false)}
    var changeDue by rememberSaveable {mutableStateOf<String?>(null)}
    var showHistory by rememberSaveable {mutableStateOf(false)}
    var ownerDetails by rememberSaveable {mutableStateOf<String?>(null)}
    val focus=LocalFocusManager.current
    var databaseTimes by remember {mutableStateOf(reminders.map{it.at}.toSet())}
    var previous by remember {mutableStateOf(task)}
    var incoming by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(task) {
        fun <T> merge(old: T,current: T,new: T,assign: (T)->Unit) {if(current==old) assign(new) else if(new!=old && new!=current) incoming=true}
        merge(previous.title,title,task.title){title=it};merge(previous.description,description,task.description){description=it};merge(previous.owner,owner,task.owner){owner=it};merge(previous.assignee,assignee,task.assignee){assignee=it}
        merge(previous.due,day,task.due){day=it};merge(previous.priority.name,priority,task.priority.name){priority=it};merge(previous.status.name,status,task.status.name){status=it}
        merge(previous.meetingId ?: 0L,meetingId,task.meetingId ?: 0L){meetingId=it};previous=task
    }
    LaunchedEffect(reminders.map{it.at}.toSet()) {
        val current=reminders.map{it.at}.toSet()
        if(!autoFollowUp) {
            val removed=databaseTimes-current;val added=current-databaseTimes
            dates=(dates.split(',').mapNotNull(String::toLongOrNull).toSet()-removed+added).sorted().joinToString(",")
        }
        databaseTimes=current
    }
    val formActions=buildList {
        if(recordings.isNotEmpty()) add(PageAction("صداهای ذخیره‌شده",{editingInput=true}))
        if(task.id==0L) add(PageAction("ورود چند مصوبه از متن",{editingInput=true}))
        if(task.id>0) {
            add(PageAction("ورود چند مصوبه از متن",{editingInput=true}))
            add(PageAction("اشتراک‌گذاری خلاصهٔ مصوبه",{try {context.startActivity(TaskShare.chooser(task.copy(title=title,owner=owner,due=day)))} catch(_:Exception){message("اشتراک‌گذاری باز نشد؛ دوباره تلاش کنید.")}}))
            if(Status.valueOf(status)!=Status.DONE) {add(PageAction("تمدید مهلت",{changeDue=DueChangeKind.EXTEND.name}));add(PageAction("به تعویق انداختن",{changeDue=DueChangeKind.POSTPONE.name}))}
            if(task.status==Status.REVIEW && OrgAccess.role(context)=="manager" && approval!=null) {add(PageAction("تأیید نهایی انجام",{approval(Status.DONE)}));add(PageAction("برگشت برای ادامهٔ کار",{approval(Status.ACTIVE)}))}
            if(delete!=null) add(PageAction("حذف مصوبه",{confirmDelete=true},destructive=true))
        }
    }
    FullEditor(if(task.id==0L) "مصوبهٔ تازه" else "ویرایش مصوبه",busy,dismiss,{
        error=when {title.isBlank()->"عنوان مصوبه را وارد کنید.";owner.isBlank()->"مسئول انجام کار را مشخص کنید.";else->""}
        if(error.isEmpty()) save(task.copy(title=title,description=description,owner=owner,due=day,priority=Priority.valueOf(priority),status=OrgAccess.targetStatus(context,task.copy(assignee=assignee),Status.valueOf(status)),meetingId=meetingId.takeIf{it>0},assignee=assignee),if(autoFollowUp) listOfNotNull(DefaultFollowUp.forDue(day)) else times)
    },canSave=!inputBusy && !incoming,feedback=listOf(feedback,error).filter{it.isNotBlank()}.distinct().joinToString("\n"),actions=formActions) {
        if(incoming) IncomingEditorChanges("${task.title}\nمسئول: ${task.owner}\n${Solar.label(LocalDate.ofEpochDay(task.due))} - ${task.status.label}\n${task.description}",{
            title=task.title;description=task.description;owner=task.owner;assignee=task.assignee;day=task.due;priority=task.priority.name;status=task.status.name;meetingId=task.meetingId ?: 0L;incoming=false
        },{incoming=false})
        if(task.status==Status.REVIEW) Text("گزارش انجام این مصوبه منتظر تأیید رئیس است.",color=MaterialTheme.colorScheme.primary)
        if(editingInput || recordingId!=null || initialSource.isNotBlank()) CaptureInput(meetingId.takeIf{it>0},recordings,accepted,task.id==0L,recordingId,busy,{inputBusy=it},{p->title=p.title;owner=p.owner;description=p.source;if(!inputIsVoice){p.due?.let{day=it.toEpochDay()};priority=p.priority.name};error="";proposalSelected(p.key())},refresh,message,initialSource,batchChanged,draftSession,{inputIsVoice=it!=null;recordingUsed(it)})
        VoiceTextField(title,{title=it;error=""},voiceLabel="عنوان مصوبه",label={Text("عنوان مصوبه *")},isError=error.isNotBlank() && title.isBlank(),modifier=Modifier.fillMaxWidth(),minLines=1,maxLines=3)
        OwnerField(owner,assignee,companyUsers,contacts+allTasks.map{it.owner},error.isNotBlank() && owner.isBlank()){name,id->owner=name;assignee=id;error=""}
        OutlinedButton(onClick={dateFor="due"},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.CalendarMonth,null);Spacer(Modifier.width(8.dp));Text("سررسید - ${Solar.label(LocalDate.ofEpochDay(day))}")}
        if(day<LocalDate.now().toEpochDay() && Status.valueOf(status)!=Status.DONE) Text("موعد این مصوبه گذشته است.",color=MaterialTheme.colorScheme.error)
        if(task.id>0) {
            TaskState(task.copy(status=Status.valueOf(status)))
            followUps.maxByOrNull{it.created}?.let{note->Text(note.text,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis);val at=Instant.ofEpochMilli(note.created).atZone(ZoneId.systemDefault());Text("آخرین پیگیری: ${Solar.label(at.toLocalDate())} - ${at.toLocalTime().withSecond(0).withNano(0).toString().fa()}",style=MaterialTheme.typography.labelSmall)}
        }
        MoreDetails(more,{more=!more},"task_editor_more")
        if(more) {
            if(task.id>0) TextButton(onClick={focus.clearFocus();ownerDetails=owner.trim()},enabled=owner.isNotBlank() && !busy,modifier=Modifier.testTag("owner_open_tasks")){Icon(Icons.Outlined.PersonOutline,null);Spacer(Modifier.width(6.dp));Text("مصوبات باز مسئول")}
            Box {Row(verticalAlignment=Alignment.CenterVertically) {TextButton(onClick={meetingQuery="";meetingMenu=true}){Icon(Icons.Outlined.Groups,null);Spacer(Modifier.width(6.dp));Text(meetings.find{it.id==meetingId}?.title ?: "جلسه (اختیاری)");Icon(Icons.Outlined.ExpandMore,null)}}
                DropdownMenu(meetingMenu && !keyboardSpeech,{meetingMenu=false}){VoiceTextField(meetingQuery,{meetingQuery=it},voiceLabel="جست‌وجوی جلسهٔ مرتبط",label={Text("جست‌وجوی جلسه")},singleLine=true,modifier=Modifier.width(260.dp).padding(8.dp));DropdownMenuItem(text={Text("بدون جلسه")},onClick={meetingId=0;meetingMenu=false});meetings.filter{PeopleSuggestions.matches(it.title,meetingQuery)}.forEach{m->DropdownMenuItem(text={Text(m.title)},onClick={meetingId=m.id;meetingMenu=false})}}}
            ChoiceRow(Priority.entries.associate{it.name to it.label},priority,"اولویت مصوبه"){priority=it}
            ChoiceRow(OrgAccess.statuses(context,task.copy(assignee=assignee)).associate{it.name to it.label},status,"وضعیت مصوبه"){status=it}
            VoiceTextField(description,{description=it},voiceLabel="شرح کار و توضیحات",label={Text("شرح کار و توضیحات")},minLines=2,modifier=Modifier.fillMaxWidth())
            if(task.approved>0) Text("تأیید نهایی: ${Solar.label(Instant.ofEpochMilli(task.approved).atZone(ZoneId.systemDefault()).toLocalDate())}",style=MaterialTheme.typography.bodySmall)
            if(history.isNotEmpty()){TextButton(onClick={showHistory=!showHistory}){CountLabel(if(showHistory) "بستن تاریخچه" else "تاریخچهٔ موعد",history.size,color=LocalContentColor.current)};if(showHistory) DueHistoryList(history)}
            if(task.id>0 && onFollowUp!=null) FollowUpPanel(followUps,busy,onFollowUp)
            if(autoFollowUp) Text(if(autoTime!=null) "پیگیری پیش‌فرض: یک روز قبل از سررسید" else "روز قبل از سررسید گذشته است؛ زمان پیگیری را دستی اضافه کنید.",style=MaterialTheme.typography.labelSmall)
            times.forEach{at->val dt=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault());Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("${Solar.label(dt.toLocalDate())} - ${dt.toLocalTime().toString().fa()}",style=MaterialTheme.typography.bodyMedium);if(at<System.currentTimeMillis()) Text(if(reminders.any{it.at==at && it.delivered}) "ارسال شده" else "زمان گذشته",style=MaterialTheme.typography.labelSmall)};IconButton(onClick={dates=times.filter{it!=at}.joinToString(",");autoFollowUp=false}){Icon(Icons.Outlined.Close,"حذف یادآوری")}}}
            OutlinedButton(onClick={dateFor="reminder"},modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.AddAlarm,null);Spacer(Modifier.width(6.dp));Text("افزودن زمان پیگیری")}
        }
    }
    if(meetingMenu && keyboardSpeech) LinkedMeetingSearch(meetings,meetingQuery,{meetingQuery=it},{meetingMenu=false}){meetingId=it;meetingMenu=false}
    changeDue?.let {kind->ChangeDueDialog(task,DueChangeKind.valueOf(kind),busy,{changeDue=null},feedback) {newDay,reason,reminder->
        error=when {title.isBlank()->"عنوان مصوبه را وارد کنید.";owner.isBlank()->"مسئول انجام کار را مشخص کنید.";else->""}
        if(error.isBlank()) {
            val updated=task.copy(title=title,owner=owner,description=description,due=newDay,priority=Priority.valueOf(priority),status=OrgAccess.targetStatus(context,task.copy(assignee=assignee),Status.valueOf(status)),meetingId=meetingId.takeIf{it>0},assignee=assignee)
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

@Composable private fun IncomingEditorChanges(latest: String,adopt: ()->Unit,keep: ()->Unit) {
    Surface(color=MaterialTheme.colorScheme.errorContainer,shape=MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth().testTag("incoming_editor_changes")) {
        Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text("این مورد هم‌زمان تغییر کرده است. پیش از ذخیره، نسخهٔ تازه را بررسی کنید.",style=MaterialTheme.typography.bodyMedium)
            Text(latest,style=MaterialTheme.typography.bodySmall)
            TextButton(onClick=adopt){Text("استفاده از تغییرات تازه")}
            TextButton(onClick=keep){Text("ادامه با ویرایش من")}
        }
    }
}

