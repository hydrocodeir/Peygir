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
    private val meetingRequest=mutableStateOf(0L)
    private val sharedRequest=mutableStateOf<String?>(null)
    private val widgetRequest=mutableStateOf<WidgetLaunch?>(null)
    override fun attachBaseContext(base: Context) {
        val c=Configuration(base.resources.configuration).apply { setLocale(Locale("fa"));setLayoutDirection(Locale("fa")) }
        super.attachBaseContext(base.createConfigurationContext(c))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        request.value=intent.getLongExtra("task_id",0);widgetRequest.value=WidgetLaunch.from(intent);sharedRequest.value=intent.getStringExtra("shared_record");meetingRequest.value=intent.getLongExtra("meeting_id",0)
        setContent {PeygirEntry {PeygirApp(requestTask=request.value,consume={request.value=0;widgetRequest.value=null;sharedRequest.value=null;meetingRequest.value=0},widgetLaunch=widgetRequest.value,sharedRecord=sharedRequest.value,requestMeeting=meetingRequest.value)}}
    }
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);request.value=intent.getLongExtra("task_id",0);widgetRequest.value=WidgetLaunch.from(intent);sharedRequest.value=intent.getStringExtra("shared_record");meetingRequest.value=intent.getLongExtra("meeting_id",0)}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PeygirApp(requestTask: Long = 0, consume: () -> Unit = {}, widgetLaunch: WidgetLaunch? = null,sharedRecord: String?=null,requestMeeting: Long=0,initialSection: Int=2,calendarClock: ()->java.time.LocalDateTime = {java.time.LocalDateTime.now()}) {
    val context=LocalContext.current
    val store=remember {Store.get(context)}
    val accountRevision by SharedSync.revision.collectAsState()
    val serverState by SharedSync.connection.state.collectAsState()
    val serverWorking by SharedSync.working.collectAsState()
    val sharedAccount=SharedCredentials(context).connected()
    var serverDetails by remember {mutableStateOf(false)}
    var calendarFocus by remember {mutableIntStateOf(0)}
    val deputy=OrgAccess.deputy(context)
    val role=OrgAccess.role(context)
    val prefs=remember {context.getSharedPreferences("settings",0)}
    var mode by rememberSaveable {mutableStateOf(prefs.getString("theme","system") ?: "system")}
    var dynamicColors by rememberSaveable {mutableStateOf(prefs.getBoolean("dynamic_colors",false))}
    var settingsRevision by remember {mutableIntStateOf(0)}
    var data by remember {mutableStateOf(Snapshot())}
    var loading by remember {mutableStateOf(true)}
    var loadError by remember {mutableStateOf(false)}
    var revision by remember {mutableIntStateOf(0)}
    var today by remember {mutableLongStateOf(LocalDate.now().toEpochDay())}
    val pageOrder=listOf(2,0,1,3)
    val pager=rememberPagerState(initialPage=pageOrder.indexOf(initialSection).coerceAtLeast(0),pageCount={4})
    val page=pageOrder[pager.currentPage]
    var settingsOpen by rememberSaveable {mutableStateOf(false)}
    val sectionState=rememberSaveableStateHolder()
    var filter by rememberSaveable {mutableStateOf("open")}
    var calendarDay by rememberSaveable {mutableLongStateOf(LocalDate.now().toEpochDay())}
    var calendarVersion by rememberSaveable {mutableIntStateOf(0)}
    var calendarMode by rememberSaveable {mutableStateOf("DAY")}
    var selectedMeeting by rememberSaveable {mutableLongStateOf(0)}
    var editTask by rememberSaveable {mutableStateOf<String?>(null)}
    var taskEditing by rememberSaveable {mutableStateOf(false)}
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
    var newMeetingStart by rememberSaveable {mutableStateOf<Int?>(null)}
    var newMeetingTemplate by rememberSaveable {mutableStateOf<String?>(null)}
    var recordingForInput by rememberSaveable {mutableStateOf<Long?>(null)}
    var reviewDay by rememberSaveable {mutableLongStateOf(LocalDate.now().toEpochDay())}
    var editMeeting by rememberSaveable {mutableStateOf<Long?>(null)}
    var deletingMeeting by rememberSaveable {mutableStateOf<Long?>(null)}
    var busy by remember {mutableStateOf(false)}
    var editorFeedback by remember {mutableStateOf("")}
    val snackbar=remember {SnackbarHostState()}
    val scope=rememberCoroutineScope()
    fun navigate(index: Int) {settingsOpen=false;scope.launch {pager.scrollToPage(pageOrder.indexOf(index))}}
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
            revision++;calendarFocus++
            withContext(Dispatchers.IO) {runCatching {Reminders.process(context);ReviewWidget.updateAll(context)}}
            SharedJobs.schedule(context);ServerNetwork.observe(context)
            while(isActive) {today=LocalDate.now().toEpochDay();SharedSync.request(context,force=false);delay(5_000)}
        }
    }
    LaunchedEffect(revision) {
        try {data=withContext(Dispatchers.IO) {if(!draftsCleaned) {store.discardDraftRecordings(except=if(editTask!=null) draftSession else null);draftsCleaned=true};Organization.members.value=Organization.users(store);Organization.contacts.value=Organization.contactNames(store);store.snapshot()};loadError=false;loading=false}
        catch(e:CancellationException) {throw e}
        catch(e:Exception) {loadError=true;loading=false;android.util.Log.e("Peygir","Snapshot load failed",e);message("خواندن اطلاعات ناموفق بود؛ دوباره تلاش کنید.")}
    }
    fun discardEditor() {store.discardDraftRecordings(draftSession);revision++;editTask=null;proposalSource="";sourceRecording=null;recordingForInput=null;batchSource="";savedProposals=""}
    fun beginSession() {store.discardDraftRecordings(draftSession);draftSession=java.util.UUID.randomUUID().toString();editorSession++;proposalSource="";batchSource="";savedProposals="";recordingForInput=null;sourceRecording=null}
    fun openTask(task: Resolution) {beginSession();taskEditing=false;editTask=task.encode()}
    fun newTask(meetingId: Long? = selectedMeeting.takeIf{page==2 && it>0}, due: Long = if(page==3) reviewDay else LocalDate.now().plusDays(1).toEpochDay(), recordingId: Long? = null) {if(deputy) return;beginSession();taskEditing=true;recordingForInput=recordingId;editTask=Resolution(meetingId=meetingId,due=due).encode()}
    fun newMeeting(day: Long = LocalDate.now().toEpochDay(),start: Int? = null) {if(deputy) return;if(editTask!=null) discardEditor();newMeetingDay=day;newMeetingStart=start;newMeetingTemplate=null;editMeeting=0;selectedMeeting=0;navigate(2)}
    fun copyMeeting(source: Meeting) {
        if(deputy) return
        val draft=MeetingCopies.draft(source)
        newMeetingTemplate=MeetingCopies.encode(draft);newMeetingDay=draft.date;newMeetingStart=draft.start;editMeeting=0;navigate(2)
    }
    LaunchedEffect(requestTask,requestMeeting,widgetLaunch,data.tasks,data.meetings,loading) {
        if(requestMeeting>0 && !loading) {data.meetings.find{it.id==requestMeeting}?.let{selectedMeeting=it.id;navigate(2)};consume()}
        else if(requestTask>0 && !loading) {
            data.tasks.find{it.id==requestTask}?.let(::openTask)
            navigate(1);consume()
        } else if(widgetLaunch!=null && !loading) {
            val day=WidgetDays.clamp(widgetLaunch.day)
            when(widgetLaunch.kind) {
                WidgetLaunch.NEW_TASK -> {editMeeting=null;newTask(meetingId=null,due=day)}
                WidgetLaunch.NEW_MEETING -> newMeeting(day)
                WidgetLaunch.MEETINGS -> {if(editTask!=null) discardEditor();editMeeting=null;selectedMeeting=0;calendarDay=day;calendarMode="DAY";calendarVersion++;navigate(2)}
                WidgetLaunch.REVIEW -> {if(editTask!=null) discardEditor();editMeeting=null;reviewDay=day;reviewRequestVersion++;navigate(3)}
            }
            consume()
        }
    }
    LaunchedEffect(sharedRecord,data,loading) {
        if(sharedRecord!=null && !loading) {
            val match=withContext(Dispatchers.IO){SharedStore(store).remoteLocalId(sharedRecord)}
            if(match!=null) {if(match.first=="meeting") {selectedMeeting=match.second;navigate(2)} else data.tasks.find{it.id==match.second}?.let{openTask(it);navigate(1)};consume()}
            else SharedSync.request(context)
        }
    }
    if(serverDetails && sharedAccount) AlertDialog(onDismissRequest={serverDetails=false},title={Text("اتصال به سرور")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(serverState.label)
        if(serverState.lastSuccess>0) Text("آخرین پاسخ موفق: ${java.time.Instant.ofEpochMilli(serverState.lastSuccess).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).withSecond(0).toString().fa()}",style=MaterialTheme.typography.bodySmall)
        if(!serverState.connected && !serverState.loginRequired) Text("تلاش مجدد خودکار انجام می‌شود؛ اطلاعات روی گوشی محفوظ است.",style=MaterialTheme.typography.bodySmall)
        if(serverState.loginRequired) Text("از تنظیمات حساب، دوباره وارد شوید.")
    }},confirmButton={TextButton(onClick={serverDetails=false;if(serverState.loginRequired) settingsOpen=true else {revision++;SharedSync.request(context)}},enabled=!serverWorking){Text(if(serverState.loginRequired) "تنظیمات حساب" else "تازه‌سازی")}},dismissButton={TextButton(onClick={serverDetails=false}){Text("بستن")}})
    var lastPrincipal by remember {mutableStateOf(OrgAccess.company(context)+"|"+OrgAccess.id(context))}
    LaunchedEffect(accountRevision,data.tasks,data.meetings) {
        val principal=OrgAccess.company(context)+"|"+OrgAccess.id(context)
        if(principal!=lastPrincipal) {if(editTask!=null) discardEditor();editMeeting=null;newMeetingTemplate=null;newMeetingStart=null;selectedMeeting=0;lastPrincipal=principal}
        if(deputy && editTask!=null) {val id=decodeTask(editTask!!).id;if(id>0 && data.tasks.none{it.id==id}) discardEditor()}
        if(selectedMeeting>0 && data.meetings.none{it.id==selectedMeeting}) selectedMeeting=0
    }
    fun selectFilter(value: String) {filter=value;navigate(1);selectedMeeting=0}
    BackHandler(enabled=settingsOpen || page!=2 || selectedMeeting>0) {when {settingsOpen -> settingsOpen=false;selectedMeeting>0 && page==2 -> selectedMeeting=0;else -> navigate(2)}}
    PeygirTheme(mode=mode,dynamicColors=dynamicColors) {
        Scaffold(
            topBar={TopAppBar(title={ServerTitle(if(settingsOpen) "تنظیمات" else if(selectedMeeting>0 && page==2) "جزئیات جلسه" else "پیگیر",serverState.takeIf{sharedAccount}){serverDetails=true}},navigationIcon={if(settingsOpen || (selectedMeeting>0 && page==2)) IconButton(onClick={if(settingsOpen) settingsOpen=false else selectedMeeting=0}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"بازگشت")}},actions={if(!settingsOpen) {if(!deputy && page in listOf(0,1)) {
                val awaiting=data.tasks.count{it.status==Status.REVIEW}
                if(role=="manager") {
                    if(awaiting>0) IconButton(onClick={selectFilter("review")},enabled=!loading && !busy){BadgedBox(badge={Badge{Text(if(awaiting>99) "۹۹+" else awaiting.fa())}}){Icon(Icons.Outlined.TaskAlt,"تأییدها")}}
                    IconButton(onClick={newTask()},enabled=!loading && !busy){Icon(Icons.Outlined.Add,"ثبت مصوبه")}
                } else FilledTonalButton(onClick={newTask()},enabled=!loading && !busy,contentPadding=PaddingValues(horizontal=12.dp)){Icon(Icons.Outlined.Add,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text("ثبت مصوبه")}
            };ServerRefresh(serverWorking){revision++;SharedSync.request(context)};IconButton(onClick={settingsOpen=true}){Icon(Icons.Outlined.Settings,"تنظیمات")}}})},
            bottomBar={NavigationBar {listOf((if(deputy) "جلسات من" else "جلسات") to Icons.Outlined.Groups,"پیگیری" to Icons.Outlined.Checklist,(if(deputy) "مصوبات من" else "مصوبات") to Icons.Outlined.TaskAlt,"مرور" to Icons.Outlined.CalendarMonth).forEachIndexed { i,(label,icon) -> NavigationBarItem(selected=!settingsOpen && page==pageOrder[i],onClick={navigate(pageOrder[i]);selectedMeeting=0},icon={val awaiting=if(role=="manager" && i==1) data.tasks.count{it.status==Status.REVIEW} else 0
                Box(Modifier.size(32.dp),contentAlignment=Alignment.Center){Icon(icon,null,Modifier.size(24.dp));if(awaiting>0) Badge(containerColor=MaterialTheme.colorScheme.primary,modifier=Modifier.align(Alignment.TopEnd).testTag("approval_badge").semantics{contentDescription="${awaiting.fa()} مصوبه نیازمند تأیید"}){Text(if(awaiting>99) "۹۹+" else awaiting.fa())}}},label={Text(label)})}}},
            snackbarHost={SnackbarHost(snackbar)}
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if(loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else if(loadError) Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally) {Text("اطلاعات خوانده نشد.");Button(onClick={revision++}){Text("تلاش دوباره")}}
                else if(settingsOpen) key(settingsRevision) {SettingsScreen(mode,{mode=it;prefs.edit().putString("theme",it).apply()},::message,dynamicColors,{dynamicColors=it;prefs.edit().putBoolean("dynamic_colors",it).apply()},{
                    mode=prefs.getString("theme","system") ?: "system";dynamicColors=prefs.getBoolean("dynamic_colors",false)
                    selectedMeeting=0;settingsRevision++;revision++
                },showTitle=false)}
                else HorizontalPager(pager,Modifier.fillMaxSize().testTag("sections_pager"),key={pageOrder[it]}) { position ->
                    val section=pageOrder[position]
                    sectionState.SaveableStateProvider(section) {
                        Column(Modifier.fillMaxSize().testTag("section_$section")) {
                            Box(Modifier.weight(1f)) {
                                when(section) {
                                    0 -> DashboardScreen(data,today,::selectFilter,::openTask,{id,s -> action("وضعیت به‌روز شد") {store.status(id,s)}},{navigate(3)},{newTask()},addMeeting={newMeeting()})
                                    1 -> TaskListScreen(data,today,filter,{filter=it},::openTask,{id,s->action("وضعیت به‌روز شد"){store.status(id,s)}})
                                    2 -> if(selectedMeeting==0L) key(calendarVersion) {MeetingCalendarScreen(data,{selectedMeeting=it},{newMeeting(it)},if(deputy) null else ({deletingMeeting=it}),initialDay=calendarDay,initialMode=calendarMode,createAt={day,start->newMeeting(day,start)},active=page==2,focusVersion=calendarFocus,clock=calendarClock)} else MeetingDetail(data,selectedMeeting,today,::openTask,{id,s->action("وضعیت به‌روز شد"){store.status(id,s)}},{if(!deputy) editMeeting=selectedMeeting},{newTask(selectedMeeting)},if(deputy) null else ({deletingMeeting=selectedMeeting}),copy=if(deputy) null else ({data.meetings.find{it.id==selectedMeeting}?.let(::copyMeeting)}))
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
            if(deputy) DeputyTaskDetails(task,data,busy,::discardEditor,{status->action("وضعیت به‌روز شد"){store.status(task.id,status)}},{text,onSaved->action("گزارش ثبت شد",onSaved){store.addFollowUp(task.id,text)}},editorFeedback)
            else if(task.id>0 && !taskEditing) TaskSummary(task,data,busy,::discardEditor,
                status={next->action(if(next==Status.DONE) "انجام مصوبه ثبت شد" else "وضعیت به‌روز شد",if(next in listOf(Status.DONE,Status.REVIEW)) ::discardEditor else ({})){store.status(task.id,next)}},
                followUp={text,onSaved->action("نتیجهٔ پیگیری ثبت شد",onSaved){store.addFollowUp(task.id,text)}},feedback=editorFeedback,edit={taskEditing=true},
                delete={action("مصوبه حذف شد",::discardEditor){store.deleteTask(task.id);context.getSystemService(android.app.NotificationManager::class.java).cancel(task.id.toInt())}},
                reschedule={t,rs,kind,reason->action("تغییر موعد ثبت شد",::discardEditor){store.saveTask(t,rs,kind,reason);context.getSystemService(android.app.NotificationManager::class.java).cancel(t.id.toInt())}},
                openMeeting={id->discardEditor();selectedMeeting=id;navigate(2)})
            else TaskEditor(task,data.meetings,data.reminders.filter{it.taskId==task.id},busy,::discardEditor,{t,rs->action("مصوبه ثبت شد",{
                val captured=proposalSource.isNotBlank()
                if(captured) {savedProposals=(savedProposals.split(',')+proposalSource).filter{it.isNotBlank()}.distinct().takeLast(500).joinToString(",")}
                val remaining=if(captured && task.id==0L) Extractor.extract(batchSource).filterNot{it.key() in savedProposals.split(',')} else emptyList()
                proposalSource="";recordingForInput=null
                if(remaining.isNotEmpty()) {store.discardDraftRecordings(draftSession);draftSession=java.util.UUID.randomUUID().toString();editorSession++;editTask=Resolution(meetingId=t.meetingId,due=t.due).encode()} else discardEditor()
            }){store.saveTask(t,rs,draftSession=draftSession,consumedRecording=sourceRecording);if(task.id>0 && t.due!=task.due) context.getSystemService(android.app.NotificationManager::class.java).cancel(task.id.toInt())}},if(task.id>0) ({action("مصوبه حذف شد",::discardEditor){store.deleteTask(task.id);context.getSystemService(android.app.NotificationManager::class.java).cancel(task.id.toInt())}}) else null,recordings=data.recordings+data.draftRecordings.filter{it.draftSession==draftSession},accepted=savedProposals.split(',').toSet(),recordingId=recordingForInput,refresh={revision++},message=::message,proposalSelected={proposalSource=it},initialSource=batchSource,batchChanged={batchSource=it},history=data.history.filter{it.taskId==task.id},feedback=editorFeedback,draftSession=draftSession,recordingUsed={sourceRecording=it},allTasks=data.tasks,followUps=data.followUps.filter{it.taskId==task.id},onFollowUp={text,onSaved->action("نتیجهٔ پیگیری ثبت شد",onSaved){store.addFollowUp(task.id,text)}},reschedule={t,rs,kind,reason->action("تغییر موعد ثبت شد",::discardEditor){store.saveTask(t,rs,kind,reason,draftSession,sourceRecording);context.getSystemService(android.app.NotificationManager::class.java).cancel(t.id.toInt())}},approval={s->action(if(s==Status.DONE) "انجام مصوبه تأیید شد" else "مصوبه برای ادامهٔ کار برگشت",::discardEditor){store.status(task.id,s)}})
        } }
        editMeeting?.let { id -> key(id,newMeetingTemplate) {
            val template=if(id==0L) newMeetingTemplate?.let{runCatching{MeetingCopies.decode(it)}.getOrNull()} else null
            MeetingEditor(data.meetings.find{it.id==id} ?: template ?: Meeting(date=newMeetingDay),busy,{editMeeting=null;newMeetingTemplate=null;newMeetingStart=null},save={m->var saved=0L;action("جلسه ثبت شد",{editMeeting=null;newMeetingTemplate=null;newMeetingStart=null;selectedMeeting=saved}){saved=store.saveMeetingSeries(m)}},delete=if(id>0) ({deletingMeeting=id}) else null,feedback=editorFeedback,initialStart=if(id==0L) newMeetingStart else null,allMeetings=data.meetings,copied=template!=null)
        } }
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

@Composable fun DashboardScreen(data: Snapshot,today: Long,filter: (String)->Unit,edit: (Resolution)->Unit,status: (Long,Status)->Unit,review: ()->Unit,add: ()->Unit,addMeeting: (()->Unit)?=null) {
    val role=OrgAccess.role(LocalContext.current);val deputy=role=="deputy"
    val open=data.tasks.filter{it.status!=Status.DONE}.sortedWith(Presentation.order(today,role))
    val pending=open.filter{it.status==Status.REVIEW}
    val actionable=open.filter{it.status!=Status.REVIEW}
    val groups=buildList {
        if(role=="manager" && pending.isNotEmpty()) add(Triple("منتظر تأیید شما",pending,"review"))
        if(deputy) {add(Triple("امروز من",actionable.filter{it.due==today},"today"));add(Triple("معوق‌های من",actionable.filter{it.due<today},"late"))}
        else {add(Triple("معوق",actionable.filter{it.due<today},"late"));add(Triple("امروز",actionable.filter{it.due==today},"today"))}
        add(Triple("نزدیک سررسید",actionable.filter{it.due in today+1..today+3},"near"))
    }.filter{it.second.isNotEmpty()}
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(16.dp,4.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {Row(Modifier.testTag("section_swipe_0"),verticalAlignment=Alignment.CenterVertically) {Text("برای پیگیری",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);if(role=="assistant" && addMeeting!=null) TextButton(onClick=addMeeting){Text("ثبت جلسه")};IconButton(onClick=review){Icon(Icons.Outlined.CalendarMonth,"مرور روزانه")}}}
        if(groups.isEmpty()) item {EmptyCard(Icons.Outlined.TaskAlt,if(open.isEmpty()) "مصوبه‌ای برای پیگیری ندارید." else "اقدام فوری ندارید.",if(deputy) "مصوبات واگذارشده اینجا دیده می‌شوند." else "مصوبات امروز و معوق در این بخش دیده می‌شوند.")}
        groups.forEach{(title,records,key)->
            item {TextButton(onClick={filter(key)},contentPadding=PaddingValues(0.dp)){CountLabel(title,records.size,style=MaterialTheme.typography.titleSmall,color=if(key=="late") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}}
            items(records.take(8),key={it.id}){t->TaskCard(t,null,today,{edit(t)}){status(t.id,it)}}
            if(records.size>8) item{TextButton(onClick={filter(key)}){CountLabel("همهٔ موارد",records.size,color=LocalContentColor.current)}}
        }
        if(role!="manager" && pending.isNotEmpty()) item {TextButton(onClick={filter("review")}){CountLabel("در انتظار تأیید رئیس",pending.size,color=LocalContentColor.current)}}
        item {TextButton(onClick={filter("open")},modifier=Modifier.fillMaxWidth()){CountLabel("همهٔ مصوبات باز",open.size,color=LocalContentColor.current)}}
    }
}

@Composable fun TaskCard(t: Resolution,meeting: String?,today: Long,edit: ()->Unit,status: (Status)->Unit) {
    val context=LocalContext.current;val deputy=OrgAccess.deputy(context)
    val late=t.due<today && t.status!=Status.DONE
    val date=Solar.parts(LocalDate.ofEpochDay(t.due)).let{"${it.day.fa()} ${Solar.months[it.month-1]}"}
    Surface(onClick=edit,modifier=Modifier.fillMaxWidth().testTag("task_card_${t.id}"),shape=RoundedCornerShape(16.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.padding(horizontal=4.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Checkbox(checked=t.status==Status.DONE,enabled=!(deputy && t.status==Status.DONE),onCheckedChange={status(OrgAccess.targetStatus(context,t,if(it) Status.DONE else Status.WAITING))},modifier=Modifier.semantics{contentDescription="انجام مصوبه: ${t.title}"})
            Column(Modifier.weight(1f).padding(vertical=2.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(t.title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis,textDecoration=if(t.status==Status.DONE) TextDecoration.LineThrough else null)
                Text("${t.owner.ifBlank{"بدون مسئول"}} - ${if(t.due==today) "امروز" else date}${if(late) " - معوق" else ""}",style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis,color=if(late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TaskState(t);if(t.priority==Priority.URGENT) Text("فوری",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.error)}
            }
            PageActions("وضعیت و گزینه‌های ${t.title}",buildList {
                OrgAccess.statuses(context,t).filter{it!=t.status}.forEach{state->add(PageAction(state.label,{status(state)}))}
                add(PageAction("جزئیات",edit))
            })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun MeetingsScreen(data: Snapshot,open: (Long)->Unit,add: ()->Unit,delete: ((Long)->Unit)? = null) {
    LazyColumn(contentPadding=PaddingValues(16.dp,8.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item {Row(verticalAlignment=Alignment.CenterVertically){Text("دفتر جلسات",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);TextButton(onClick=add){Icon(Icons.Outlined.Add,null,Modifier.size(18.dp));Text("جلسهٔ تازه")}}}
        if(data.meetings.isEmpty()) item {EmptyCard(Icons.Outlined.Groups,"اولین جلسه را بسازید","مصوبات و فایل‌های صوتی هر جلسه را کنار هم نگه دارید.","ثبت جلسه",add)}
        items(data.meetings,key={it.id}) {m->
            val tasks=data.tasks.filter{it.meetingId==m.id}
            OutlinedCard(onClick={open(m.id)},shape=RoundedCornerShape(24.dp)) {Column(Modifier.fillMaxWidth().padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {Row(verticalAlignment=Alignment.CenterVertically) {Icon(Icons.Outlined.FolderOpen,null,tint=MaterialTheme.colorScheme.primary);Spacer(Modifier.width(10.dp));Text(m.title,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold);if(delete!=null) IconButton(onClick={delete(m.id)}){Icon(Icons.Outlined.DeleteOutline,"حذف جلسهٔ ${m.title}",tint=MaterialTheme.colorScheme.onSurfaceVariant)}};Text(Solar.label(LocalDate.ofEpochDay(m.date)),style=MaterialTheme.typography.bodySmall);if(m.notes.isNotBlank()) Text(m.notes,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall);FlowRow(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){CountLabel("مصوبات",tasks.size);CountLabel("انجام‌شده",tasks.count{it.status==Status.DONE})}}}
        }
    }
}
@Composable fun MeetingDetail(data: Snapshot,id: Long,today: Long,edit: (Resolution)->Unit,status: (Long,Status)->Unit,editMeeting: ()->Unit,add: ()->Unit,delete: (() -> Unit)? = null,copy: (() -> Unit)? = null) {
    val context=LocalContext.current;val deputy=OrgAccess.deputy(context);val role=OrgAccess.role(context)
    val people by Organization.members.collectAsState()
    val m=data.meetings.find{it.id==id} ?: return
    val tasks=data.tasks.filter{it.meetingId==id}.sortedWith(Presentation.order(today,role))
    var more by rememberSaveable(id){mutableStateOf(false)}
    val names=people.filter{it.id in m.attendees}.map{it.name}+m.guests
    val summary=names.take(2).joinToString("، ")
    LazyColumn(contentPadding=PaddingValues(16.dp,8.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.testTag("section_swipe_2"),verticalAlignment=Alignment.Top) {Text(m.title,Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold);PageActions("گزینه‌های جلسه",buildList {
                if(!deputy) add(PageAction("ویرایش جلسه",editMeeting,"meeting_edit"))
                if(!deputy && copy!=null) add(PageAction("کپی جلسه",copy,"meeting_copy"))
                if(!deputy && delete!=null) add(PageAction("حذف جلسه",delete,"meeting_delete",true))
            })}
            Text("${Solar.label(LocalDate.ofEpochDay(m.date))} - ${m.clockLabel()}",style=MaterialTheme.typography.bodyMedium)
            if(m.attendees.isNotEmpty() || m.guests.isNotEmpty()) {CountLabel("شرکت‌کنندگان",m.attendees.size+m.guests.size);if(summary.isNotBlank()) Text(summary,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)}
            if(!deputy) Button(onClick=add,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(6.dp));Text("ثبت مصوبه")}
            MoreDetails(more,{more=!more},"meeting_detail_more")
            if(more) {
                if(m.location.isNotBlank()) Text("محل: ${m.location}")
                if(names.isNotEmpty()) Text(names.joinToString("، "))
                if(m.notes.isNotBlank()) Text(m.notes)
                if(m.repeat!="NONE") Text("تکرار: ${MeetingRecurrence.labels[m.repeat] ?: m.repeat}",style=MaterialTheme.typography.bodySmall)
                val audio=data.recordings.count{it.meetingId==id}
                if(audio>0) {CountLabel("یادداشت‌های صوتی",audio);Text("در فرم ثبت مصوبه در دسترس است",style=MaterialTheme.typography.bodySmall)}
            }
        }}
        item {CountLabel("مصوبات",tasks.size,style=MaterialTheme.typography.titleSmall.copy(fontWeight=FontWeight.Bold))}
        if(tasks.isEmpty()) item {Text("هنوز مصوبه‌ای ثبت نشده است.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        items(tasks,key={it.id}){t->TaskCard(t,null,today,{edit(t)}){status(t.id,it)}}
    }
}
