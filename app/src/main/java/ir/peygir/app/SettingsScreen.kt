package ir.peygir.app

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalTime

@OptIn(ExperimentalLayoutApi::class)
@Composable fun SettingsScreen(mode: String, setMode: (String)->Unit, message: (String)->Unit, dynamicColors: Boolean = false, setDynamicColors: (Boolean)->Unit = {}, restored: ()->Unit = {},showTitle: Boolean=true) {
    val context=LocalContext.current
    val prefs=remember {context.getSharedPreferences("settings",0)}
    var enabled by remember {mutableStateOf(Reminders.dailyEnabled(context))}
    var times by remember {mutableStateOf(Reminders.dailyTimes(context))}
    var notification by remember {mutableStateOf(Reminders.allowed(context))}
    var exact by remember {mutableStateOf(Reminders.exact(context))}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    fun reschedule() {Reminders.background {runCatching {Reminders.process(context)}}}
    fun updateTimes(value: List<LocalTime>) {times=value.distinct().sorted();prefs.edit().putString("times",times.joinToString(",")).apply();reschedule()}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_RESUME) {notification=Reminders.allowed(context);exact=Reminders.exact(context);reschedule()}}
        lifecycle.addObserver(observer);onDispose {lifecycle.removeObserver(observer)}
    }
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {notification=Reminders.allowed(context);reschedule()}
    fun openSettings(action: String) {try {context.startActivity(Intent(action,Uri.parse("package:${context.packageName}")))} catch(_:Exception) {context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}")))}}
    val settingsScroll=rememberScrollState()
    val settingsScope=rememberCoroutineScope()
    var group by rememberSaveable {mutableStateOf("")}
    var help by rememberSaveable {mutableStateOf(false)}
    val syncRevision by SharedSync.revision.collectAsState()
    val syncError=remember(syncRevision){context.getSharedPreferences("shared_status",0).getString("error","").orEmpty()}
    var conflicts by remember {mutableIntStateOf(0)}
    LaunchedEffect(syncRevision){conflicts=withContext(Dispatchers.IO){runCatching{SharedStore(Store.get(context)).counts().second}.getOrDefault(0)}}
    Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(showTitle) Text("تنظیمات",style=MaterialTheme.typography.titleLarge)
        if(!notification || !exact) OutlinedCard(onClick={group="notifications";settingsScope.launch{settingsScroll.animateScrollTo(0)}},colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) {Text(if(!notification) "اعلان‌ها خاموش است؛ برای فعال‌سازی بزنید." else "یادآوری دقیق فعال نیست؛ برای بررسی بزنید.",Modifier.padding(12.dp))}
        if(syncError.isNotBlank() || conflicts>0) OutlinedCard(onClick={group="account";settingsScope.launch{settingsScroll.animateScrollTo(0)}},colors=CardDefaults.outlinedCardColors(containerColor=MaterialTheme.colorScheme.errorContainer)) {if(conflicts>0) CountLabel("تغییرات هم‌زمان نیازمند بررسی",conflicts,Modifier.padding(12.dp),color=MaterialTheme.colorScheme.onErrorContainer) else Text("همگام‌سازی نیاز به بررسی دارد؛ برای جزئیات بزنید.",Modifier.padding(12.dp))}
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(settingsScroll),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        SettingsGroup("حساب و همگام‌سازی",when(OrgAccess.role(context)){"manager"->"حساب رئیس";"deputy"->"حساب معاون";"assistant"->"حساب دستیار";else->"اتصال به شرکت"},group=="account",{group=if(group=="account") "" else "account"},"settings_account") {SharedPanel(message);CompanyAccountsPanel()}
        SettingsGroup("یادآوری‌ها",if(enabled) "مرور روزانه فعال" else "مرور روزانه خاموش",group=="notifications",{group=if(group=="notifications") "" else "notifications"},"settings_notifications") {
        OutlinedCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row {Icon(Icons.Outlined.NotificationsActive,null);Spacer(Modifier.width(10.dp));Text("اعلان‌ها",style=MaterialTheme.typography.titleMedium)}
            Text(if(notification) "مجوز اعلان فعال است" else "مجوز اعلان غیرفعال است",color=if(notification) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Button(onClick={if(Build.VERSION.SDK_INT>=33 && !notification) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))},modifier=Modifier.fillMaxWidth()) {Text(if(notification) "تنظیمات اعلان" else "فعال‌سازی اعلان‌ها")}
            if(!notification) TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}) {Text("باز کردن تنظیمات اعلان گوشی")}
            TextButton(onClick={if(!Reminders.test(context)) message("ابتدا مجوز اعلان و کانال پیگیری را فعال کنید.")}) {Text("آزمایش اعلان")}
            HorizontalDivider()
            Text(if(exact) "دسترسی زمان دقیق فعال است" else "زمان دقیق فعال نیست؛ اندروید ممکن است یادآوری را با تأخیر اجرا کند.",style=MaterialTheme.typography.bodyMedium)
            if(Build.VERSION.SDK_INT>=31) OutlinedButton(onClick={openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)},modifier=Modifier.fillMaxWidth()) {Text("دسترسی هشدارها و یادآورها")}
            TextButton(onClick={help=!help}){Text("راهنمای یادآوری‌ها")}
            if(help) Text("یادآوری‌ها در حالت بسته بودن برنامه اجرا می‌شوند و پس از روشن شدن گوشی دوباره تنظیم می‌شوند. توقف اجباری، خاموش بودن گوشی و محدودیت باتری می‌توانند مانع اجرا شوند. پس از توقف اجباری، برنامه را یک بار باز کنید.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        OutlinedCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("مرور روزانهٔ مدیریتی",style=MaterialTheme.typography.titleMedium);Text("خلاصهٔ مصوبات امروز و تعداد معوق‌ها؛ پیش‌فرض ۸ صبح",style=MaterialTheme.typography.bodySmall)};Switch(enabled,onCheckedChange={enabled=it;prefs.edit().putBoolean("daily",it).apply();reschedule()})}
            times.forEach {time->Row(verticalAlignment=Alignment.CenterVertically) {TextButton(onClick={TimePickerDialog(context,{_,h,m->updateTimes(times-time+LocalTime.of(h,m))},time.hour,time.minute,true).show()},modifier=Modifier.weight(1f)) {Icon(Icons.Outlined.Schedule,null);Spacer(Modifier.width(8.dp));Text("ساعت مرور - ${time.toString().fa()}")};IconButton(onClick={updateTimes(times-time)}){Icon(Icons.Outlined.Close,"حذف ساعت مرور")}}}
            OutlinedButton(onClick={TimePickerDialog(context,{_,h,m->updateTimes(times+LocalTime.of(h,m))},8,0,true).show()},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.AddAlarm,null);Spacer(Modifier.width(8.dp));Text("افزودن ساعت مرور")}
            if(times.isEmpty()) Text("برای دریافت مرور روزانه، دست‌کم یک ساعت انتخاب کنید.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,Reminders.REVIEW_CHANNEL))}) {Text("تنظیمات اعلان مرور روزانه")}
        }}
        }
        SettingsGroup("ظاهر",when(mode){"dark"->"تیره";"light"->"روشن";else->"همراه گوشی"},group=="appearance",{group=if(group=="appearance") "" else "appearance"},"settings_appearance") {
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("system" to "همراه گوشی","light" to "روشن","dark" to "تیره").forEach {(value,label)->FilterChip(mode==value,{setMode(value)},label={Text(label)})}}
        OutlinedCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("رنگ‌های گوشی",style=MaterialTheme.typography.titleMedium);Text("هماهنگ با پالت تصویر زمینهٔ گوشی",style=MaterialTheme.typography.bodySmall)};Switch(dynamicColors,onCheckedChange=setDynamicColors,enabled=Build.VERSION.SDK_INT>=31,modifier=Modifier.testTag("dynamic_colors"))}
            Text(if(Build.VERSION.SDK_INT>=31) "رنگ‌ها با انتخاب پالت گوشی تغییر می‌کنند؛ حالت روشن و تیره جداگانه قابل انتخاب است." else "این قابلیت از اندروید ۱۲ به بعد در دسترس است.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        }
        SettingsGroup("اطلاعات و پشتیبان","ذخیره و بازیابی",group=="backup",{group=if(group=="backup") "" else "backup"},"settings_backup") {BackupPanel(message,restored)}
        SettingsGroup("ویجت‌ها","جلسات و مصوبات",group=="widgets",{group=if(group=="widgets") "" else "widgets"},"settings_widgets") {
            Text("فهرست شمسیِ امروز تا ۳۰ روز آینده، با ثبت سریع و پیمایش عمودی.",style=MaterialTheme.typography.bodySmall)
            listOf(false to "افزودن ویجت مصوبات",true to "افزودن ویجت جلسات").forEach{(meetings,label)->OutlinedButton(onClick={
                val manager=android.appwidget.AppWidgetManager.getInstance(context)
                val component=android.content.ComponentName(context,if(meetings) MeetingWidget::class.java else ReviewWidget::class.java)
                if(!manager.isRequestPinAppWidgetSupported || !manager.requestPinAppWidget(component,null,null)) message("از ویجت‌های صفحهٔ اصلی، پیگیر را انتخاب کنید.")
            },modifier=Modifier.fillMaxWidth()){Text(label)}}
        }
        SettingsGroup("دربارهٔ پیگیر","نسخهٔ ${BuildConfig.VERSION_NAME.fa()}",group=="about",{group=if(group=="about") "" else "about"},"settings_about") {
        OutlinedCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {Text("پیگیر",style=MaterialTheme.typography.titleMedium);Text("نسخهٔ ${BuildConfig.VERSION_NAME.fa()} - ساخته‌شده برای پیگیری تصمیم‌ها",style=MaterialTheme.typography.bodySmall);Text("جلسات، مصوبات و صداها روی گوشی ذخیره می‌شوند. برای تبادل اطلاعات فضای مشترک، اتصال اینترنت لازم است؛ اطلاعات دریافت‌شده آفلاین هم در دسترس‌اند. حذف برنامه یا پاک‌کردن داده‌های آن، اطلاعات محلی را حذف می‌کند.",style=MaterialTheme.typography.bodySmall);Text("فونت وزیرمتن - Material 3\nگفتار فارسی: Vosk\nمجوزهای متن‌باز همراه برنامه در assets/licenses قرار دارند.",style=MaterialTheme.typography.bodySmall)}}
        }
        }
    }
}

@Composable private fun SettingsGroup(title: String,summary: String,open: Boolean,toggle: ()->Unit,tag: String,content: @Composable ColumnScope.()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick=toggle,modifier=Modifier.fillMaxWidth().testTag(tag)) {Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Text(summary,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Icon(if(open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null)}
        if(open) content()
    }}
}
