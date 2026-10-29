package ir.peygir.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.flow.collect
import androidx.compose.foundation.layout.*
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
    fun matches(name: String,query: String): Boolean {
        val key=Owners.key(name)
        return query.trim().split(Regex("\\s+")).filter{it.isNotBlank()}.all{key.contains(Owners.key(it))}
    }
    fun choices(users: List<CompanyUser>,contacts: List<String>,query: String): List<PersonChoice> {
        val active=users.filter{it.active}
        val known=active.map{Owners.key(it.name)}.toSet()
        return (active.map{PersonChoice(it.name,it)}+contacts.filter{it.isNotBlank()}.distinctBy{Owners.key(it)}.filter{Owners.key(it) !in known}.map{PersonChoice(it)})
            .filter{matches(it.name,query)}
    }
    fun assigneeAfterEdit(name: String,current: String?,users: List<CompanyUser>)=current?.takeIf{id->users.any{it.id==id && Owners.same(name,it.name)}}
}

/** Bounded and lazy even when the company directory has many people. */
@Composable private fun PersonSuggestions(choices: List<PersonChoice>,choose: (PersonChoice)->Unit) {
    if(choices.isNotEmpty()) Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            if(choices.size>5) CountLabel("افراد",choices.size,Modifier.padding(horizontal=12.dp,vertical=6.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=240.dp).testTag("person_suggestions")) {
                items(choices,key={it.user?.id ?: "contact_${Owners.key(it.name)}"}) {person->
                    Row(Modifier.fillMaxWidth().clickable{choose(person)}.heightIn(min=48.dp).padding(horizontal=12.dp,vertical=9.dp).testTag("person_suggestion_${person.user?.id ?: person.name}"),verticalAlignment=Alignment.CenterVertically) {
                        Icon(if(person.user!=null) Icons.Outlined.PersonOutline else Icons.Outlined.PersonAddAlt,null,Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(person.name,style=MaterialTheme.typography.bodyMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(if(person.user!=null) person.user.username.ifBlank{"حساب شرکت"} else "مخاطب",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1,overflow=TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** Text fields keep focus after a suggestion is chosen, so observe taps as well as focus. */
@Composable private fun suggestionTaps(open: ()->Unit): MutableInteractionSource {
    val source=remember{MutableInteractionSource()}
    val latestOpen by rememberUpdatedState(open)
    LaunchedEffect(source) {source.interactions.collect{if(it is PressInteraction.Release) latestOpen()}}
    return source
}

@Composable internal fun OwnerField(name: String,assignee: String?,users: List<CompanyUser>,contacts: List<String>,error: Boolean,change: (String,String?)->Unit) {
    var suggesting by remember {mutableStateOf(false)}
    var filtering by remember {mutableStateOf(false)}
    fun open() {filtering=false;suggesting=true}
    val taps=suggestionTaps(::open)
    val directory=remember(users,contacts){PeopleSuggestions.choices(users,contacts,"")}
    val choices=remember(directory,name,filtering){if(filtering) directory.filter{PeopleSuggestions.matches(it.name,name)} else directory}
    VoiceTextField(name,{change(it,PeopleSuggestions.assigneeAfterEdit(it,assignee,users));filtering=true;suggesting=true},label={Text("مسئول انجام *")},voiceLabel="مسئول انجام",singleLine=true,isError=error,
        modifier=Modifier.fillMaxWidth().testTag("task_owner").onFocusChanged{suggesting=it.isFocused;if(it.isFocused) filtering=false},leadingIcon={Icon(Icons.Outlined.PersonOutline,null)},interactionSource=taps,
        trailingIcon={IconButton(onClick={if(suggesting) suggesting=false else open()}){Icon(if(suggesting) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,"پیشنهاد مسئول")}})
    if(suggesting) PersonSuggestions(choices){choice->change(choice.name,choice.user?.id);suggesting=false}
    if(assignee!=null) Text("واگذار شده به ${users.find{it.id==assignee}?.name ?: name}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
    else if(name.isNotBlank() && users.isNotEmpty()) Text("برای واگذاری در گوشی مسئول، نام او را از پیشنهادها انتخاب کنید.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ParticipantsField(ids: List<String>,guests: List<String>,users: List<CompanyUser>,contacts: List<String>,change: (List<String>,List<String>)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var suggesting by remember {mutableStateOf(false)}
    val directory=remember(users,contacts){PeopleSuggestions.choices(users,contacts,"")}
    val choices=remember(directory,query,ids,guests) {directory.filter{p->p.user?.id !in ids && guests.none{Owners.same(it,p.name)} && PeopleSuggestions.matches(p.name,query)}}
    val taps=suggestionTaps{suggesting=true}
    var expanded by rememberSaveable {mutableStateOf(false)}
    fun choose(person: PersonChoice) {
        if(person.user!=null) change((ids+person.user.id).distinct(),guests) else if(guests.none{Owners.same(it,person.name)}) change(ids,guests+person.name.trim())
        query="";suggesting=false
    }
    VoiceTextField(query,{query=it;suggesting=true},label={Text("افزودن شرکت‌کننده")},voiceLabel="نام شرکت‌کننده",singleLine=true,
        modifier=Modifier.fillMaxWidth().testTag("meeting_people").onFocusChanged{suggesting=it.isFocused},leadingIcon={Icon(Icons.Outlined.Groups,null)},interactionSource=taps,
        trailingIcon={IconButton(onClick={suggesting=!suggesting}){Icon(if(suggesting) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,"پیشنهاد شرکت‌کنندگان")}})
    if(suggesting) {
        PersonSuggestions(choices,::choose)
        if(query.isNotBlank() && query.length<=200 && users.none{Owners.same(it.name,query)} && guests.none{Owners.same(it,query)}) TextButton(onClick={choose(PersonChoice(query))},modifier=Modifier.testTag("add_guest")){Icon(Icons.Outlined.Add,null);Spacer(Modifier.width(4.dp));Text("افزودن «${query.trim()}» به‌عنوان مخاطب")}
    }
    if(ids.isNotEmpty() || guests.isNotEmpty()) FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ids.take(if(expanded) ids.size else 4).forEach{id->InputChip(true,{change(ids-id,guests)},label={Text(users.find{it.id==id}?.name ?: "حساب پیشین")},trailingIcon={Icon(Icons.Outlined.Close,"حذف شرکت‌کننده",Modifier.size(16.dp))})}
        guests.take(if(expanded) guests.size else (4-ids.size).coerceAtLeast(0)).forEach{name->InputChip(true,{change(ids,guests-name)},label={Text(name)},trailingIcon={Icon(Icons.Outlined.Close,"حذف مخاطب",Modifier.size(16.dp))})}
    }
    if(ids.size+guests.size>4) TextButton(onClick={expanded=!expanded},modifier=Modifier.testTag("participants_expand")){CountLabel(if(expanded) "بستن فهرست افراد" else "همهٔ شرکت‌کنندگان",ids.size+guests.size,color=LocalContentColor.current)}
    if(guests.isNotEmpty()) Text("مخاطب بدون حساب، دعوت و اعلان دریافت نمی‌کند.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
