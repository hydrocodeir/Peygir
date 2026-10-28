package ir.peygir.app

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal fun meetingSearch(meeting: Meeting,users: List<CompanyUser>,query: String): Boolean {
    fun clean(s: String)=s.replace('ي','ی').replace('ك','ک').replace('\u200c',' ').trim().lowercase()
    val text=clean(listOf(meeting.title,meeting.location,meeting.guests.joinToString(" "),users.filter{it.id in meeting.attendees}.joinToString(" "){it.name}).joinToString(" "))
    return clean(query).split(Regex("\\s+")).all{text.contains(it)}
}
@Composable internal fun MeetingCalendarScreen(data: Snapshot,open: (Long)->Unit,add: (Long)->Unit,delete: ((Long)->Unit)?=null,initialDay: Long=LocalDate.now().toEpochDay(),initialMode: String?=null,createAt: ((Long,Int)->Unit)?=null,active: Boolean=true,focusVersion: Int=0,clock: ()->java.time.LocalDateTime = {java.time.LocalDateTime.now()}) {
    val c=LocalContext.current;val deputy=OrgAccess.deputy(c)
    val users by Organization.members.collectAsState()
    val min=remember {WidgetDays.min};val max=remember {WidgetDays.max}
    val options=rememberUserOptions()
    var mode by rememberSaveable {mutableStateOf(initialMode ?: options.calendar)}
    var day by rememberSaveable {mutableLongStateOf(initialDay.coerceIn(min,max))}
    var navigation by remember {mutableIntStateOf(0)}
    var query by rememberSaveable {mutableStateOf("")};var searchOpen by rememberSaveable {mutableStateOf(false)};var picker by remember {mutableStateOf(false)}
    val date=LocalDate.ofEpochDay(day)
    val daily=mode!="MONTH"
    val monthCellHeight=with(LocalDensity.current){22.sp.toDp()+18.sp.toDp()+12.dp}.coerceAtLeast(60.dp)
    fun go(value: Long) {day=value.coerceIn(min,max);navigation++}
    val creator=createAt?.takeUnless{deputy}
    fun pickStart(epoch: Long) {
        if(creator==null || epoch !in min..max) return
        go(epoch)
        val dialog=android.app.TimePickerDialog(c,{_,hour,minute->
            val start=hour*60+minute
            if(start>=1439) android.widget.Toast.makeText(c,"شروع باید پیش از ۲۳:۵۹ باشد تا پایان جلسه در همین روز تعیین شود.",android.widget.Toast.LENGTH_LONG).show()
            else creator(epoch,start)
        },9,0,true)
        dialog.setTitle("شروع جلسه - ${Solar.label(LocalDate.ofEpochDay(epoch))}")
        dialog.show()
    }
    fun move(delta: Int) {go(if(daily) day+delta*(if(mode=="WEEK") 7 else 1) else (if(delta>0) Solar.nextMonth(date) else Solar.previousMonth(date)).toEpochDay())}
    Column(Modifier.fillMaxSize().testTag("meeting_calendar")) {
        Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).testTag("section_swipe_2"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                listOf("MONTH" to "ماهانه","WEEK" to "هفتگی","DAY" to "روزانه").forEach{(key,label)->
                    FilterChip(mode==key,{mode=key},label={Text(label,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.labelMedium)},modifier=Modifier.weight(1f).testTag("meeting_mode_$key"))
                }
            }
            IconButton(onClick={searchOpen=true},modifier=Modifier.testTag("meeting_search_button")){Icon(Icons.Outlined.Search,"جست‌وجوی جلسات")}
            if(!deputy) IconButton(onClick={add(day)}){Icon(Icons.Outlined.Add,"جلسهٔ تازه")}
        }
        run {
            // Like daily review, the header stays still and only the day's meeting list turns.
            if(daily) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick={move(-1)},enabled=day>min){Icon(Icons.AutoMirrored.Outlined.ArrowBack,if(mode=="WEEK") "هفتهٔ قبل" else "روز قبل")}
                    TextButton(onClick={picker=true},modifier=Modifier.weight(1f)){Text(Solar.label(date),fontWeight=FontWeight.Bold);Spacer(Modifier.width(4.dp));Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))}
                    IconButton(onClick={move(1)},enabled=day<max){Icon(Icons.AutoMirrored.Outlined.ArrowForward,if(mode=="WEEK") "هفتهٔ بعد" else "روز بعد")}
                    TextButton(onClick={go(LocalDate.now().toEpochDay())},enabled=day!=LocalDate.now().toEpochDay()){Text("امروز")}
                }
                HorizontalDivider(Modifier.padding(horizontal=16.dp))
            }
            if(daily) {
                key(mode) {MeetingTimePages(data,mode,day,navigation,{day=it},::go,open,creator,::pickStart,active,focusVersion,clock)}
            } else {
                val pageDay=day
                val pageDate=LocalDate.ofEpochDay(pageDay);val pageMonth=Solar.monthStart(pageDate)
                val shown=remember(data.meetings,pageDay) {data.meetings.filter{it.date==pageDay}.sortedWith(MeetingSchedule.order)}
                LazyColumn(Modifier.fillMaxSize().testTag("meeting_month_list"),contentPadding=PaddingValues(16.dp,6.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    item {Surface(shape=RoundedCornerShape(20.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            IconButton(onClick={move(-1)},enabled=pageMonth.toEpochDay()>min){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"بازهٔ قبل")}
                            TextButton(onClick={picker=true},modifier=Modifier.weight(1f)){Text(Solar.parts(pageMonth).let{"${Solar.months[it.month-1]} ${it.year.fa()}"},textAlign=TextAlign.Center)}
                            IconButton(onClick={move(1)},enabled=Solar.nextMonth(pageMonth).toEpochDay()<=max){Icon(Icons.AutoMirrored.Outlined.ArrowForward,"بازهٔ بعد")}
                            TextButton(onClick={go(LocalDate.now().toEpochDay())}){Text("امروز")}
                        }
                        Row {listOf("ش","ی","د","س","چ","پ","ج").forEach{Text(it,Modifier.weight(1f),textAlign=TextAlign.Center,style=MaterialTheme.typography.labelSmall)}}
                        val leading=(pageMonth.dayOfWeek.value+1)%7;val length=ChronoUnit.DAYS.between(pageMonth,Solar.nextMonth(pageMonth)).toInt()
                        repeat((leading+length+6)/7) {r->Row {repeat(7) {col->val d=r*7+col-leading
                            Box(Modifier.weight(1f).height(monthCellHeight)) {if(d in 0 until length) {
                                val epoch=pageMonth.plusDays(d.toLong()).toEpochDay();val count=data.meetings.count{it.date==epoch};val chosen=pageDay==epoch
                                val cellColor=when {chosen->MaterialTheme.colorScheme.primary;count>0->MaterialTheme.colorScheme.primaryContainer;else->MaterialTheme.colorScheme.surfaceContainerLow}
                                val ink=when {chosen->MaterialTheme.colorScheme.onPrimary;count>0->MaterialTheme.colorScheme.onPrimaryContainer;else->MaterialTheme.colorScheme.onSurface}
                                Surface(onClick={if(count==0 && creator!=null) pickStart(epoch) else go(epoch)},enabled=epoch in min..max,shape=RoundedCornerShape(12.dp),color=cellColor,contentColor=ink,modifier=Modifier.fillMaxSize().padding(1.dp).testTag("meeting_day_$epoch").semantics{selected=chosen;contentDescription="${Solar.label(LocalDate.ofEpochDay(epoch))}، ${count.fa()} جلسه"}) {
                                    Column(Modifier.padding(vertical=2.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
                                        Text((d+1).fa(),style=MaterialTheme.typography.bodyMedium.copy(lineHeight=22.sp),fontWeight=if(chosen || count>0) FontWeight.Bold else FontWeight.Normal)
                                        MeetingDayMarker(count,epoch,ink)
                                    }
                                }
                            }}
                        }}}
                    }}}
                    item {Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Text(Solar.label(pageDate),Modifier.weight(1f),style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant);CountLabel("جلسات",shown.size)}}
                    if(shown.isEmpty()) item {MeetingCalendarEmpty(deputy,{if(creator!=null) pickStart(pageDay) else add(pageDay)})}
                    items(shown,key={it.id}) {MeetingCalendarCard(it,data,users,deputy,open,delete,showDate=false)}
                }
            }
        }
    }
    if(searchOpen) MeetingSearchDialog(data,users,deputy,query,{query=it},{searchOpen=false},{id->searchOpen=false;open(id)},delete?.let{remove->{id->searchOpen=false;remove(id)}})
    if(picker) SolarPicker(date,{picker=false}){go(it.toEpochDay());picker=false}
}

@Composable private fun MeetingCalendarEmpty(deputy: Boolean,add: ()->Unit) {
    EmptyCard(Icons.Outlined.Event,"جلسه‌ای در این بازه نیست",if(deputy) "دعوت‌های شما پس از همگام‌سازی اینجا دیده می‌شوند." else "برنامهٔ جلسه را با زمان و شرکت‌کنندگان ثبت کنید.",if(deputy) null else "ثبت جلسه",add)
}

@Composable private fun MeetingCalendarCard(m: Meeting,data: Snapshot,users: List<CompanyUser>,deputy: Boolean,open: (Long)->Unit,delete: ((Long)->Unit)?,showDate: Boolean=true) {
    val expanded=rememberUserOptions().cards=="full"
    OutlinedCard(onClick={open(m.id)},shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().testTag("meeting_card_${m.id}")) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(m.title,style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(if(showDate) "${Solar.label(LocalDate.ofEpochDay(m.date))} - ${m.clockLabel()}" else m.clockLabel(),style=MaterialTheme.typography.bodySmall)
                if(expanded) {
                    if(m.location.isNotBlank()) Text(m.location,style=MaterialTheme.typography.bodySmall,maxLines=1,overflow=TextOverflow.Ellipsis)
                    val names=(users.filter{it.id in m.attendees}.map{it.name}+m.guests).joinToString("، ")
                    if(names.isNotBlank()) Text(names,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    if(m.notes.isNotBlank()) Text(m.notes,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                    CountLabel("مصوبات",data.tasks.count{it.meetingId==m.id})
                }
            }
            if(!deputy && delete!=null) PageActions("گزینه‌های جلسهٔ ${m.title}",listOf(PageAction("حذف جلسهٔ ${m.title}",{delete(m.id)},destructive=true)))
        }
    }
}

@Composable private fun MeetingSearchDialog(data: Snapshot,users: List<CompanyUser>,deputy: Boolean,query: String,change: (String)->Unit,dismiss: ()->Unit,open: (Long)->Unit,delete: ((Long)->Unit)?) {
    val focus=remember {FocusRequester()}
    val shown=remember(data.meetings,users,query) {if(query.isBlank()) emptyList() else data.meetings.filter{meetingSearch(it,users,query)}.sortedWith(MeetingSchedule.order)}
    Dialog(onDismissRequest=dismiss,properties=speechDialogProperties()) {
        KeyboardSpeechHost {
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface) {
            Column(Modifier.safeDrawingPadding().imePadding().testTag("meeting_search_modal")) {
                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=dismiss){Icon(Icons.Outlined.Close,"بستن جست‌وجوی جلسات")}
                    Text("جست‌وجوی جلسات",style=MaterialTheme.typography.titleMedium)
                }
                VoiceTextField(query,change,voiceLabel="عنوان جلسه یا نام شرکت‌کننده",label={Text("عنوان جلسه یا نام شرکت‌کننده",maxLines=1)},leadingIcon={Icon(Icons.Outlined.Search,null)},trailingIcon={if(query.isNotEmpty()) IconButton(onClick={change("")}){Icon(Icons.Outlined.Close,"پاک کردن جست‌وجو")}},singleLine=true,modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp).focusRequester(focus).testTag("meeting_search_field"),shape=RoundedCornerShape(16.dp))
                if(query.isBlank()) Text("جست‌وجو در تمام تاریخ‌ها و جلسات قابل مشاهده",Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                else CountLabel("نتایج در همهٔ تاریخ‌ها",shown.size,Modifier.padding(horizontal=16.dp,vertical=8.dp),style=MaterialTheme.typography.labelSmall)
                LazyColumn(Modifier.weight(1f).testTag("meeting_search_results"),contentPadding=PaddingValues(12.dp,4.dp,12.dp,16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(query.isNotBlank() && shown.isEmpty()) item {Text("جلسه‌ای پیدا نشد؛ عنوان یا نام شرکت‌کننده را تغییر دهید.",Modifier.padding(12.dp),style=MaterialTheme.typography.bodyMedium)}
                    items(shown,key={it.id}) {MeetingCalendarCard(it,data,users,deputy,open,delete)}
                }
            }
        }
        LaunchedEffect(Unit) {focus.requestFocus()}
        }
    }
}


/** Reserve the same space on empty days so date numerals remain aligned. */
@Composable private fun MeetingDayMarker(count: Int,day: Long,color: androidx.compose.ui.graphics.Color) {
    val height=with(LocalDensity.current){18.sp.toDp()+4.dp}
    Box(Modifier.fillMaxWidth().height(height),contentAlignment=Alignment.Center) {
        if(count>0) CountBadge(count,"جلسات",Modifier.testTag("meeting_day_marker_$day"),containerColor=color.copy(alpha=0.14f),contentColor=color)
    }
}
