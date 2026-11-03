package ir.peygir.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime

data class MeetingPlacement(val meeting: Meeting,val lane: Int,val lanes: Int)
internal data class DenseMeetingGroup(val meetings: List<Meeting>) {
    val start get()=meetings.minOf{it.start!!}
    val end get()=meetings.maxOf{it.end!!}
}
object MeetingTimeline {
    fun initialHour(minute: Int)=((minute.coerceIn(0,1439)/60f)-0.5f).coerceAtLeast(0f)
    fun weekStart(day: Long)=day-((LocalDate.ofEpochDay(day).dayOfWeek.value+1)%7)
    fun minuteAt(y: Float,height: Float): Int {
        require(height>0 && height.isFinite() && y.isFinite())
        return ((y.coerceIn(0f,height)/height*96).toInt().coerceIn(0,95))*15
    }
    /** Meetings in one overlapping component share a lane count; touching endpoints do not overlap. */
    fun place(meetings: List<Meeting>): List<MeetingPlacement> {
        val ordered=meetings.filter{it.start!=null && it.end!=null && it.end>it.start}.sortedWith(MeetingSchedule.order)
        val result=mutableListOf<MeetingPlacement>();var cluster=mutableListOf<Meeting>();var until=-1
        fun flush() {
            val ends=mutableListOf<Int>();val assigned=cluster.map {meeting->
                val lane=ends.indexOfFirst{it<=meeting.start!!}.let{if(it<0) ends.size else it}
                if(lane==ends.size) ends.add(meeting.end!!) else ends[lane]=meeting.end!!
                meeting to lane
            }
            assigned.forEach{(m,lane)->result+=MeetingPlacement(m,lane,ends.size)};cluster=mutableListOf()
        }
        ordered.forEach {m->if(cluster.isNotEmpty() && m.start!!>=until) flush();if(cluster.isEmpty()) until=m.end!! else until=maxOf(until,m.end!!);cluster+=m}
        if(cluster.isNotEmpty()) flush()
        return result
    }
    /** Collapse only dense connected overlap groups; every meeting remains in the day list. */
    internal fun denseGroups(placements: List<MeetingPlacement>,limit: Int): List<DenseMeetingGroup> {
        val result=mutableListOf<DenseMeetingGroup>();val group=mutableListOf<MeetingPlacement>();var until=-1
        fun flush() {if(group.any{it.lanes>limit}) result+=DenseMeetingGroup(group.map{it.meeting});group.clear()}
        placements.forEach {p->
            if(group.isNotEmpty() && p.meeting.start!!>=until) flush()
            until=if(group.isEmpty()) p.meeting.end!! else maxOf(until,p.meeting.end!!)
            group+=p
        }
        flush();return result
    }
}

@Composable internal fun MeetingTimePages(data: Snapshot,mode: String,selectedDay: Long,navigation: Int,onPage: (Long)->Unit,choose: (Long)->Unit,open: (Long)->Unit,create: ((Long,Int)->Unit)?=null,pickTime: ((Long)->Unit)?=null,active: Boolean=true,focusVersion: Int=0,clock: ()->LocalDateTime = {LocalDateTime.now()}) {
    val weekly=mode=="WEEK";val step=if(weekly) 7 else 1
    val first=if(weekly) MeetingTimeline.weekStart(WidgetDays.min) else WidgetDays.min
    val pager=rememberPagerState(initialPage=((selectedDay-first)/step).toInt(),pageCount={((WidgetDays.max-first)/step+1).toInt()})
    val latestDay by rememberUpdatedState(selectedDay)
    val byDay=remember(data.meetings){data.meetings.groupBy{it.date}}
    LaunchedEffect(navigation) {val target=((selectedDay-first)/step).toInt();if(target!=pager.currentPage) pager.animateScrollToPage(target)}
    LaunchedEffect(pager.settledPage) {
        val anchor=first+pager.settledPage*step
        val offset=if(weekly) latestDay-MeetingTimeline.weekStart(latestDay) else 0
        val next=(anchor+offset).coerceIn(WidgetDays.min,WidgetDays.max)
        if(next!=latestDay) onPage(next)
    }
    val hourHeight=if(weekly) 48.dp else 56.dp
    val gutter=with(LocalDensity.current){38.sp.toDp()}.coerceIn(38.dp,72.dp)
    val dayBadge=with(LocalDensity.current){32.sp.toDp()}.coerceIn(32.dp,44.dp)
    val density=LocalDensity.current
    val options=rememberUserOptions()
    val initialMinute=remember {clock().let{it.hour*60+it.minute}}
    val scroll=rememberScrollState(with(LocalDensity.current){(hourHeight*options.focusHour(initialMinute)).roundToPx()})
    LaunchedEffect(active,focusVersion,mode,options.focus,options.workStart) {if(active) scroll.scrollTo(with(density){(hourHeight*options.focusHour(clock().let{it.hour*60+it.minute})).roundToPx()})}
    Box(Modifier.fillMaxSize().testTag("meeting_day_page_$selectedDay")) {
        HorizontalPager(pager,modifier=Modifier.fillMaxSize().testTag("meeting_days_pager"),key={first+it*step}) {index->
            val anchor=first+index*step;val days=(0 until step).map{anchor+it}
            Column(Modifier.fillMaxSize().testTag(if(weekly) "meeting_week_page_$anchor" else "meeting_timeline_day_$anchor")) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.width(gutter))
                    days.forEach {day->
                        val date=LocalDate.ofEpochDay(day);val chosen=day==selectedDay
                        Surface(onClick={choose(day)},enabled=day in WidgetDays.min..WidgetDays.max,modifier=(if(weekly) Modifier.weight(1f) else Modifier.width(dayBadge+20.dp)).testTag("meeting_week_day_$day").semantics{selected=chosen;contentDescription=Solar.label(date)},color=MaterialTheme.colorScheme.surface) {
                            Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.padding(vertical=3.dp)) {
                                Text(listOf("ش","ی","د","س","چ","پ","ج")[(date.dayOfWeek.value+1)%7],style=MaterialTheme.typography.labelSmall)
                                Surface(shape=CircleShape,color=if(chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                                    Box(Modifier.size(dayBadge),contentAlignment=Alignment.Center) {Text(Solar.parts(date).day.fa(),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold)}
                                }
                            }
                        }
                    }
                }
                val untimed=days.flatMap{byDay[it].orEmpty()}.filter{it.start==null || it.end==null}
                if(untimed.isNotEmpty()) UntimedMeetings(days,untimed,open,gutter)
                HorizontalDivider()
                Row(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).testTag("meeting_timeline_scroll")) {
                    Box(Modifier.width(gutter).height(hourHeight*24)) {
                        repeat(24) {hour->Text("${hour.fa().padStart(2,'۰')}:۰۰",Modifier.fillMaxWidth().offset(y=hourHeight*hour).padding(end=4.dp),fontSize=11.sp,lineHeight=16.sp,maxLines=1,textAlign=TextAlign.End,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    }
                    days.forEach {day->
                        val meetings=remember(byDay,day){MeetingTimeline.place(byDay[day].orEmpty())}
                        DayTimeColumn(day,meetings,weekly,hourHeight,open,Modifier.weight(1f),create?.takeIf{day in WidgetDays.min..WidgetDays.max},pickTime)
                    }
                }
            }
        }
    }
}

@Composable private fun UntimedMeetings(days: List<Long>,meetings: List<Meeting>,open: (Long)->Unit,gutter: androidx.compose.ui.unit.Dp) {
    var show by remember {mutableStateOf<Long?>(null)}
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Text("بدون\nساعت",Modifier.width(gutter),fontSize=9.sp,lineHeight=13.sp,textAlign=TextAlign.Center)
        days.forEach {day->val records=meetings.filter{it.date==day}
            Box(Modifier.weight(1f).heightIn(min=38.dp).padding(1.dp)) {
                if(records.isNotEmpty()) Surface(onClick={if(records.size==1) open(records.first().id) else show=day},shape=RoundedCornerShape(6.dp),color=MaterialTheme.colorScheme.secondaryContainer) {
                    if(records.size==1) Text(records.first().title,Modifier.padding(4.dp),maxLines=2,overflow=TextOverflow.Ellipsis,fontSize=10.sp,lineHeight=14.sp)
                    else Box(Modifier.padding(4.dp),contentAlignment=Alignment.Center){CountBadge(records.size,"جلسات بدون ساعت")}
                }
            }
        }
    }
    show?.let {day->AlertDialog(onDismissRequest={show=null},title={Text(Solar.label(LocalDate.ofEpochDay(day)))},text={Column {meetings.filter{it.date==day}.forEach{m->TextButton(onClick={show=null;open(m.id)}){Text(m.title)}}}},confirmButton={TextButton(onClick={show=null}){Text("بستن")}})}
}

@Composable private fun DayTimeColumn(day: Long,meetings: List<MeetingPlacement>,weekly: Boolean,hourHeight: androidx.compose.ui.unit.Dp,open: (Long)->Unit,modifier: Modifier,create: ((Long,Int)->Unit)?,pickTime: ((Long)->Unit)?) {
    val scheme=MaterialTheme.colorScheme
    val expanded=rememberUserOptions().cards=="full"
    var now by remember {mutableStateOf(LocalDateTime.now())}
    val latestCreate by rememberUpdatedState(create)
    val dense=remember(meetings,weekly){MeetingTimeline.denseGroups(meetings,if(weekly) 2 else 3)}
    val hidden=remember(dense){dense.flatMap{it.meetings}.map{it.id}.toSet()}
    var show by remember(day){mutableStateOf<DenseMeetingGroup?>(null)}
    LaunchedEffect(Unit) {while(true) {now=LocalDateTime.now();delay(60_000)}}
    BoxWithConstraints(modifier.height(hourHeight*24).background(scheme.surfaceContainerLowest).testTag("meeting_time_column_$day")) {
        val width=maxWidth
        // Only the background handles empty-space taps. Existing meeting cards keep their own action.
        val emptyTap=if(create==null) Modifier else Modifier.pointerInput(day) {
            detectTapGestures(onTap={point->latestCreate?.invoke(day,MeetingTimeline.minuteAt(point.y,size.height.toFloat()))})
        }.semantics {
            contentDescription="زمان خالی ${Solar.label(LocalDate.ofEpochDay(day))}"
            onClick("انتخاب ساعت و ثبت جلسه") {pickTime?.invoke(day);true}
        }
        Canvas(Modifier.fillMaxSize().then(emptyTap).testTag("meeting_empty_time_$day")) {
            repeat(25){i->val y=size.height*i/24f;drawLine(scheme.outlineVariant.copy(alpha=.6f),Offset(0f,y),Offset(size.width,y))}
            drawLine(scheme.outlineVariant.copy(alpha=.6f),Offset.Zero,Offset(0f,size.height))
        }
        meetings.filter{it.meeting.id !in hidden}.forEach {placement->
            val m=placement.meeting;val start=m.start!!;val end=m.end!!
            val column=width/placement.lanes;val height=hourHeight*((end-start)/60f)
            val palette=listOf(scheme.primaryContainer to scheme.onPrimaryContainer,scheme.secondaryContainer to scheme.onSecondaryContainer,scheme.tertiaryContainer to scheme.onTertiaryContainer)
            val (color,ink)=if(m.reserved) ReservationColors.background to ReservationColors.ink else palette[(m.id%palette.size).toInt().coerceAtLeast(0)]
            Surface(onClick={open(m.id)},shape=RoundedCornerShape(if(weekly) 5.dp else 10.dp),color=color,contentColor=ink,
                modifier=Modifier.offset(x=column*placement.lane,y=hourHeight*(start/60f)).width(column).height(height).padding(1.dp).testTag("meeting_card_${m.id}").semantics{contentDescription="${m.title}، ${Solar.label(LocalDate.ofEpochDay(day))}، ${m.clockLabel()}"}) {
                Column(Modifier.padding(if(weekly) 3.dp else 8.dp)) {
                    Text(if(m.reserved) "رزرو: ${m.title}" else m.title,fontSize=if(weekly) 11.sp else 15.sp,lineHeight=if(weekly) 16.sp else 22.sp,fontWeight=FontWeight.Medium,maxLines=if(weekly) 6 else 3,overflow=TextOverflow.Ellipsis)
                    if(!weekly && height>100.dp && m.location.isNotBlank()) Text(m.location,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    if(expanded && height>160.dp) {
                        if(weekly && m.location.isNotBlank()) Text(m.location,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                        if(m.notes.isNotBlank()) Text(m.notes,fontSize=if(weekly) 10.sp else 12.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                    }
                }
            }
        }
        dense.forEach {group->
            val groupHeight=hourHeight*((group.end-group.start)/60f)
            Surface(onClick={show=group},shape=RoundedCornerShape(8.dp),color=scheme.secondaryContainer,contentColor=scheme.onSecondaryContainer,
                modifier=Modifier.offset(y=hourHeight*(group.start/60f)).fillMaxWidth().height(groupHeight).padding(1.dp).testTag("meeting_dense_${day}_${group.meetings.first().id}").semantics{contentDescription="${group.meetings.size.fa()} جلسهٔ هم‌پوشان - برای مشاهده لمس کنید"}) {
                Column(Modifier.padding(if(weekly) 3.dp else 8.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=if(weekly) Alignment.CenterHorizontally else Alignment.Start) {
                    if(weekly) CountBadge(group.meetings.size,"جلسات هم‌پوشان") else CountLabel("جلسات هم‌پوشان",group.meetings.size)
                    if(!weekly && groupHeight>80.dp) Text("مشاهده و انتخاب جلسه",style=MaterialTheme.typography.labelSmall)
                }
            }
        }
        if(now.toLocalDate().toEpochDay()==day) Box(Modifier.fillMaxWidth().height(2.dp).offset(y=hourHeight*((now.hour*60+now.minute)/60f)).background(scheme.error))
    }
    show?.let {group->AlertDialog(onDismissRequest={show=null},title={Text(Solar.label(LocalDate.ofEpochDay(day)))},text={
        LazyColumn(Modifier.heightIn(max=420.dp).testTag("dense_meeting_list"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(group.meetings,key={it.id}) {m->OutlinedCard(onClick={show=null;open(m.id)},modifier=Modifier.fillMaxWidth().testTag("dense_meeting_${m.id}")) {
                Column(Modifier.padding(12.dp)) {Text(if(m.reserved) "رزرو: ${m.title}" else m.title,style=MaterialTheme.typography.titleSmall);Text(m.clockLabel(),style=MaterialTheme.typography.bodySmall)}
            }}
        }
    },confirmButton={TextButton(onClick={show=null}){Text("بستن")}})}
}
