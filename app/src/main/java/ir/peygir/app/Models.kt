package ir.peygir.app

import java.time.LocalDate

enum class Status(val label: String) { WAITING("در انتظار"), ACTIVE("در حال انجام"), REVIEW("منتظر تأیید رئیس"), DONE("انجام‌شده") }
enum class Priority(val label: String) { LOW("کم"), NORMAL("عادی"), HIGH("مهم"), URGENT("فوری") }
data class Meeting(val id: Long = 0, val title: String = "", val date: Long = LocalDate.now().toEpochDay(), val notes: String = "", val start: Int? = 540, val end: Int? = 600, val location: String = "", val attendees: List<String> = emptyList(), val repeat: String = "NONE", val until: Long? = null, val series: String? = null, val guests: List<String> = emptyList(), val alertMinutes: Int? = null)
data class Resolution(val id: Long = 0, val meetingId: Long? = null, val title: String = "", val description: String = "", val owner: String = "", val due: Long = LocalDate.now().plusDays(1).toEpochDay(), val priority: Priority = Priority.NORMAL, val status: Status = Status.WAITING, val delayCount: Int = 0, val assignee: String? = null, val submitted: Long = 0, val approved: Long = 0, val approvedBy: String? = null)
enum class DueChangeKind(val label: String) { EXTEND("تمدید مهلت"), POSTPONE("به تعویق انداختن"), EDIT("تغییر سررسید") }
data class DueChange(val id: Long, val taskId: Long, val oldDue: Long, val newDue: Long, val changedAt: Long, val kind: DueChangeKind, val reason: String)
data class Reminder(val id: Long, val taskId: Long, val at: Long, val delivered: Boolean)
data class FollowUp(val id: Long, val taskId: Long, val text: String, val created: Long)
data class BacklogItem(val id: Long = 0, val title: String = "", val description: String = "", val owner: String = "", val created: Long = System.currentTimeMillis())
data class Recording(val id: Long = 0, val meetingId: Long? = null, val path: String, val created: Long, val seconds: Int, val transcript: String = "", val draftSession: String = "")
data class Snapshot(val meetings: List<Meeting> = emptyList(), val tasks: List<Resolution> = emptyList(), val reminders: List<Reminder> = emptyList(), val recordings: List<Recording> = emptyList(), val history: List<DueChange> = emptyList(), val draftRecordings: List<Recording> = emptyList(), val followUps: List<FollowUp> = emptyList(), val backlog: List<BacklogItem> = emptyList(), val contacts: List<String> = emptyList())
data class Proposal(val title: String, val owner: String = "", val due: LocalDate? = null, val source: String = "", val priority: Priority = Priority.NORMAL)
fun Proposal.key(): String = java.util.UUID.nameUUIDFromBytes("$source|$title|$owner|$due".toByteArray(Charsets.UTF_8)).toString()
object VoiceCommands {
    fun extract(text: String): Proposal? {
        // Offline recognition can split a command across segments and use different Persian spellings.
        val normalized=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC)
            .replace('ي','ی').replace('ى','ی').replace('ك','ک').replace('\u200c',' ')
            .replace(Regex("[\\p{M}ـ\\u200e\\u200f]"),"").replace(Regex("\\s+")," ").trim()
        val match=Regex("(?:^|[\\s،,؛;.:])عنوان(?:ش|\\s+آن)?\\b\\s*[:：،,؛;.-]*\\s*(.+?)\\s*[,،؛;.-]?\\s*\\b(?:مسئول|مسؤول|مسوول|مسول)(?:ش|\\s+آن)?\\b\\s*[:：،,؛;.-]*\\s*(.+)$").find(normalized) ?: return null
        fun clean(value: String)=value.trim(' ',':','：','،',',','؛',';','.','-')
        val title=clean(match.groupValues[1])
        val owner=clean(match.groupValues[2].split(Regex("\\b(?:مهلت|تاریخ|سررسید|موعد)\\b"),limit=2).first())
        if(title.isBlank() || owner.isBlank()) return null
        return Proposal(title,owner,due=null,source=normalized)
    }
}
object Extractor {
    private val datePattern = Regex("[1۱١][2-5۲-۵٢-٥][0-9۰-۹٠-٩]{2}[/\\-.٫][0-9۰-۹٠-٩]{1,2}[/\\-.٫][0-9۰-۹٠-٩]{1,2}")
    private val ownerPattern = Regex("(?:مسئول|مسوول|عهده‌دار|عهده دار)\\s*[:：]?\\s*(.+?)(?=\\s+(?:تا|مهلت|سررسید|اولویت|شرح|موعد)\\b|[،؛\\n.]|$)")
    fun extract(text: String, today: LocalDate = LocalDate.now()): List<Proposal> {
        val normalized = text.replace('ي','ی').replace('ك','ک').replace("\r", "")
        val blocks = mutableListOf<String>()
        normalized.split(Regex("[\\n؛]+|(?<=[.!؟])\\s+|\\s+(?:مصوبه بعدی|کار بعدی)\\s*")).forEach { raw ->
            val s = raw.trim().replace(Regex("^[\\d۰-۹٠-٩]+[.)ـ-]\\s*"), "").trim('•','-',' ')
            if (s.isNotBlank()) {
                if (blocks.isNotEmpty() && Regex("^(مسئول|مسوول|مهلت|سررسید|موعد|اولویت)\\s*[:：]").containsMatchIn(s)) blocks[blocks.lastIndex] += "، $s"
                else blocks += s
            }
        }
        return blocks.mapNotNull { block ->
            val owner = ownerPattern.find(block)?.groupValues?.get(1)?.trim().orEmpty()
            val due = Solar.parse(block) ?: when { block.contains("پس فردا") || block.contains("پس‌فردا") -> today.plusDays(2); block.contains("فردا") -> today.plusDays(1); block.contains("امروز") -> today; else -> null }
            var title = block.replace(ownerPattern, "").replace(datePattern, "")
                .replace(Regex("(?:مهلت|سررسید|موعد|اولویت)\\s*[:：]?"), "")
                .replace(Regex("^(?:مصوبه|وظیفه|کار)\\s*[:：]\\s*"), "")
                .replace(Regex("[،,\\s]+$"), "").trim()
            title = title.replace(Regex("\\s+(?:تا|تا تاریخ)\\s*$"), "").trim('،',' ',':')
            if (title.length < 3) null else Proposal(title, owner, due, block, when { block.contains("فوری") -> Priority.URGENT; block.contains("مهم") -> Priority.HIGH; else -> Priority.NORMAL })
        }.distinctBy { Triple(it.title, it.owner, it.due) }.take(50)
    }
}
