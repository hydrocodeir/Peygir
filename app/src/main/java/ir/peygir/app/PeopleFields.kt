package ir.peygir.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal data class PersonChoice(val name: String,val user: CompanyUser?=null)
internal object PeopleSuggestions {
    fun matches(name: String,query: String)=query.trim().split(Regex("\\s+")).filter{it.isNotBlank()}.all{Owners.key(name).contains(Owners.key(it))}
    fun choices(users: List<CompanyUser>,contacts: List<String>,query: String): List<PersonChoice> {
        val active=users.filter{it.active}
        return (active.map{PersonChoice(it.name,it)}+contacts.filter{n->n.isNotBlank() && active.none{Owners.same(it.name,n)}}.distinctBy{Owners.key(it)}.map{PersonChoice(it)})
            .filter{matches(it.name,query)}.take(5)
    }
    fun assigneeAfterEdit(name: String,current: String?,users: List<CompanyUser>)=current?.takeIf{id->users.any{it.id==id && Owners.same(name,it.name)}}
}

@Composable private fun PersonSuggestions(choices: List<PersonChoice>,choose: (PersonChoice)->Unit) {
    if(choices.isNotEmpty()) Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {choices.forEach{person->
            Row(Modifier.fillMaxWidth().clickable{choose(person)}.heightIn(min=48.dp).padding(horizontal=12.dp,vertical=9.dp).testTag("person_suggestion_${person.user?.id ?: person.name}"),verticalAlignment=Alignment.CenterVertically) {
                Icon(if(person.user!=null) Icons.Outlined.PersonOutline else Icons.Outlined.PersonAddAlt,null,Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(person.name,Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium)
                Text(if(person.user!=null) person.user.username.ifBlank{"حساب شرکت"} else "مخاطب",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }}
    }
}

@Composable internal fun OwnerField(name: String,assignee: String?,users: List<CompanyUser>,contacts: List<String>,error: Boolean,change: (String,String?)->Unit) {
    var suggesting by remember {mutableStateOf(false)}
    VoiceTextField(name,{change(it,PeopleSuggestions.assigneeAfterEdit(it,assignee,users));suggesting=true},label={Text("مسئول انجام *")},voiceLabel="مسئول انجام",singleLine=true,isError=error,
        modifier=Modifier.fillMaxWidth().onFocusChanged{suggesting=it.isFocused},leadingIcon={Icon(Icons.Outlined.PersonOutline,null)})
    if(suggesting) PersonSuggestions(PeopleSuggestions.choices(users,contacts,name)){choice->change(choice.name,choice.user?.id);suggesting=false}
    if(assignee!=null) Text("واگذار شده به ${users.find{it.id==assignee}?.name ?: name}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
    else if(name.isNotBlank() && users.isNotEmpty()) Text("برای واگذاری در گوشی مسئول، نام او را از پیشنهادها انتخاب کنید.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ParticipantsField(ids: List<String>,guests: List<String>,users: List<CompanyUser>,contacts: List<String>,change: (List<String>,List<String>)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var suggesting by remember {mutableStateOf(false)}
    val choices=PeopleSuggestions.choices(users.filter{it.id !in ids},contacts.filter{n->guests.none{Owners.same(it,n)}},query)
    fun choose(person: PersonChoice) {
        if(person.user!=null) change((ids+person.user.id).distinct(),guests) else if(guests.none{Owners.same(it,person.name)}) change(ids,guests+person.name.trim())
        query="";suggesting=false
    }
    VoiceTextField(query,{query=it;suggesting=true},label={Text("افزودن شرکت‌کننده")},voiceLabel="نام شرکت‌کننده",singleLine=true,
        modifier=Modifier.fillMaxWidth().testTag("meeting_people").onFocusChanged{suggesting=it.isFocused},leadingIcon={Icon(Icons.Outlined.Groups,null)})
    if(suggesting) {
        PersonSuggestions(choices,::choose)
        if(query.isNotBlank() && query.length<=200 && users.none{Owners.same(it.name,query)} && guests.none{Owners.same(it,query)}) TextButton(onClick={choose(PersonChoice(query))},modifier=Modifier.testTag("add_guest")){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(4.dp));Text("افزودن «${query.trim()}» به‌عنوان مخاطب")}
    }
    if(ids.isNotEmpty() || guests.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ids.forEach{id->InputChip(true,{change(ids-id,guests)},label={Text(users.find{it.id==id}?.name ?: "حساب پیشین")},trailingIcon={Icon(Icons.Outlined.Close,"حذف شرکت‌کننده",Modifier.size(16.dp))})}
        guests.forEach{name->InputChip(true,{change(ids,guests-name)},label={Text(name)},trailingIcon={Icon(Icons.Outlined.Close,"حذف مخاطب",Modifier.size(16.dp))})}
    }
    if(guests.isNotEmpty()) Text("مخاطب بدون حساب، دعوت و اعلان دریافت نمی‌کند.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
