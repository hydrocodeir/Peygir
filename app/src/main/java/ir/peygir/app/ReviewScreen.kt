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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.*

data class DayReview(val tasks: List<Resolution>, val meetings: List<Meeting>, val recordings: List<Recording>)
object ReviewDays {
    fun forDay(data: Snapshot, day: Long, byReminders: Boolean, zone: ZoneId = ZoneId.systemDefault()): DayReview {
        val taskIds=if(byReminders) data.reminders.filter { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate().toEpochDay()==day }.map {it.taskId}.toSet() else emptySet()
        return DayReview(
            data.tasks.filter {if(byReminders) it.id in taskIds else it.due==day}.sortedWith(compareBy<Resolution>{it.status==Status.DONE}.thenByDescending{it.priority.ordinal}.thenBy{it.id}),
            data.meetings.filter{it.date==day},
            data.recordings.filter{Instant.ofEpochMilli(it.created).atZone(zone).toLocalDate().toEpochDay()==day}
        )
    }
}

@Composable fun DailyReviewScreen(data: Snapshot, today: Long, selected: Long, select: (Long)->Unit, edit: (Resolution)->Unit, status: (Long,Status)->Unit, openMeeting: (Long)->Unit, openRecording: (Recording)->Unit, overdue: ()->Unit, add: (Long)->Unit) {
    val min=remember {Solar.toDate(1200,1,1)!!.toEpochDay()}
    val max=remember {Solar.toDate(1600,12,29)!!.toEpochDay()}
    val pager=rememberPagerState(initialPage=(selected.coerceIn(min,max)-min).toInt(),pageCount={(max-min+1).toInt()})
    val scope=rememberCoroutineScope()
    var picker by rememberSaveable {mutableStateOf(false)}
    var reminders by rememberSaveable {mutableStateOf(false)}
    val day=min+pager.currentPage
    fun go(value: Long) {scope.launch {pager.animateScrollToPage((value.coerceIn(min,max)-min).toInt())}}
    LaunchedEffect(day) {select(day)}
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={go(day-1)},enabled=day>min) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"روز قبل")}
            TextButton(onClick={picker=true},modifier=Modifier.weight(1f)) {Text(Solar.label(LocalDate.ofEpochDay(day)),fontWeight=FontWeight.Bold);Spacer(Modifier.width(4.dp));Icon(Icons.Outlined.ExpandMore,null,Modifier.size(18.dp))}
            IconButton(onClick={go(day+1)},enabled=day<max) {Icon(Icons.AutoMirrored.Outlined.ArrowForward,"روز بعد")}
            TextButton(onClick={go(today)},enabled=day!=today) {Text("امروز")}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)) {
            val start=LocalDate.ofEpochDay(day).minusDays(((LocalDate.ofEpochDay(day).dayOfWeek.value+1)%7).toLong())
            repeat(7) {i->
                val date=start.plusDays(i.toLong())
                val chosen=date.toEpochDay()==day
                Surface(onClick={go(date.toEpochDay())},modifier=Modifier.weight(1f),shape=RoundedCornerShape(14.dp),color=if(chosen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally) {Text(listOf("ش","ی","د","س","چ","پ","ج")[i],style=MaterialTheme.typography.labelSmall);Text(Solar.parts(date).day.fa(),style=MaterialTheme.typography.titleSmall,fontWeight=if(chosen) FontWeight.Bold else FontWeight.Normal)}
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            FilterChip(!reminders,{reminders=false},label={Text("سررسیدها")})
            Spacer(Modifier.width(8.dp))
            FilterChip(reminders,{reminders=true},label={Text("یادآوری‌ها")})
            Spacer(Modifier.weight(1f))
            Text("ورق بزنید",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(horizontal=16.dp))
        HorizontalPager(pager,modifier=Modifier.weight(1f).testTag("daily_pager"),key={min+it}) {index->
            val at=min+index
            val review=remember(data,at,reminders) {ReviewDays.forDay(data,at,reminders)}
            LazyColumn(Modifier.fillMaxSize().testTag("review_day_$at"),contentPadding=PaddingValues(16.dp,12.dp,16.dp,24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                item {Text("${review.tasks.size.fa()} مصوبه • ${review.meetings.size.fa()} جلسه • ${review.recordings.size.fa()} یادداشت صوتی",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                if(at==today) {
                    val count=data.tasks.count{it.status!=Status.DONE && it.due<today}
                    if(count>0) item {TextButton(onClick=overdue,contentPadding=PaddingValues(horizontal=0.dp)) {Icon(Icons.Outlined.History,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("${count.fa()} مصوبهٔ معوق از روزهای قبل",color=MaterialTheme.colorScheme.error)}}
                }
                items(review.tasks,key={"task_${it.id}"}) {t->
                    Column {
                        TaskCard(t,data.meetings.find{it.id==t.meetingId}?.title,today,{edit(t)}){status(t.id,it)}
                        if(reminders) {
                            val times=data.reminders.filter{it.taskId==t.id && Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()==at}
                            Text(times.joinToString(" • "){Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).toLocalTime().toString().fa()+if(it.delivered) " ✓" else ""},Modifier.padding(start=16.dp,top=2.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                items(review.meetings,key={"meeting_${it.id}"}) {m->
                    Surface(onClick={openMeeting(m.id)},shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {Icon(Icons.Outlined.Groups,null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(m.title,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis);Text("جلسه • ${data.tasks.count{it.meetingId==m.id}.fa()} مصوبه",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
                }
                items(review.recordings,key={"recording_${it.id}"}) {r->
                    Surface(onClick={openRecording(r)},shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceContainerLow) {Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically){Icon(Icons.Outlined.MicNone,null,Modifier.size(20.dp));Spacer(Modifier.width(10.dp));Column(Modifier.weight(1f)){Text(r.transcript.ifBlank{"یادداشت صوتی"},style=MaterialTheme.typography.titleSmall,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${Instant.ofEpochMilli(r.created).atZone(ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString().fa()} • ${r.seconds.fa()} ثانیه",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
                }
                if(review.tasks.isEmpty() && review.meetings.isEmpty() && review.recordings.isEmpty()) item {Column(Modifier.fillMaxWidth().padding(vertical=28.dp),horizontalAlignment=Alignment.CenterHorizontally) {Text(if(reminders) "یادآوری‌ای برای این روز ندارید." else "این صفحه هنوز خالی است.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant);TextButton(onClick={add(at)}){Text("ثبت مصوبه برای این روز")}}}
            }
        }
    }
    if(picker) SolarPicker(LocalDate.ofEpochDay(day),{picker=false}) {picker=false;go(it.toEpochDay())}
}
