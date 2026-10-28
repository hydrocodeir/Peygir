package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

@Composable internal fun CompanyPeoplePicker(initial: Set<String>,single: Boolean,dismiss: ()->Unit,choose: (Set<String>)->Unit) {
    val users by Organization.members.collectAsState()
    var selected by remember {mutableStateOf(initial)};var query by remember {mutableStateOf("")}
    FullEditor(if(single) "حساب مسئول" else "شرکت‌کنندگان شرکت",false,dismiss,{choose(selected)},saveLabel="انتخاب") {
        VoiceTextField(query,{query=it},voiceLabel="جست‌وجوی نام",label={Text("جست‌وجوی نام")},modifier=Modifier.fillMaxWidth())
        if(single) TextButton(onClick={selected=emptySet()}){Text("بدون واگذاری؛ فقط رئیس و دستیار")}
        if(users.isEmpty()) Text("ابتدا در تنظیمات همگام‌سازی کنید و حساب کاربران را بسازید.")
        users.filter{(it.active || it.id in selected) && (query.isBlank() || it.name.contains(query.trim(),true))}.forEach {u->
            Row(Modifier.fillMaxWidth().clickable(enabled=u.active || u.id in selected){selected=if(u.id in selected) selected-u.id else if(single) setOf(u.id) else selected+u.id}.padding(vertical=6.dp)) {
                Checkbox(u.id in selected,{checked->selected=if(checked) {if(single) setOf(u.id) else selected+u.id} else selected-u.id},enabled=u.active || u.id in selected)
                Text(u.name+if(u.active) "" else " (غیرفعال)",Modifier.padding(top=12.dp))
            }
        }
    }
}

private val accountRoles=linkedMapOf("manager" to "مدیر","assistant" to "دستیار","deputy" to "کاربر")
@Composable internal fun AccountRolePicker(role: String,roles: List<String>,enabled: Boolean=true,change: (String)->Unit) {
    var menu by remember {mutableStateOf(false)}
    var query by remember {mutableStateOf("")}
    Box(Modifier.fillMaxWidth()) {
        Row(verticalAlignment=Alignment.CenterVertically) {OutlinedButton(onClick={query="";menu=true},enabled=enabled && roles.size>1,modifier=Modifier.weight(1f).testTag("account_role")) {Text("نقش: ${accountRoles[role] ?: role}")};DictationButton("نقش حساب",enabled && roles.size>1){query=it;menu=true}}
        DropdownMenu(menu,{menu=false}) {if(query.isNotBlank()) DropdownMenuItem(text={Text("نمایش همهٔ نقش‌ها")},onClick={query=""});roles.filter{PeopleSuggestions.matches(accountRoles[it] ?: it,query)}.forEach{key->DropdownMenuItem(text={Text(accountRoles[key] ?: key)},onClick={menu=false;change(key)},modifier=Modifier.testTag("account_role_$key"))}}
    }
}
private fun accountSnapshot(u: CompanyUser)=JSONObject().put("name",u.name).put("username",u.username).put("role",u.role).put("active",u.active)
@Composable internal fun CompanyAccountsPanel(request: ((JSONObject)->JSONObject)?=null) {
    val c=LocalContext.current
    val syncRevision by SharedSync.revision.collectAsState()
    if(!SharedCredentials(c).connected() || OrgAccess.role(c) !in listOf("manager","assistant")) return
    val users by Organization.members.collectAsState()
    val scope=rememberCoroutineScope();val clipboard=LocalClipboardManager.current
    val company=OrgAccess.company(c);val enhanced=company.isNotEmpty();val manager=OrgAccess.role(c)=="manager"
    fun canManage(u: CompanyUser)=!enhanced || manager || u.role=="deputy"
    var open by remember {mutableStateOf(false)};var create by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<CompanyUser?>(null)}
    var working by remember {mutableStateOf(false)};var error by remember {mutableStateOf("")}
    var confirm by remember {mutableStateOf<Pair<CompanyUser,String>?>(null)}
    var credentials by remember {mutableStateOf("")}
    fun call(payload: JSONObject,done: ()->Unit={}) {scope.launch {working=true;error="";try {withContext(Dispatchers.IO){request?.invoke(payload) ?: Organization.account(c,payload)};done()} catch(e:CancellationException){throw e} catch(e:Exception){error=e.message ?: "ارتباط برقرار نشد."} finally {working=false}}}
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("حساب‌های شرکت",style=MaterialTheme.typography.titleMedium)
        Text("کاربر فقط مصوبات خودش و جلساتی را که دعوت شده می‌بیند.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={open=true;call(JSONObject().put("action","list"))}){Text("مدیریت حساب‌ها")}
    }}
    if(open) FullEditor("حساب‌های شرکت",working,{open=false},{error="";create=true},saveLabel="کاربر جدید",feedback=error) {
        users.forEach {u->OutlinedCard(Modifier.fillMaxWidth().testTag("account_${u.id}")) {Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {Text(u.name,style=MaterialTheme.typography.titleMedium);Text("${accountRoles[u.role] ?: u.role} - ${if(u.active) "فعال" else "غیرفعال"} - ${u.username}",style=MaterialTheme.typography.bodySmall)}
            if(canManage(u)) PageActions("گزینه‌های حساب ${u.name}",buildList {
                add(PageAction("ویرایش حساب",{error="";editing=u},"account_edit_${u.id}"))
                add(PageAction("بازیابی رمز",{error="";confirm=u to "reset"},"account_reset_${u.id}"))
                if(u.id!=OrgAccess.id(c)) add(PageAction(if(u.active) "غیرفعال کردن" else "فعال کردن",{error="";confirm=u to "toggle"},"account_toggle_${u.id}"))
                if(enhanced && u.id!=OrgAccess.id(c)) add(PageAction("حذف کاربر",{error="";confirm=u to "delete"},"account_delete_${u.id}",true))
            },enabled=!working)
        }}}
    }
    if(create) {
        var name by remember {mutableStateOf("")};var username by remember {mutableStateOf("")};var role by remember {mutableStateOf("deputy")}
        val password=remember {UUID.randomUUID().toString().replace("-","")};val id=remember {UUID.randomUUID().toString()}
        val roles=if(enhanced && manager) accountRoles.keys.toList() else listOf("deputy")
        FullEditor("کاربر جدید",working,{create=false},{call(JSONObject().put("action","create").put("id",id).put("name",name).put("username",username.trim().lowercase(java.util.Locale.ROOT)).put("role",role).put("password",password)) {credentials="${if(company.isNotEmpty()) "کد شرکت: $company\n" else ""}نام: $name\nنام کاربری: ${username.trim().lowercase(java.util.Locale.ROOT)}\nرمز ورود: $password";create=false}},canSave=name.isNotBlank() && username.trim().lowercase(java.util.Locale.ROOT).matches(Regex("[a-z0-9][a-z0-9_.-]{2,63}")),feedback=error,saveLabel="ساخت حساب") {
            VoiceTextField(name,{name=it},voiceLabel="نام و نام خانوادگی",label={Text("نام و نام خانوادگی")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(username,{username=it},label={Text("نام کاربری انگلیسی")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),modifier=Modifier.fillMaxWidth())
            AccountRolePicker(role,roles,!working){role=it}
            Text(when(role){"manager"->"مدیر به اطلاعات شرکت و مدیریت کاربران دسترسی دارد و انجام مصوبات را تأیید می‌کند.";"assistant"->"دستیار جلسات و مصوبات را ثبت و پیگیری می‌کند؛ تعیین نقش مدیر و دستیار با مدیر است.";else->"کاربر فقط مصوبات واگذارشده و دعوت‌های خودش را می‌بیند."},style=MaterialTheme.typography.bodySmall)
            Text("رمز قوی خودکار ساخته می‌شود و پس از ثبت نمایش داده خواهد شد.",style=MaterialTheme.typography.labelSmall)
        }
    }
    editing?.let {u->key(u.id) {
        var name by remember {mutableStateOf(u.name)};var username by remember {mutableStateOf(u.username)};var role by remember {mutableStateOf(u.role)}
        val lastManager=u.active && u.role=="manager" && users.count{it.active && it.role=="manager"}<=1
        val roles=if(enhanced && manager && !lastManager) accountRoles.keys.toList() else listOf(u.role)
        FullEditor("ویرایش حساب",working,{editing=null},{call(JSONObject().put("action","update").put("id",u.id).put("name",name).put("username",username.trim().lowercase(java.util.Locale.ROOT)).put("role",role).put("active",u.active).put("expected",accountSnapshot(u))) {editing=null}},canSave=name.isNotBlank() && username.trim().lowercase(java.util.Locale.ROOT).matches(Regex("[a-z0-9][a-z0-9_.-]{2,63}")),feedback=error,saveLabel="ذخیرهٔ تغییرات") {
            VoiceTextField(name,{name=it},voiceLabel="نام و نام خانوادگی",label={Text("نام و نام خانوادگی")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(username,{username=it},label={Text("نام کاربری انگلیسی")},singleLine=true,enabled=enhanced,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),modifier=Modifier.fillMaxWidth())
            AccountRolePicker(role,roles,!working){role=it}
            if(enhanced) Text("تغییر نام کاربری یا نقش، ورود دوبارهٔ آن کاربر را الزامی می‌کند.",style=MaterialTheme.typography.bodySmall)
            if(lastManager) Text("شرکت باید دست‌کم یک مدیر فعال داشته باشد.",style=MaterialTheme.typography.bodySmall)
        }
    }}
    confirm?.let {(u,action)->AlertDialog(onDismissRequest={if(!working) confirm=null},title={Text(when(action){"delete"->"حذف ${u.name}؟";"reset"->"رمز تازه برای ${u.name}؟";else->if(u.active) "غیرفعال کردن ${u.name}؟" else "فعال کردن ${u.name}؟"})},text={Column {Text(when(action){"delete"->"حساب، نشست‌ها و دسترسی ورود حذف می‌شوند. مصوبات و سوابق باقی می‌مانند و مسئول آن‌ها قابل تغییر است.";"reset"->"نشست‌های قبلی باطل می‌شوند؛ رمز تازه را در اختیار همین فرد بگذارید.";else->"مصوبات و سوابق حفظ می‌شوند. حساب غیرفعال نمی‌تواند به سرویس وارد شود."});if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton(onClick={
        val password=UUID.randomUUID().toString().replace("-","")
        val p=JSONObject().put("action",if(action=="toggle") "update" else action).put("id",u.id).put("expected",accountSnapshot(u))
        if(action=="reset") p.put("password",password)
        if(action=="toggle") p.put("name",u.name).put("username",u.username).put("role",u.role).put("active",!u.active)
        call(p){confirm=null;if(action=="reset") credentials="نام کاربری: ${u.username}\nرمز تازه: $password"}
    },enabled=!working){Text(if(action=="delete") "حذف کاربر" else "تأیید",color=if(action=="delete") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)}},dismissButton={TextButton(onClick={confirm=null},enabled=!working){Text("انصراف")}})}
    if(credentials.isNotEmpty()) AlertDialog(onDismissRequest={credentials=""},title={Text("اطلاعات ورود")},text={Text(credentials)},confirmButton={TextButton(onClick={clipboard.setText(AnnotatedString(credentials))}){Text("کپی اطلاعات ورود")}},dismissButton={TextButton(onClick={credentials=""}){Text("بستن")}})
}

@Composable internal fun DeputyTaskDetails(task: Resolution,data: Snapshot,busy: Boolean,dismiss: ()->Unit,status: (Status)->Unit,followUp: (String,()->Unit)->Unit,feedback: String) {
    TaskSummary(task,data,busy,dismiss,status,followUp,feedback)
}
