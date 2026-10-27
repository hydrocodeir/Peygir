package ir.peygir.app

data class WidgetAgendaRow(val day: Long,val meeting: Meeting?=null,val task: Resolution?=null,val showDate: Boolean=true) {
    val stableId: Long get()=meeting?.id ?: task?.id ?: Long.MIN_VALUE+(day-WidgetDays.min)
}
object WidgetAgenda {
    const val DAYS=30
    fun rows(data: Snapshot,meetings: Boolean,today: Long): List<WidgetAgendaRow> =
        (WidgetDays.clamp(today)..(WidgetDays.clamp(today)+DAYS-1).coerceAtMost(WidgetDays.max)).flatMap {day->
            if(meetings) {
                val records=MeetingSchedule.forDay(data,day)
                if(records.isEmpty()) listOf(WidgetAgendaRow(day)) else records.mapIndexed{index,m->WidgetAgendaRow(day,meeting=m,showDate=index==0)}
            } else {
                val records=data.tasks.filter{it.due==day}.sortedWith(compareBy<Resolution>{it.status==Status.DONE}.thenByDescending{it.priority.ordinal}.thenBy{it.id})
                if(records.isEmpty()) listOf(WidgetAgendaRow(day)) else records.mapIndexed{index,t->WidgetAgendaRow(day,task=t,showDate=index==0)}
            }
        }
}
