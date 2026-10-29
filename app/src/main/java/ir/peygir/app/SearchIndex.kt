package ir.peygir.app

/** Normalize once per data revision; queries use the same Persian rules across screens. */
internal object SearchText {
    fun clean(text: String)=java.text.Normalizer.normalize(text,java.text.Normalizer.Form.NFKC)
        .replace('ي','ی').replace('ى','ی').replace('ك','ک').replace('\u200c',' ').replace("ـ","")
        .replace(Regex("[\\u064B-\\u065F\\u0670]"),"").latin().trim().lowercase(java.util.Locale.ROOT)
    fun tokens(query: String)=clean(query).split(Regex("\\s+")).filter{it.isNotBlank()}
    fun matches(text: String,tokens: List<String>)=tokens.all{text.contains(it)}
    fun meeting(m: Meeting,people: Map<String,CompanyUser>)=clean(listOf(m.title,m.location,m.guests.joinToString(" "),m.attendees.mapNotNull{people[it]?.name}.joinToString(" ")).joinToString(" "))
    fun task(t: Resolution,meetings: Map<Long,Meeting>)=clean(listOf(t.title,t.owner,t.description,meetings[t.meetingId]?.title.orEmpty()).joinToString(" "))
}
