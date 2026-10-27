package ir.peygir.app

import org.json.JSONObject
import java.time.LocalDate

data class MeetingConflict(val meeting: Meeting,val people: Set<String>)

object MeetingConflicts {
    /** Advisory only: compares the current draft/series against the authorized local calendar. */
    fun find(draft: Meeting,existing: List<Meeting>): List<MeetingConflict> {
        if(draft.attendees.isEmpty()) return emptyList()
        val occurrences=runCatching{MeetingRecurrence.expand(draft)}.getOrElse{return emptyList()}
        val byDay=existing.filter{(draft.id==0L || it.id!=draft.id) && it.start!=null && it.end!=null}.groupBy{it.date}
        val selected=draft.attendees.toSet()
        return occurrences.flatMap {candidate->
            byDay[candidate.date].orEmpty().mapNotNull {other->
                if(candidate.start==null || candidate.end==null || candidate.start>=other.end!! || candidate.end<=other.start!!) null
                else other.attendees.toSet().intersect(selected).takeIf{it.isNotEmpty()}?.let{MeetingConflict(other,it)}
            }
        }.distinctBy{it.meeting.id to it.meeting.date}.sortedWith(compareBy<MeetingConflict>{it.meeting.date}.thenBy{it.meeting.start}.thenBy{it.meeting.id})
    }
}

object MeetingCopies {
    fun draft(source: Meeting,day: Long=LocalDate.now().toEpochDay())=source.copy(id=0,date=WidgetDays.clamp(day),repeat="NONE",until=null,series=null,attendees=source.attendees.distinct())
    internal fun encode(draft: Meeting)=JSONObject().put("title",draft.title).put("date",draft.date).put("notes",draft.notes).meetingOrganization(draft).toString()
    internal fun decode(encoded: String): Meeting=JSONObject(encoded).let {p->
        Meeting(title=p.getString("title"),date=p.getLong("date"),notes=p.optString("notes")).fromOrganization(p).copy(id=0,repeat="NONE",until=null,series=null)
    }
}
