package ir.peygir.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object FirstRun {
    const val NOTIFICATIONS=0;const val EXACT=1;const val LOGIN=2
    private fun prefs(c: Context)=c.getSharedPreferences("first_run",0)
    fun complete(c: Context)=prefs(c).edit().putBoolean("complete",true).commit()
    fun stage(c: Context)=prefs(c).getInt("stage",NOTIFICATIONS).coerceIn(NOTIFICATIONS,LOGIN)
    fun stage(c: Context,value: Int) {prefs(c).edit().putInt("stage",value).commit()}
    fun asked(c: Context)=prefs(c).getBoolean("notifications_asked",false)
    fun markAsked(c: Context) {prefs(c).edit().putBoolean("notifications_asked",true).commit()}
    fun needsSetup(c: Context): Boolean {
        if(prefs(c).getBoolean("complete",false)) return false
        val store=Store.get(c)
        val legacy=SharedCredentials(c).connected() || SharedStore(store).binding().isNotBlank() || store.snapshot().let{it.tasks.isNotEmpty() || it.meetings.isNotEmpty() || it.recordings.isNotEmpty() || it.backlog.isNotEmpty()}
        if(legacy) {complete(c);return false}
        return true
    }
    fun settingsIntent(c: Context,exact: Boolean): Intent = if(exact && Build.VERSION.SDK_INT>=31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${c.packageName}")) else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName)
}

@Composable internal fun PeygirEntry(autoAsk: Boolean=true,content: @Composable ()->Unit) {
    val c=LocalContext.current
    var setup by remember {mutableStateOf<Boolean?>(null)}
    LaunchedEffect(Unit) {setup=withContext(Dispatchers.IO){runCatching{FirstRun.needsSetup(c)}.getOrDefault(false)}}
    when(setup) {
        null->PeygirTheme("system"){Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator()}}
        false->content()
        true->PeygirTheme("system") {FirstRunScreen(autoAsk){FirstRun.complete(c);setup=false}}
    }
}

@Composable internal fun FirstRunScreen(autoAsk: Boolean=true,finished: ()->Unit) {
    val c=LocalContext.current
    var stage by remember {mutableIntStateOf(FirstRun.stage(c))}
    var allowed by remember {mutableStateOf(Reminders.allowed(c))}
    var exact by remember {mutableStateOf(Reminders.exact(c))}
    var error by remember {mutableStateOf("")}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun advance(next: Int) {FirstRun.stage(c,next);stage=next;error=""}
    val notification=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {allowed=Reminders.allowed(c);advance(FirstRun.EXACT)}
    fun request() {
        error=""
        if(stage==FirstRun.NOTIFICATIONS && Build.VERSION.SDK_INT>=33 && !FirstRun.asked(c)) {
            FirstRun.markAsked(c);notification.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            try {c.startActivity(FirstRun.settingsIntent(c,stage==FirstRun.EXACT))}
            catch(_:Exception) {error="صفحهٔ دسترسی باز نشد؛ می‌توانید بعداً از تنظیمات گوشی فعال کنید."}
        }
    }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,e->if(e==Lifecycle.Event.ON_RESUME) {allowed=Reminders.allowed(c);exact=Reminders.exact(c)}}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(stage,allowed,exact) {
        if(stage==FirstRun.NOTIFICATIONS && allowed) advance(FirstRun.EXACT)
        else if(stage==FirstRun.EXACT && exact) advance(FirstRun.LOGIN)
        else if(stage==FirstRun.NOTIFICATIONS && autoAsk && Build.VERSION.SDK_INT>=33 && !FirstRun.asked(c)) request()
    }
    Surface(Modifier.fillMaxSize()) {
        if(stage==FirstRun.LOGIN) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("پیگیر")}
            SharedLoginDialog({}, {SharedSync.request(c);finished()},onboarding=true)
        } else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp).testTag("first_run_permissions"),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(if(stage==FirstRun.NOTIFICATIONS) Icons.Outlined.NotificationsActive else Icons.Outlined.Alarm,null,Modifier.size(52.dp),tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(if(stage==FirstRun.NOTIFICATIONS) "یادآوری‌ها را از دست ندهید" else "هشدارها و یادآوری‌های دقیق",style=MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text(if(stage==FirstRun.NOTIFICATIONS) "اعلان‌ها برای مصوبات تازه، دعوت جلسه و زمان پیگیری استفاده می‌شوند." else "این دسترسی کمک می‌کند یادآوری مصوبات در ساعت انتخاب‌شده اجرا شود. در صفحهٔ بعد، دسترسی پیگیر را روشن کنید.",style=MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Button(onClick=::request,modifier=Modifier.fillMaxWidth().testTag("first_run_allow")){Text(if(stage==FirstRun.NOTIFICATIONS) "فعال‌کردن اعلان‌ها" else "فعال‌کردن یادآوری دقیق")}
            TextButton(onClick={advance(if(stage==FirstRun.NOTIFICATIONS) FirstRun.EXACT else FirstRun.LOGIN)},modifier=Modifier.testTag("first_run_later")){Text("فعلاً بعداً؛ ادامه")}
            if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(12.dp));Text("مرحلهٔ ${if(stage==FirstRun.NOTIFICATIONS) 1.fa() else 2.fa()} از ۳",style=MaterialTheme.typography.labelMedium)
        }
    }
}
