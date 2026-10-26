package ir.peygir.app

import android.content.Intent
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import java.time.LocalDate
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val request=mutableStateOf(0L)
    private val widgetRequest=mutableStateOf<WidgetLaunch?>(null)
    override fun attachBaseContext(base: Context) {
        val c=Configuration(base.resources.configuration).apply { setLocale(Locale("fa"));setLayoutDirection(Locale("fa")) }
        super.attachBaseContext(base.createConfigurationContext(c))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        request.value=intent.getLongExtra("task_id",0);widgetRequest.value=WidgetLaunch.from(intent)
        setContent {PeygirApp(requestTask=request.value,consume={request.value=0;widgetRequest.value=null},widgetLaunch=widgetRequest.value)}
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);request.value=intent.getLongExtra("task_id",0);widgetRequest.value=WidgetLaunch.from(intent)}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PeygirApp(requestTask: Long = 0, consume: () -> Unit = {}, widgetLaunch: WidgetLaunch? = null) {
    val context=LocalContext.current
    val store=remember {Store.get(context)}
    val prefs=remember {context.getSharedPreferences("settings",0)}
    var mode by rememberSaveable {mutableStateOf(prefs.getString("theme","system") ?: "system")}
    var dynamicColors by rememberSaveable {mutableStateOf(prefs.getBoolean("dynamic_colors",false))}
    var settingsRevision by remember {mutableIntStateOf(0)}
    var data by remember {mutableStateOf(Snapshot())}
    var loading by remember {mutableStateOf(true)}
    var loadError by remember {mutableStateOf(false)}
    var revision by remember {mutableIntStateOf(0)}
    var today by remember {mutableLongStateOf(LocalDate.now().toEpochDay())}
    val pager=rememberPagerState(pageCount={4})
    val page=pager.currentPage
    var settingsOpen by rememberSaveable {mutableStateOf(false)}
    val sectionState=rememberSaveableStateHolder()
    var filter by rememberSaveable {mutableStateOf("all")}
    var selectedMeeting by rememberSaveable {mutableLongStateOf(0)}
    var editTask by rememberSaveable {mutableStateOf<String?>(null)}
    var proposalSource by rememberSaveable {mutableStateOf("")}
    val drafts=remember {context.getSharedPreferences("drafts",0)}
    var savedProposals by rememberSaveable {mutableStateOf("")}
    var batchSource by rememberSaveable {mutableStateOf("")}
    var editorSession by rememberSaveable {mutableIntStateOf(0)}
    var draftSession by rememberSaveable {mutableStateOf(java.util.UUID.randomUUID().toString())}
    var draftsCleaned by remember {mutableStateOf(false)}
    var sourceRecording by rememberSaveable {mutableStateOf<Long?>(null)}
    var reviewRequestVersion by rememberSaveable {mutableIntStateOf(0)}
    var newMeetingDay by rememberSaveable {mutableLongStateOf(LocalDate.now().toEpochDay())}
    var recordingForInput by rememberSaveable {mutableStateOf<Long?>(null)}
    var reviewDay by rememberSaveable {mutableLongStateOf(LocalDate.now().toEpochDay())}
    var editMeeting by rememberSaveable {mutableStateOf<Long?>(null)}
    var deletingMeeting by rememberSaveable {mutableStateOf<Long?>(null)}
    var busy by remember {mutableStateOf(false)}
    var editorFeedback by remember {mutableStateOf("")}
    val snackbar=remember {SnackbarHostState()}
    val scope=rememberCoroutineScope()
    fun navigate(index: Int) {settingsOpen=false;scope.launch {pager.scrollToPage(index)}}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun message(text: String) {scope.launch {snackbar.showSnackbar(text)}}
    fun action(success: String, finished: () -> Unit = {}, block: () -> Unit) {
        if(busy) return
        busy=true;editorFeedback=""
        scope.launch {
            try {
                data=withContext(Dispatchers.IO) {block();store.snapshot()}
                loadError=false
                finished();revision++
                SharedSync.request(context)
                if(success.isNotBlank()) message(success)
                withContext(Dispatchers.IO) {runCatching {Reminders.process(context);ReviewWidget.updateAll(context)}}
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {val text=e.message?.takeIf { it.any { c -> c in 'آ'..'ی' } } ?: "ثبت تغییرات ناموفق بود؛ دوباره امتحان کنید.";editorFeedback=text;message(text)}
            finally {busy=false}
        }
    }
    LaunchedEffect(Unit) {drafts.edit().clear().apply()}
    LaunchedEffect(store) {store.externalChanges.collect {if(it>0) revision++}}
    LaunchedEffect(editTask,editMeeting,deletingMeeting) {editorFeedback=""}
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            revision++
            withContext(Dispatchers.IO) {runCatching {Reminders.process(context);ReviewWidget.updateAll(context)}}
            SharedJobs.schedule(context)
            while(isActive) {today=LocalDate.now().toEpochDay();SharedSync.request(context);delay(30_000)}
        }
    }
    LaunchedEffect(revision) {
        try {data=withContext(Dispatchers.IO) {if(!draftsCleaned) {store.discardDraftRecordings(except=if(editTask!=null) draftSession else null);draftsCleaned=true};store.snapshot()};loadError=false;loading=false}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {loadError=true;loading=false;android.util.Log.e("Peygir","Snapshot load failed",e);message("خواندن اطلاعات ناموفق بود؛ دوباره تلاش کنید.")}
    }
    fun discardEditor() {store.discardDraftRecordings(draftSession);revision++;editTask=null;proposalSource="";sourceRecording=null;recordingForInput=null;batchSource="";savedProposals=""}
    fun beginSession() {store.discardDraftRecordings(draftSession);draftSession=java.util.UUID.randomUUID().toString();editorSession++;proposalSource="";batchSource="";savedProposals="";recordingForInput=null;sourceRecording=null}
    fun openTask(task: Resolution) {beginSession();editTask=task.encode()}
    fun newTask(meetingId: Long? = selectedMeeting.takeIf{page==2 && it>0}, due: Long = if(page==3) reviewDay else LocalDate.now().plusDays(1).toEpochDay(), recordingId: Long? = null) {beginSession();recordingForInput=recordingId;editTask=Resolution(meetingId=meetingId,due=due).encode()}
    fun newMeeting(day: Long = LocalDate.now().toEpochDay()) {if(editTask!=null) discardEditor();newMeetingDay=day;editMeeting=0;selectedMeeting=0;navigate(2)}
    LaunchedEffect(requestTask,widgetLaunch,data.tasks,loading) {
        if(requestTask>0 && !loading) {
            data.tasks.find{it.id==requestTask}?.let(::openTask)
            navigate(1);consume()
        } else if(widgetLaunch!=null && !loading) {
            val day=WidgetDays.clamp(widgetLaunch.day)
            when(widgetLaunch.kind) {
                WidgetLaunch.NEW_TASK -> {editMeeting=null;newTask(meetingId=null,due=day)}
                WidgetLaunch.NEW_MEETING -> newMeeting(day)
                WidgetLaunch.REVIEW -> {if(editTask!=null) discardEditor();editMeeting=null;reviewDay=day;reviewRequestVersion++;navigate(3)}
            }
            consume()
        }
    }
    fun selectFilter(value: String) {filter=value;navigate(1);selectedMeeting=0}
    BackHandler(enabled=settingsOpen || page!=0) {when {settingsOpen -> settingsOpen=false;selectedMeeting>0 && page==2 -> selectedMeeting=0;else -> navigate(0)}}
    PeygirTheme(mode=mode,dynamicColors=dynamicColors) {
        Scaffold(
            topBar={TopAppBar(title={Column {Text(if(settingsOpen) "تنظیمات" else if(selectedMeeting>0 && page==2) "جزئیات جلسه" else "پیگیر",style=MaterialTheme.typography.titleLarge);Text(Solar.label(LocalDate.ofEpochDay(if(page==3) reviewDay else today)),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}},navigationIcon={if(settingsOpen || (selectedMeeting>0 && page==2)) IconButton(onClick={if(settingsOpen) settingsOpen=false else selectedMeeting=0}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"بازگشت")}},actions={if(!settingsOpen) {FilledTonalButton(onClick={newTask()},enabled=!loading && !busy,contentPadding=PaddingValues(horizontal=12.dp)){Icon(Icons.Outlined.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("ثبت مصوبه")};IconButton(onClick={settingsOpen=true}){Icon(Icons.Outlined.Settings,"تنظیمات")}}})},
            bottomBar={NavigationBar {listOf("پیگیری" to Icons.Outlined.Checklist,"مصوبات" to Icons.Outlined.TaskAlt,"جلسات" to Icons.Outlined.Groups,"مرور" to Icons.Outlined.CalendarMonth).forEachIndexed { i,(label,icon) -> NavigationBarItem(selected=!settingsOpen && page==i,onClick={navigate(i);selectedMeeting=0},icon={Icon(icon,null)},label={Text(label)})}}},
            snackbarHost={SnackbarHost(snackbar)}
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if(loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else if(loadError) Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {Text("اطلاعات خوانده نشد.");Button(onClick={revision++}){Text("تلاش دوباره")}}
                else if(settingsOpen) key(settingsRevision) {SettingsScreen(mode,{mode=it;prefs.edit().putString("theme",it).apply()},::message,dynamicColors,{dynamicColors=it;prefs.edit().putBoolean("dynamic_colors",it).apply()},{
                    mode=prefs.getString("theme","system") ?: "system";dynamicColors=prefs.getBoolean("dynamic_colors",false)
                    selectedMeeting=0;settingsRevision++;revision++
                })}
                else HorizontalPager(pager,Modifier.fillMaxSize().testTag("sections_pager"),key={it}) { section ->
                    sectionState.SaveableStateProvider(section) {
                        Column(Modifier.fillMaxSize().testTag("section_$section")) {
                            Row(Modifier.fillMaxWidth().heightIn(min=36.dp).padding(horizontal=20.dp).testTag("section_swipe_$section"),verticalAlignment=Alignment.CenterVertically) {
                                Text(listOf("نمای کلی","فهرست مصوبات","جلسات و تصمیم‌ها","دفتر روزانه")[section],Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary)
                                Text("تغییر بخش",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Outlined.Swipe,null,Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Box(Modifier.weight(1f)) {
                                when(section) {
                                    0 -> DashboardScreen(data,today,::selectFilter,::openTask,{id,s -> action("وضعیت به‌روز شد") {store.status(id,s)}},{navigate(3)},{newTask()})
                                    1 -> TaskListScreen(data,today,filter,{filter=it},::openTask,{id,s->action("وضعیت به‌روز شد"){store.status(id,s)}})
                                    2 -> if(selectedMeeting==0L) MeetingsScreen(data,{selectedMeeting=it},{newMeeting()},{deletingMeeting=it}) else MeetingDetail(data,selectedMeeting,today,::openTask,{id,s->action("وضعیت به‌روز شد"){store.status(id,s)}},{editMeeting=selectedMeeting},{newTask(selectedMeeting)},{deletingMeeting=selectedMeeting})
                                    3 -> key(reviewRequestVersion) {DailyReviewScreen(data,today,reviewDay,{reviewDay=it},::openTask,{id,s->action("وضعیت به‌روز شد"){store.status(id,s)}},{selectedMeeting=it;navigate(2)},{r->newTask(r.meetingId,reviewDay,r.id)},{selectFilter("late")},{newTask(due=it)})}
                                }
                            }
                        }
                    }
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
        editTask?.let { encoded -> key(editorSession,encoded) {
            val encodedTask=decodeTask(encoded)
            val task=data.tasks.find{it.id==encodedTask.id} ?: encodedTask
            TaskEditor(task,data.meetings,data.reminders.filter{it.taskId==task.id},busy,::discardEditor,{t,rs->action("مصوبه ثبت شد",{
                val captured=proposalSource.isNotBlank()
                if(captured) {savedProposals=(savedProposals.split(',')+proposalSource).filter{it.isNotBlank()}.distinct().takeLast(500).joinToString(",")}
                val remaining=if(captured && task.id==0L) Extractor.extract(batchSource).filterNot{it.key() in savedProposals.split(',')} else emptyList()
                proposalSource="";recordingForInput=null
                if(remaining.isNotEmpty()) {store.discardDraftRecordings(draftSession);draftSession=java.util.UUID.randomUUID().toString();editorSession++;editTask=Resolution(meetingId=t.meetingId,due=t.due).encode()} else discardEditor()
            }){store.saveTask(t,rs,draftSession=draftSession,consumedRecording=sourceRecording);if(task.id>0 && t.due!=task.due) context.getSystemService(android.app.NotificationManager::class.java).cancel(task.id.toInt())}},if(task.id>0) ({action("مصوبه حذف شد",::discardEditor){store.deleteTask(task.id);context.getSystemService(android.app.NotificationManager::class.java).cancel(task.id.toInt())}}) else null,recordings=data.recordings+data.draftRecordings.filter{it.draftSession==draftSession},accepted=savedProposals.split(',').toSet(),recordingId=recordingForInput,refresh={revision++},message=::message,proposalSelected={proposalSource=it},initialSource=batchSource,batchChanged={batchSource=it},history=data.history.filter{it.taskId==task.id},feedback=editorFeedback,draftSession=draftSession,recordingUsed={sourceRecording=it},allTasks=data.tasks,followUps=data.followUps.filter{it.taskId==task.id},onFollowUp={text,onSaved->action("نتیجهٔ پیگیری ثبت شد",onSaved){store.addFollowUp(task.id,text)}},reschedule={t,rs,kind,reason->action("تغییر موعد ثبت شد",::discardEditor){store.saveTask(t,rs,kind,reason,draftSession,sourceRecording);context.getSystemService(android.app.NotificationManager::class.java).cancel(t.id.toInt())}})
        } }
        editMeeting?.let { id -> key(id) {MeetingEditor(data.meetings.find{it.id==id} ?: Meeting(date=newMeetingDay),busy,{editMeeting=null},save={m->action("جلسه ثبت شد",{editMeeting=null}){store.saveMeeting(m)}},delete=if(id>0) ({deletingMeeting=id}) else null,feedback=editorFeedback)} }
        deletingMeeting?.let { id ->
            val meeting=data.meetings.find{it.id==id}
            if(meeting!=null) AlertDialog(onDismissRequest={if(!busy) deletingMeeting=null},title={Text("حذف جلسه؟")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Text("«${meeting.title}» حذف می‌شود. مصوبات، یادآوری‌ها و صداهای آن حفظ می‌شوند و دیگر به این جلسه متصل نیستند.");if(editorFeedback.isNotBlank()) Text(editorFeedback,color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton(onClick={action("جلسه حذف شد",{deletingMeeting=null;if(selectedMeeting==id) selectedMeeting=0;if(editMeeting==id) editMeeting=null}){store.deleteMeeting(id)}},enabled=!busy){Text(if(busy) "در حال حذف…" else "حذف",color=MaterialTheme.colorScheme.error)}},dismissButton={TextButton(onClick={deletingMeeting=null},enabled=!busy){Text("انصراف")}})
        }
    }
}

@Composable fun EmptyCard(icon: ImageVector, title: String, subtitle: String, action: String? = null, click: () -> Unit = {}) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=18.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium)
            Text(subtitle,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
            if(action!=null) FilledTonalButton(onClick=click) {Text(action)}
        }
    }
}

@Composable fun DashboardScreen(data: Snapshot,today: Long,filter: (String)->Unit,edit: (Resolution)->Unit,status: (Long,Status)->Unit,review: ()->Unit,add: ()->Unit) {
    val open=data.tasks.filter{it.status!=Status.DONE}.sortedWith(compareBy<Resolution>{it.due}.thenByDescending{it.priority.ordinal})
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,4.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {
            Surface(shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(Modifier.fillMaxWidth().padding(vertical=8.dp)) {
                    listOf(Triple("معوق",open.count{it.due<today},"late"),Triple("نزدیک سررسید",open.count{it.due in today..today+3},"near"),Triple("انجام‌شده",data.tasks.count{it.status==Status.DONE},"done")).forEach {(label,count,key)->
                        Column(Modifier.weight(1f).clickable{filter(key)}.padding(vertical=4.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                            Text(count.fa(),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold,color=if(key=="late" && count>0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            Text(label,style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item {Row(verticalAlignment=Alignment.CenterVertically) {Text("برای پیگیری",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);TextButton(onClick=review){Icon(Icons.Outlined.CalendarMonth,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("مرور روزانه")}}}
        if(open.isEmpty()) item {EmptyCard(Icons.Outlined.TaskAlt,"مصوبه‌ای برای پیگیری ندارید.","از «ثبت مصوبه»، بنویسید یا بگویید.","ثبت اولین مصوبه",add)}
        items(open,key={it.id}) {t->TaskCard(t,data.meetings.find{it.id==t.meetingId}?.title,today,{edit(t)}){status(t.id,it)}}
    }
}

@Composable fun TaskCard(t: Resolution,meeting: String?,today: Long,edit: ()->Unit,status: (Status)->Unit) {
    var menu by remember {mutableStateOf(false)}
    val late=t.due<today && t.status!=Status.DONE
    val date=Solar.parts(LocalDate.ofEpochDay(t.due)).let{"${it.day.fa()} ${Solar.months[it.month-1]}"}
    Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(start=2.dp,end=2.dp,top=6.dp,bottom=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=t.status==Status.DONE,onCheckedChange={status(if(it) Status.DONE else Status.WAITING)},modifier=Modifier.semantics{contentDescription="انجام مصوبه: ${t.title}"})
            Column(Modifier.weight(1f).clickable(onClick=edit).padding(vertical=4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(t.title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis,textDecoration=if(t.status==Status.DONE) TextDecoration.LineThrough else null)
                Text("${t.owner.ifBlank { "بدون مسئول" }} • ${if(t.due==today) "امروز" else date}${if(late) " • معوق" else ""}",style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    if(t.status==Status.ACTIVE) Surface(shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.secondaryContainer) {Text(t.status.label,Modifier.padding(horizontal=6.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSecondaryContainer)}
                    if(t.priority>=Priority.HIGH) Text(t.priority.label,style=MaterialTheme.typography.labelSmall,color=if(t.priority==Priority.URGENT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    if(t.delayCount>0) Text("تغییر موعد: ${t.delayCount.fa()}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                    if(meeting!=null) Text(meeting,Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick={menu=true}) {Icon(Icons.Outlined.MoreVert,"وضعیت و گزینه‌های ${t.title}",Modifier.size(20.dp))}
                DropdownMenu(menu,{menu=false}) {
                    Status.entries.forEach {s->DropdownMenuItem(text={Text(s.label)},trailingIcon={if(t.status==s) Icon(Icons.Outlined.Check,null)},onClick={menu=false;status(s)})}
                    if(t.status!=Status.DONE) DropdownMenuItem(text={Text("تمدید یا تعویق مهلت")},onClick={menu=false;edit()})
                    DropdownMenuItem(text={Text("ویرایش")},onClick={menu=false;edit()})
                }
            }
        }
    }
}

@Composable fun TaskListScreen(data: Snapshot,today: Long,filter: String,setFilter: (String)->Unit,edit: (Resolution)->Unit,status: (Long,Status)->Unit) {
    var search by rememberSaveable {mutableStateOf("")}
    val tasks=data.tasks.filter {t -> (when(filter){"late"->t.status!=Status.DONE && t.due<today;"near"->t.status!=Status.DONE && t.due in today..today+3;"done"->t.status==Status.DONE;"active"->t.status==Status.ACTIVE;else->true}) && (search.isBlank() || listOf(t.title,t.owner,t.description,data.meetings.find{it.id==t.meetingId}?.title.orEmpty()).any{it.contains(search.trim(),true)})}.sortedWith(compareBy<Resolution>{it.status==Status.DONE}.thenBy{it.due}.thenByDescending{it.priority.ordinal})
    Column {
        OutlinedTextField(search,{search=it},leadingIcon={Icon(Icons.Outlined.Search,null)},placeholder={Text("جست‌وجوی مصوبه، مسئول یا جلسه")},trailingIcon={if(search.isNotEmpty()) IconButton(onClick={search=""}) {Icon(Icons.Outlined.Close,"پاک‌کردن جست‌وجو")}},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp),shape=RoundedCornerShape(18.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("all" to "همه","late" to "معوق","near" to "نزدیک سررسید","active" to "در حال انجام","done" to "انجام‌شده").forEach {(key,label)->FilterChip(selected=filter==key,onClick={setFilter(key)},label={Text(label)})}}
        LazyColumn(contentPadding=PaddingValues(16.dp,8.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            item {Text("${tasks.size.fa()} مصوبه",style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            if(tasks.isEmpty()) item {EmptyCard(Icons.Outlined.SearchOff,"مصوبه‌ای در این فهرست نیست","می‌توانید فیلتر را عوض کنید یا یک مصوبهٔ تازه بسازید.")}
            items(tasks,key={it.id}) {t->TaskCard(t,data.meetings.find{it.id==t.meetingId}?.title,today,{edit(t)}){status(t.id,it)}}
        }
    }
}

@Composable fun MeetingsScreen(data: Snapshot,open: (Long)->Unit,add: ()->Unit,delete: ((Long)->Unit)? = null) {
    LazyColumn(contentPadding=PaddingValues(16.dp,8.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Row(verticalAlignment=Alignment.CenterVertically){Text("دفتر جلسات",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);TextButton(onClick=add){Icon(Icons.Outlined.Add,null,Modifier.size(18.dp));Text("جلسهٔ تازه")}}}
        if(data.meetings.isEmpty()) item {EmptyCard(Icons.Outlined.Groups,"اولین جلسه را بسازید","مصوبات و فایل‌های صوتی هر جلسه را کنار هم نگه دارید.","ثبت جلسه",add)}
        items(data.meetings,key={it.id}) {m->
            val tasks=data.tasks.filter{it.meetingId==m.id}
            OutlinedCard(onClick={open(m.id)},shape=RoundedCornerShape(24.dp)) {Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {Row(verticalAlignment=Alignment.CenterVertically) {Icon(Icons.Outlined.FolderOpen,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(10.dp));Text(m.title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);if(delete!=null) IconButton(onClick={delete(m.id)}){Icon(Icons.Outlined.DeleteOutline,"حذف جلسهٔ ${m.title}",tint=MaterialTheme.colorScheme.onSurfaceVariant)}};Text(Solar.label(LocalDate.ofEpochDay(m.date)),style=MaterialTheme.typography.bodySmall);if(m.notes.isNotBlank()) Text(m.notes,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall);Text("${tasks.size.fa()} مصوبه • ${tasks.count{it.status==Status.DONE}.fa()} انجام‌شده",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary)}}
        }
    }
}
@Composable fun MeetingDetail(data: Snapshot,id: Long,today: Long,edit: (Resolution)->Unit,status: (Long,Status)->Unit,editMeeting: ()->Unit,add: ()->Unit,delete: (() -> Unit)? = null) {
    val m=data.meetings.find{it.id==id} ?: return
    val tasks=data.tasks.filter{it.meetingId==id}
    LazyColumn(contentPadding=PaddingValues(16.dp,8.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {Text(m.title,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);Text(Solar.label(LocalDate.ofEpochDay(m.date)),color=MaterialTheme.colorScheme.onSurfaceVariant);if(m.notes.isNotBlank()) Text(m.notes,Modifier.padding(top=12.dp));Row {TextButton(onClick=editMeeting){Icon(Icons.Outlined.Edit,null);Spacer(Modifier.width(8.dp));Text("ویرایش جلسه")};if(delete!=null) TextButton(onClick=delete){Icon(Icons.Outlined.DeleteOutline,null);Spacer(Modifier.width(8.dp));Text("حذف جلسه",color=MaterialTheme.colorScheme.error)}};Text("${data.recordings.count{it.meetingId==id}.fa()} یادداشت صوتی؛ در فرم ثبت مصوبه در دسترس است",style=MaterialTheme.typography.bodySmall)}
        if(tasks.isEmpty()) item {EmptyCard(Icons.Outlined.Assignment,"مصوبات این جلسه","تصمیم جلسه را به کاری با مسئول و مهلت روشن تبدیل کنید.","افزودن مصوبه",add)}
        items(tasks,key={it.id}) {t->TaskCard(t,null,today,{edit(t)}){status(t.id,it)}}
    }
}
