package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        OutlinedTextField(query,{query=it},label={Text("جست‌وجوی نام یا معاونت")},modifier=Modifier.fillMaxWidth())
        if(single) TextButton(onClick={selected=emptySet()}){Text("بدون واگذاری؛ فقط رئیس و دستیار")}
        if(users.isEmpty()) Text("ابتدا در تنظیمات همگام‌سازی کنید و حساب معاون‌ها را بسازید.")
        users.filter{(it.active || it.id in selected) && (query.isBlank() || it.name.contains(query.trim(),true))}.forEach {u->
            Row(Modifier.fillMaxWidth().clickable(enabled=u.active || u.id in selected){selected=if(u.id in selected) selected-u.id else if(single) setOf(u.id) else selected+u.id}.padding(vertical=6.dp)) {
                Checkbox(u.id in selected,{checked->selected=if(checked) {if(single) setOf(u.id) else selected+u.id} else selected-u.id},enabled=u.active || u.id in selected)
                Text(u.name+if(u.active) "" else " (غیرفعال)",Modifier.padding(top=12.dp))
            }
        }
    }
}

@Composable internal fun CompanyAccountsPanel() {
    val c=LocalContext.current
    val syncRevision by SharedSync.revision.collectAsState()
    if(!SharedCredentials(c).connected() || OrgAccess.role(c) !in listOf("manager","assistant")) return
    val users by Organization.members.collectAsState()
    val scope=rememberCoroutineScope();val clipboard=LocalClipboardManager.current
    var open by remember {mutableStateOf(false)};var create by remember {mutableStateOf(false)}
    var working by remember {mutableStateOf(false)};var error by remember {mutableStateOf("")}
    var confirm by remember {mutableStateOf<Pair<CompanyUser,String>?>(null)}
    var credentials by remember {mutableStateOf("")}
    fun call(payload: JSONObject,done: ()->Unit={}) {scope.launch {working=true;error="";try {withContext(Dispatchers.IO){Organization.account(c,payload)};done()} catch(e:Exception){error=e.message ?: "ارتباط برقرار نشد."} finally {working=false}}}
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("حساب‌های شرکت",style=MaterialTheme.typography.titleMedium)
        Text("معاون فقط مصوبات خودش و جلساتی را که دعوت شده می‌بیند.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={open=true;call(JSONObject().put("action","list"))}){Text("مدیریت حساب‌ها")}
    }}
    if(open) FullEditor("حساب‌های شرکت",working,{open=false},{create=true},saveLabel="معاون جدید",feedback=error) {
        users.forEach {u->OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
            Text(u.name,style=MaterialTheme.typography.titleMedium)
            Text("${when(u.role){"manager"->"رئیس";"assistant"->"دستیار";else->"معاون"}} - ${if(u.active) "فعال" else "غیرفعال"} - ${u.username}",style=MaterialTheme.typography.bodySmall)
            Row {TextButton(onClick={confirm=u to "reset"},enabled=!working){Text("بازیابی رمز")};if(u.role=="deputy") TextButton(onClick={confirm=u to "update"},enabled=!working){Text(if(u.active) "غیرفعال کردن" else "فعال کردن")}}
        }}}
    }
    if(create) {
        var name by remember {mutableStateOf("")};var username by remember {mutableStateOf("")}
        val password=remember {UUID.randomUUID().toString().replace("-","")};val id=remember {UUID.randomUUID().toString()}
        FullEditor("حساب معاون",working,{create=false},{call(JSONObject().put("action","create").put("id",id).put("name",name).put("username",username.trim().lowercase()).put("role","deputy").put("password",password)) {credentials="نام: $name\nنام کاربری: ${username.trim().lowercase()}\nرمز ورود: $password";create=false}},canSave=name.isNotBlank() && username.length>=3,feedback=error,saveLabel="ساخت حساب") {
            OutlinedTextField(name,{name=it},label={Text("نام و نام خانوادگی / معاونت")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(username,{username=it},label={Text("نام کاربری انگلیسی")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii),modifier=Modifier.fillMaxWidth())
            Text("رمز قوی خودکار ساخته می‌شود و پس از ثبت نمایش داده خواهد شد. این حساب فقط به مصوبات تخصیص‌یافته و دعوت‌های خودش دسترسی دارد.")
        }
    }
    confirm?.let {(u,action)->AlertDialog(onDismissRequest={if(!working) confirm=null},title={Text(if(action=="reset") "رمز تازه برای ${u.name}؟" else if(u.active) "غیرفعال کردن ${u.name}؟" else "فعال کردن ${u.name}؟")},text={Column {Text(if(action=="reset") "نشست‌های قبلی باطل می‌شوند؛ رمز تازه را در اختیار همین فرد بگذارید." else "مصوبات و سوابق حفظ می‌شوند. حساب غیرفعال نمی‌تواند به سرویس وارد شود.");if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)}},confirmButton={TextButton(onClick={
        val password=UUID.randomUUID().toString().replace("-","")
        val p=JSONObject().put("action",action).put("id",u.id)
        if(action=="reset") p.put("password",password) else p.put("name",u.name).put("active",!u.active)
        call(p){confirm=null;if(action=="reset") credentials="نام کاربری: ${u.username}\nرمز تازه: $password"}
    },enabled=!working){Text("تأیید")}},dismissButton={TextButton(onClick={confirm=null},enabled=!working){Text("انصراف")}})}
    if(credentials.isNotEmpty()) AlertDialog(onDismissRequest={credentials=""},title={Text("اطلاعات ورود")},text={Text(credentials)},confirmButton={TextButton(onClick={clipboard.setText(AnnotatedString(credentials))}){Text("کپی اطلاعات ورود")}},dismissButton={TextButton(onClick={credentials=""}){Text("بستن")}})
}

@Composable internal fun DeputyTaskDetails(task: Resolution,data: Snapshot,busy: Boolean,dismiss: ()->Unit,status: (Status)->Unit,followUp: (String,()->Unit)->Unit,feedback: String) {
    TaskSummary(task,data,busy,dismiss,status,followUp,feedback)
}
