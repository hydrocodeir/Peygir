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
@Composable fun SettingsScreen(mode: String, setMode: (String)->Unit, message: (String)->Unit, dynamicColors: Boolean = false, setDynamicColors: (Boolean)->Unit = {}, restored: ()->Unit = {}) {
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(20.dp)) {
        SharedPanel(message)
        Text("یادآوری‌های قابل اتکا",style=MaterialTheme.typography.headlineSmall)
        OutlinedCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row {Icon(Icons.Outlined.NotificationsActive,null);Spacer(Modifier.width(10.dp));Text("اعلان‌ها",style=MaterialTheme.typography.titleMedium)}
            Text(if(notification) "مجوز اعلان فعال است" else "مجوز اعلان غیرفعال است",color=if(notification) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Button(onClick={if(Build.VERSION.SDK_INT>=33 && !notification) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))},modifier=Modifier.fillMaxWidth()) {Text(if(notification) "تنظیمات اعلان" else "فعال‌سازی اعلان‌ها")}
            if(!notification) TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}) {Text("باز کردن تنظیمات اعلان گوشی")}
            TextButton(onClick={if(!Reminders.test(context)) message("ابتدا مجوز اعلان و کانال پیگیری را فعال کنید.")}) {Text("آزمایش اعلان")}
            HorizontalDivider()
            Text(if(exact) "دسترسی زمان دقیق فعال است" else "زمان دقیق فعال نیست؛ اندروید ممکن است یادآوری را با تأخیر اجرا کند.",style=MaterialTheme.typography.bodyMedium)
            if(Build.VERSION.SDK_INT>=31) OutlinedButton(onClick={openSettings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)},modifier=Modifier.fillMaxWidth()) {Text("دسترسی هشدارها و یادآورها")}
            Text("یادآوری‌ها در حالت بسته بودن برنامه اجرا می‌شوند و پس از روشن شدن گوشی دوباره تنظیم می‌شوند. توقف اجباری، خاموش بودن گوشی و محدودیت باتری می‌توانند مانع اجرا شوند. پس از توقف اجباری، برنامه را یک بار باز کنید.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        OutlinedCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("مرور روزانهٔ مدیریتی",style=MaterialTheme.typography.titleMedium);Text("خلاصهٔ مصوبات امروز و تعداد معوق‌ها؛ پیش‌فرض ۸ صبح",style=MaterialTheme.typography.bodySmall)};Switch(enabled,onCheckedChange={enabled=it;prefs.edit().putBoolean("daily",it).apply();reschedule()})}
            times.forEach {time->Row(verticalAlignment=Alignment.CenterVertically) {TextButton(onClick={TimePickerDialog(context,{_,h,m->updateTimes(times-time+LocalTime.of(h,m))},time.hour,time.minute,true).show()},modifier=Modifier.weight(1f)) {Icon(Icons.Outlined.Schedule,null);Spacer(Modifier.width(8.dp));Text("ساعت مرور | ${time.toString().fa()}")};IconButton(onClick={updateTimes(times-time)}){Icon(Icons.Outlined.Close,"حذف ساعت مرور")}}}
            OutlinedButton(onClick={TimePickerDialog(context,{_,h,m->updateTimes(times+LocalTime.of(h,m))},8,0,true).show()},modifier=Modifier.fillMaxWidth()) {Icon(Icons.Outlined.AddAlarm,null);Spacer(Modifier.width(8.dp));Text("افزودن ساعت مرور")}
            if(times.isEmpty()) Text("برای دریافت مرور روزانه، دست‌کم یک ساعت انتخاب کنید.",style=MaterialTheme.typography.bodySmall)
            TextButton(onClick={context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,Reminders.REVIEW_CHANNEL))}) {Text("تنظیمات اعلان مرور روزانه")}
        }}
        OutlinedCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("ویجت مرور روزانه",style=MaterialTheme.typography.titleMedium)
            Text("مصوبات هر روز، فلش روز قبل و بعد، دکمه‌های امروز، فردا و همین روز هفتهٔ بعد، و ثبت مصوبه و جلسه روی صفحهٔ اصلی گوشی.",style=MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick={
                val manager=android.appwidget.AppWidgetManager.getInstance(context)
                if(manager.isRequestPinAppWidgetSupported) {
                    if(!manager.requestPinAppWidget(android.content.ComponentName(context,ReviewWidget::class.java),null,null)) message("از منوی ویجت‌های صفحهٔ اصلی، «پیگیر | مرور روزانه» را اضافه کنید.")
                } else message("روی صفحهٔ اصلی گوشی نگه دارید و از ویجت‌ها، «پیگیر | مرور روزانه» را اضافه کنید.")
            },modifier=Modifier.fillMaxWidth()) {Text("افزودن ویجت به صفحهٔ اصلی")}
            Text("روزها با فلش‌ها عوض می‌شوند؛ فهرست مصوبات را عمودی بکشید. لمس تاریخ، مرور همان روز را در برنامه باز می‌کند.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        Text("ظاهر برنامه",style=MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {listOf("system" to "همراه گوشی","light" to "روشن","dark" to "تیره").forEach {(value,label)->FilterChip(mode==value,{setMode(value)},label={Text(label)})}}
        OutlinedCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {Column(Modifier.weight(1f)) {Text("رنگ‌های گوشی",style=MaterialTheme.typography.titleMedium);Text("هماهنگ با پالت تصویر زمینهٔ گوشی",style=MaterialTheme.typography.bodySmall)};Switch(dynamicColors,onCheckedChange=setDynamicColors,enabled=Build.VERSION.SDK_INT>=31,modifier=Modifier.testTag("dynamic_colors"))}
            Text(if(Build.VERSION.SDK_INT>=31) "رنگ‌ها با انتخاب پالت گوشی تغییر می‌کنند؛ حالت روشن و تیره جداگانه قابل انتخاب است." else "این قابلیت از اندروید ۱۲ به بعد در دسترس است.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }}
        BackupPanel(message,restored)
        OutlinedCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {Text("پیگیر",style=MaterialTheme.typography.titleMedium);Text("نسخهٔ ${BuildConfig.VERSION_NAME.fa()} • ساخته‌شده برای پیگیری تصمیم‌ها",style=MaterialTheme.typography.bodySmall);Text("جلسات، مصوبات و صداها روی گوشی ذخیره می‌شوند. برای تبادل اطلاعات فضای مشترک، اتصال اینترنت لازم است؛ اطلاعات دریافت‌شده آفلاین هم در دسترس‌اند. حذف برنامه یا پاک‌کردن داده‌های آن، اطلاعات محلی را حذف می‌کند.",style=MaterialTheme.typography.bodySmall);Text("فونت وزیرمتن • Material 3\nگفتار فارسی: Vosk\nمجوزهای متن‌باز همراه برنامه در assets/licenses قرار دارند.",style=MaterialTheme.typography.bodySmall)}}
    }
}
