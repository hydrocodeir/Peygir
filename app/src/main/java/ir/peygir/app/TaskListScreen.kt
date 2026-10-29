package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

internal val taskFilters=linkedMapOf("open" to "باز","today" to "امروز","all" to "همه","late" to "معوق","near" to "نزدیک سررسید","active" to "در حال انجام","review" to "منتظر تأیید","done" to "انجام‌شده")
internal fun taskMatchesFilter(t: Resolution,today: Long,filter: String)=when(filter) {
    "open"->t.status!=Status.DONE
    "today"->t.status!=Status.DONE && t.due==today
    "late"->t.status!=Status.DONE && t.due<today
    "near"->t.status!=Status.DONE && t.due in today..today+3
    "done"->t.status==Status.DONE
    "active"->t.status==Status.ACTIVE
    "review"->t.status==Status.REVIEW
    else->true
}
@Composable fun TaskListScreen(data: Snapshot,today: Long,filter: String,setFilter: (String)->Unit,edit: (Resolution)->Unit,status: (Long,Status)->Unit) {
    var query by rememberSaveable {mutableStateOf("")};var search by rememberSaveable{mutableStateOf(false)}
    var menu by remember {mutableStateOf(false)}
    val role=OrgAccess.role(androidx.compose.ui.platform.LocalContext.current)
    val order=remember(today,role){Presentation.order(today,role)}
    val meetings=remember(data.meetings){data.meetings.associateBy{it.id}}
    val tasks=remember(data.tasks,today,filter,order){data.tasks.filter{taskMatchesFilter(it,today,filter)}.sortedWith(order)}
    val list=rememberLazyListState()
    LaunchedEffect(filter) {list.scrollToItem(0)}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).testTag("section_swipe_1"),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton(onClick={menu=true},modifier=Modifier.testTag("task_filter_button")) {
                    Icon(Icons.Outlined.FilterList,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(taskFilters[filter] ?: "همه",maxLines=1,overflow=TextOverflow.Ellipsis);Icon(Icons.Outlined.ExpandMore,null)
                }
                DropdownMenu(menu,{menu=false}) {taskFilters.forEach{(key,label)->DropdownMenuItem(text={Text(label)},trailingIcon={if(filter==key) Icon(Icons.Outlined.Check,null)},onClick={menu=false;setFilter(key)},modifier=Modifier.testTag("task_filter_$key"))}}
            }
            CountLabel("مصوبات",tasks.size)
            IconButton(onClick={search=true}){Icon(Icons.Outlined.Search,"جست‌وجوی مصوبات")}
        }
        LazyColumn(Modifier.weight(1f).testTag("task_list"),state=list,contentPadding=PaddingValues(12.dp,4.dp,12.dp,24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(tasks.isEmpty()) item{EmptyCard(Icons.Outlined.SearchOff,"مصوبه‌ای در این فهرست نیست","از فیلتر بالا، فهرست دیگری را انتخاب کنید.")}
            items(tasks,key={it.id}) {t->TaskCard(t,meetings[t.meetingId]?.title,today,{edit(t)}){status(t.id,it)}}
        }
    }
    if(search) {
        val focus=remember{FocusRequester()}
        val index=remember(data.tasks,meetings){data.tasks.associate{it.id to SearchText.task(it,meetings)}}
        val results=remember(data.tasks,index,query,order){val tokens=SearchText.tokens(query);if(tokens.isEmpty()) emptyList() else data.tasks.filter{SearchText.matches(index.getValue(it.id),tokens)}.sortedWith(order)}
        val searchList=rememberLazyListState()
        LaunchedEffect(query){searchList.scrollToItem(0)}
        Dialog(onDismissRequest={search=false},properties=speechDialogProperties()) {
            KeyboardSpeechHost {
            Surface(Modifier.fillMaxSize()) {Column(Modifier.safeDrawingPadding().imePadding().testTag("task_search_modal")) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {IconButton(onClick={search=false}){Icon(Icons.Outlined.Close,"بستن جست‌وجوی مصوبات")};Text("جست‌وجوی مصوبات",style=MaterialTheme.typography.titleMedium)}
                VoiceTextField(query,{query=it},voiceLabel="عنوان، مسئول یا جلسه",label={Text("عنوان، مسئول یا جلسه",maxLines=1)},singleLine=true,leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(query.isNotEmpty()) IconButton(onClick={query=""}){Icon(Icons.Outlined.Close,"پاک‌کردن جست‌وجو")}},modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).focusRequester(focus).testTag("task_search_field"),shape=RoundedCornerShape(16.dp))
                if(query.isBlank()) Text("جست‌وجو در همهٔ مصوبات قابل مشاهده",Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelSmall)
                else CountLabel("نتایج در همهٔ مصوبات",results.size,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.weight(1f).testTag("task_search_results"),state=searchList,contentPadding=PaddingValues(12.dp,4.dp,12.dp,20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(query.isNotBlank() && results.isEmpty()) item{Text("مصوبه‌ای پیدا نشد.",Modifier.padding(12.dp))}
                    items(results,key={it.id}) {t->TaskCard(t,meetings[t.meetingId]?.title,today,{search=false;edit(t)}){status(t.id,it)}}
                }
            }}
            LaunchedEffect(Unit){focus.requestFocus()}
            }
        }
    }
}
