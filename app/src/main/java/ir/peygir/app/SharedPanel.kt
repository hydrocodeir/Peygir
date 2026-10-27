package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

@Composable internal fun SharedPanel(message: (String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val revision by SharedSync.revision.collectAsState();val syncing by SharedSync.working.collectAsState()
    var session by remember {mutableStateOf<SharedSession?>(null)}
    var binding by remember {mutableStateOf("")};var counts by remember {mutableStateOf(0 to 0)}
    var notes by remember {mutableStateOf(emptyList<SharedStore.Conflict>())}
    var error by remember {mutableStateOf("")};var last by remember {mutableLongStateOf(0)}
    var alerts by remember {mutableStateOf(true)};var login by remember {mutableStateOf(false)}
    var logout by remember {mutableStateOf(false)}
    var working by remember {mutableStateOf(false)};var detach by remember {mutableStateOf(false)}
    fun refresh() {SharedSync.revision.value++}
    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            val shared=SharedStore(Store.get(context));binding=shared.binding();counts=shared.counts();notes=shared.conflicts()
            val credentials=SharedCredentials(context);session=runCatching{credentials.read()}.getOrNull();alerts=credentials.alerts()
            val prefs=context.getSharedPreferences("shared_status",0);error=prefs.getString("error","").orEmpty();last=prefs.getLong("last",0)
        }
    }
    OutlinedCard(Modifier.fillMaxWidth().testTag("shared_panel")) {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("فضای سازمانی شرکت",style=MaterialTheme.typography.titleMedium)
        if(session==null) {
            Text("شما ثبت کنید؛ مدیر در گوشی خودش ببیند و نتیجهٔ پیگیری را ثبت کند.",style=MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick={login=true},enabled=!working,modifier=Modifier.fillMaxWidth()){Text("ورود به فضای مشترک")}
            if(binding.isNotEmpty()) TextButton(onClick={detach=true},enabled=!working){Text("جدا کردن اتصال قبلی از این گوشی")}
        } else {
            Text("${session!!.workspaceName} - ${session!!.name} (${when(session!!.role){"manager"->"رئیس";"deputy"->"معاون";else->"دستیار"}})",style=MaterialTheme.typography.bodyMedium)
            if(session!!.company.isNotBlank()) Text("کد شرکت: ${session!!.company}",style=MaterialTheme.typography.bodySmall)
            if(!syncing && counts.first>0) CountLabel("تغییرات در انتظار ارسال",counts.first,color=MaterialTheme.colorScheme.primary)
            else Text(if(syncing) "در حال همگام‌سازی…" else "تغییر ارسال‌نشده‌ای ندارید.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
            if(last>0) {val at=Instant.ofEpochMilli(last).atZone(ZoneId.systemDefault());Text("آخرین همگام‌سازی: ${Solar.label(at.toLocalDate())} - ${at.toLocalTime().withSecond(0).withNano(0).toString().fa()}",style=MaterialTheme.typography.labelSmall)}
            if(error.isNotBlank()) Text(error,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            Text("اعلان فوری: ${PushRegistration.status(context)}",style=MaterialTheme.typography.bodySmall)
            Row {OutlinedButton(onClick={SharedSync.request(context)},enabled=!working && !syncing){Text("همگام‌سازی اکنون")};TextButton(onClick={login=true},enabled=!working && !syncing){Text("ورود دوباره")}}
            Row {Text("یادآوری در این گوشی",Modifier.weight(1f));Switch(alerts,{value->alerts=value;SharedCredentials(context).alerts(value);SharedSync.request(context);if(!value) context.getSystemService(android.app.NotificationManager::class.java).cancelAll();Reminders.background{Reminders.process(context)}},enabled=!working)}
            TextButton(onClick={logout=true},enabled=!working && !syncing){Text("خروج از حساب")}
        }
        Text("اطلاعات دریافت‌شده آفلاین در دسترس‌اند. با اپ باز، همگام‌سازی هنگام ثبت و حدود هر ۳۰ ثانیه بررسی می‌شود؛ در پس‌زمینه حدود هر ۱۵ دقیقه و با توجه به محدودیت‌های اندروید. اعلان فوری پس از راه‌اندازی Firebase و اجازهٔ اعلان فعال می‌شود.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(notes.isNotEmpty()) {
            HorizontalDivider();CountLabel("تغییرات هم‌زمان نیازمند انتخاب",notes.size,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.titleSmall)
            notes.take(10).forEach {conflict->
                Text(conflict.title,style=MaterialTheme.typography.titleSmall)
                if(conflict.remote.optString("reason")=="parent_deleted") Text("جلسه یا مصوبهٔ مرتبط حذف شده است؛ برای حفظ این مورد، ابتدا مورد اصلی را بازیابی کنید.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
                Text("این گوشی: ${sharedSummary(conflict.local,false)}",style=MaterialTheme.typography.bodySmall)
                Text("نسخهٔ مشترک: ${sharedSummary(conflict.remote.getJSONObject("payload"),conflict.remote.getBoolean("deleted"))}\nثبت‌شده توسط ${conflict.remote.optString("actor")}",style=MaterialTheme.typography.bodySmall)
                Row {
                    listOf(true to "نسخهٔ این گوشی",false to "نسخهٔ مشترک").forEach{(local,label)->TextButton(onClick={scope.launch {working=true;try {withContext(Dispatchers.IO){SharedStore(Store.get(context)).resolve(conflict.kind,conflict.localId,local);Store.get(context).externalChanges.value++};refresh();SharedSync.request(context)} finally {working=false}}},enabled=!working && !syncing){Text(label)}}
                }
            }
        }
    }}
    if(logout) AlertDialog(onDismissRequest={logout=false},title={Text("خروج از حساب شرکت؟")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){if(counts.first>0) CountLabel("تغییرات ارسال‌نشده",counts.first);Text(if(counts.first>0) "ابتدا همگام‌سازی کنید. خروج، نسخهٔ اطلاعات شرکت را از این گوشی پاک می‌کند." else "نسخهٔ اطلاعات شرکت از این گوشی پاک می‌شود و پس از ورود دوباره دریافت خواهد شد. اطلاعات سرور حفظ می‌شود.")}},confirmButton={TextButton(onClick={scope.launch{working=true;try{withContext(Dispatchers.IO){SharedSync.disconnect(context)};session=null;logout=false;refresh()}finally{working=false}}},enabled=!working && counts.first==0){Text("خروج")}},dismissButton={TextButton(onClick={logout=false}){Text("انصراف")}})
    if(login) SharedLoginDialog({login=false},{s->session=s;login=false;refresh();SharedSync.request(context)})
    if(detach) AlertDialog(onDismissRequest={if(!working) detach=false},title={Text("جدا کردن فضای مشترک؟")},text={Text("اطلاعات فعلی روی این گوشی می‌ماند و دیگر به فضای مشترک ارسال نمی‌شود. این کار اطلاعات گوشی مدیر را حذف نمی‌کند. برای اتصال به فضای دیگری، ابتدا از اطلاعات فعلی پشتیبان بگیرید.")},confirmButton={TextButton(onClick={scope.launch {working=true;try {withContext(Dispatchers.IO){SharedSync.disconnect(context,true)};detach=false;refresh()} finally {working=false}}},enabled=!working){Text("جدا کردن")}},dismissButton={TextButton(onClick={detach=false},enabled=!working){Text("انصراف")}})
}
private fun sharedSummary(p: org.json.JSONObject?,deleted: Boolean): String {
    if(deleted || p==null) return "حذف‌شده"
    val parts=mutableListOf<String>()
    listOf("title","owner","text","description","notes","reason").forEach {key->p.optString(key).takeIf{it.isNotBlank()}?.let{parts+=it.take(300)}}
    p.optString("status").takeIf{it.isNotBlank()}?.let{value->parts+=runCatching{Status.valueOf(value).label}.getOrDefault(value)}
    if(p.has("due")) parts+=Solar.label(java.time.LocalDate.ofEpochDay(p.getLong("due")))
    return parts.joinToString(" - ")
}
@Composable internal fun SharedLoginDialog(dismiss: ()->Unit,connected: (SharedSession)->Unit,onboarding: Boolean=false) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val previous=remember{runCatching{SharedCredentials(context).read()}.getOrNull()}
    var endpoint by remember {mutableStateOf(previous?.endpoint ?: BuildConfig.SYNC_URL)};var company by remember{mutableStateOf(previous?.company.orEmpty())};var username by remember{mutableStateOf("")};var password by remember{mutableStateOf("")}
    val legacy=previous!=null && previous.company.isEmpty() && endpoint.trimEnd('/')==previous.endpoint
    val validCompany=company.trim().lowercase(java.util.Locale.ROOT).matches(Regex("[a-z0-9][a-z0-9-]{2,31}"))
    var working by remember {mutableStateOf(false)};var error by remember {mutableStateOf("")};var pending by remember {mutableStateOf<SharedSession?>(null)}
    var existing by remember {mutableIntStateOf(0)}
    fun connect(s: SharedSession) {scope.launch {working=true;try {withContext(Dispatchers.IO){SharedSync.connect(context,s)};password="";connected(s)} catch(e:Exception){error=e.message ?: "اتصال ناموفق بود."} finally {working=false}}}
    val submit: ()->Unit = {
        val ready=pending
        if(ready!=null) connect(ready) else scope.launch {
            working=true;error=""
            try {
                val s=withContext(Dispatchers.IO){SharedSync.login(endpoint,username,password,company)}
                val count=withContext(Dispatchers.IO){val store=Store.get(context);val bound=SharedStore(store).binding();if(bound.isEmpty()) store.snapshot().let{it.tasks.size+it.meetings.size} else 0}
                if(count>0) {existing=count;pending=s} else {withContext(Dispatchers.IO){SharedSync.connect(context,s)};password="";connected(s)}
            } catch(e:Exception){error=e.message?.takeIf{it.any{c->c in 'آ'..'ی'}} ?: "ورود ناموفق بود؛ نشانی و اینترنت را بررسی کنید."}
            finally {working=false}
        }
    }
    FullEditor(if(pending==null) "ورود به فضای مشترک" else "اشتراک اطلاعات این گوشی",working,dismiss,submit,
        canSave=pending!=null || (endpoint.isNotBlank() && (validCompany || legacy && company.isBlank()) && username.isNotBlank() && password.isNotBlank()),feedback=error,saveLabel=if(pending==null) "ورود" else "اتصال",dismissible=!onboarding) {
        if(pending==null) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(endpoint,{endpoint=it},label={Text("نشانی سرویس")},singleLine=true,modifier=Modifier.fillMaxWidth(),textStyle=LocalTextStyle.current.copy(textDirection=TextDirection.Ltr),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
                OutlinedTextField(company,{company=it},label={Text("کد شرکت")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("login_company"),textStyle=LocalTextStyle.current.copy(textDirection=TextDirection.Ltr),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii))
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(username,{username=it},label={Text("نام کاربری")},singleLine=true,modifier=Modifier.fillMaxWidth(),textStyle=LocalTextStyle.current.copy(textDirection=TextDirection.Ltr),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Ascii))
                OutlinedTextField(password,{password=it},label={Text("رمز ورود")},singleLine=true,modifier=Modifier.fillMaxWidth(),textStyle=LocalTextStyle.current.copy(textDirection=TextDirection.Ltr),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),visualTransformation=PasswordVisualTransformation())
            }
            Text("نشانی، کد شرکت و حساب ورود را از مدیر شرکت بگیرید. رمز فقط برای ورود استفاده می‌شود و در گوشی ذخیره نمی‌شود.",style=MaterialTheme.typography.bodySmall)
        } else {CountLabel("جلسات و مصوبات این گوشی",existing);Text("اطلاعات فعلی این گوشی به فضای مشترک «${pending!!.workspaceName}» افزوده می‌شود و برای رئیس و دستیار قابل مشاهده خواهد بود. اطلاعات قبلی جایگزین نمی‌شود. ادامه می‌دهید؟")}
        if(working) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
