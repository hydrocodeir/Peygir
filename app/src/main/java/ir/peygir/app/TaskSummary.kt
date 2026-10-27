package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.*

@Composable fun TaskSummary(task: Resolution,data: Snapshot,busy: Boolean,dismiss: ()->Unit,status: (Status)->Unit,followUp: (String,()->Unit)->Unit,feedback: String="",edit: (()->Unit)?=null,delete: (()->Unit)?=null,reschedule: ((Resolution,List<Long>,DueChangeKind,String)->Unit)?=null,openMeeting: ((Long)->Unit)?=null) {
    val context=LocalContext.current;val role=OrgAccess.role(context);val deputy=role=="deputy"
    var more by rememberSaveable(task.id){mutableStateOf(false)}
    var reporting by rememberSaveable(task.id){mutableStateOf(false)}
    var confirmDelete by remember{mutableStateOf(false)}
    var changeDue by remember{mutableStateOf<DueChangeKind?>(null)}
    var owner by remember{mutableStateOf(false)}
    var visible by remember{mutableIntStateOf(10)}
    var localError by remember{mutableStateOf("")}
    val notes=data.followUps.filter{it.taskId==task.id}.sortedByDescending{it.created}
    val history=data.history.filter{it.taskId==task.id}
    val reminders=data.reminders.filter{it.taskId==task.id}
    val meeting=data.meetings.find{it.id==task.meetingId}
    val primary=Presentation.taskAction(role,task)
    val today=LocalDate.now().toEpochDay();val late=task.due<today && task.status!=Status.DONE
    val actions=buildList {
        if(!deputy && edit!=null) add(PageAction("ویرایش",edit,"task_edit"))
        OrgAccess.statuses(context,task).filter{it!=task.status && it!=primary?.second}.forEach{s->add(PageAction(if(role=="manager" && task.status==Status.REVIEW && s==Status.ACTIVE) "برگشت برای ادامهٔ کار" else s.label,{status(s)},"summary_status_${s.name}"))}
        if(!deputy && reschedule!=null && task.status!=Status.DONE) {
            add(PageAction("تمدید مهلت",{changeDue=DueChangeKind.EXTEND},"task_extend"))
            add(PageAction("به تعویق انداختن",{changeDue=DueChangeKind.POSTPONE},"task_postpone"))
        }
        add(PageAction("اشتراک‌گذاری خلاصهٔ مصوبه",{localError="";runCatching{context.startActivity(TaskShare.chooser(task))}.onFailure{localError="اشتراک‌گذاری باز نشد؛ دوباره تلاش کنید."}},"task_share"))
        if(!deputy && delete!=null) add(PageAction("حذف مصوبه",{confirmDelete=true},"task_delete",true))
    }
    Dialog(onDismissRequest={if(!busy) dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize().testTag("task_summary")) {Column(Modifier.safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                IconButton(onClick=dismiss,enabled=!busy){Icon(Icons.Outlined.Close,"بستن")}
                Text(if(deputy) "مصوبهٔ من" else "مصوبه",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                PageActions("گزینه‌های مصوبه",actions,!busy)
            }
            HorizontalDivider()
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            val error=listOf(feedback,localError).filter{it.isNotBlank()}.distinct().joinToString("\n")
            if(error.isNotBlank()) Surface(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.errorContainer){Text(error,Modifier.padding(12.dp))}
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(task.title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
                TextButton(onClick={owner=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.testTag("owner_open_tasks")){Icon(Icons.Outlined.PersonOutline,null);Spacer(Modifier.width(6.dp));Text(task.owner.ifBlank{"بدون مسئول"})}
                Text("سررسید: ${Solar.label(LocalDate.ofEpochDay(task.due))}${if(late) " - معوق" else ""}",color=if(late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                TaskState(task)
                if(task.priority==Priority.URGENT) Text("فوری",color=MaterialTheme.colorScheme.error)
                notes.firstOrNull()?.let {note->
                    Text(note.text,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
                    val at=Instant.ofEpochMilli(note.created).atZone(ZoneId.systemDefault())
                    Text("آخرین پیگیری: ${Solar.label(at.toLocalDate())} - ${at.toLocalTime().withSecond(0).withNano(0).toString().fa()}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick={reporting=true},enabled=!busy){Icon(Icons.Outlined.AddComment,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("ثبت نتیجهٔ پیگیری")}
                MoreDetails(more,{more=!more},"task_summary_more")
                if(more) {
                    if(task.description.isNotBlank()) Text(task.description)
                    Text("اولویت: ${task.priority.label}",style=MaterialTheme.typography.bodySmall)
                    meeting?.let{m->TextButton(onClick={openMeeting?.invoke(m.id)},enabled=openMeeting!=null){Text("جلسه: ${m.title}")}}
                    if(task.approved>0) Text("تأیید نهایی: ${Solar.label(Instant.ofEpochMilli(task.approved).atZone(ZoneId.systemDefault()).toLocalDate())}",style=MaterialTheme.typography.bodySmall)
                    reminders.forEach{r->val at=Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault());Text("یادآوری: ${Solar.label(at.toLocalDate())} - ${at.toLocalTime().withSecond(0).withNano(0).toString().fa()}${if(r.delivered) " - ارسال‌شده" else ""}",style=MaterialTheme.typography.bodySmall)}
                    if(notes.isNotEmpty()) {CountLabel("سوابق پیگیری",notes.size,style=MaterialTheme.typography.titleSmall);notes.take(visible).forEach{n->Text(n.text);Text(Solar.label(Instant.ofEpochMilli(n.created).atZone(ZoneId.systemDefault()).toLocalDate()),style=MaterialTheme.typography.labelSmall)}}
                    if(history.isNotEmpty()) {CountLabel("تاریخچهٔ موعد",history.size,style=MaterialTheme.typography.titleSmall);DueHistoryList(history.take(visible))}
                    if(notes.size>visible || history.size>visible) TextButton(onClick={visible+=20}){Text("نمایش سوابق بیشتر")}
                }
            }
            if(primary!=null) Button(onClick={status(primary.second)},enabled=!busy,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=8.dp).heightIn(min=48.dp).testTag("task_primary_action")){Text(primary.first)}
        }}
    }
    if(reporting) {
        var note by rememberSaveable{mutableStateOf("")}
        FullEditor("نتیجهٔ پیگیری",busy,{reporting=false},{followUp(note){note="";reporting=false}},canSave=note.isNotBlank(),feedback=feedback,saveLabel=if(deputy) "ثبت گزارش پیشرفت" else "ثبت نتیجه") {
            OutlinedTextField(note,{note=it.take(1000)},label={Text(if(deputy) "نتیجهٔ پیگیری در یک خط" else "نتیجهٔ کوتاه پیگیری")},modifier=Modifier.fillMaxWidth(),maxLines=3)
        }
    }
    changeDue?.let{kind->ChangeDueDialog(task,kind,busy,{changeDue=null},feedback){day,reason,reminder->reschedule?.invoke(task.copy(due=day),(reminders.filter{it.delivered}.map{it.at}+listOfNotNull(reminder)).distinct().sorted(),kind,reason)}}
    if(owner) OwnerTasksDialog(task.owner,data.tasks){owner=false}
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("حذف مصوبه؟")},text={Text("«${task.title}» و یادآوری‌های آن حذف می‌شوند.")},confirmButton={TextButton(onClick={confirmDelete=false;delete?.invoke()},enabled=!busy){Text("حذف",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("انصراف")}})
}
