package ir.peygir.app

import android.app.NotificationManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.time.LocalDate

@Composable fun BackupPanel(message: (String)->Unit, restored: ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var working by remember {mutableStateOf(false)}
    var preview by remember {mutableStateOf<Backup.Prepared?>(null)}
    DisposableEffect(Unit) {onDispose {preview?.close()}}
    fun error(e: Exception, fallback: String) = e.message?.takeIf {it.any {c->c in 'آ'..'ی'}} ?: fallback
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) {uri->
        if(uri!=null) scope.launch {
            working=true
            try {
                withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri,"wt")?.use {Backup.write(context,it)} ?: error("فایل پشتیبان باز نشد.")}
                message("فایل پشتیبان ذخیره شد.")
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {message(error(e,"پشتیبان‌گیری ناموفق بود؛ فضای خالی و محل ذخیره را بررسی کنید."))}
            finally {working=false}
        }
    }
    val restoreFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) scope.launch {
            working=true
            var pending: Backup.Prepared?=null
            try {
                withContext(Dispatchers.IO) {pending=context.contentResolver.openInputStream(uri)?.use {Backup.read(context,it)} ?: error("فایل پشتیبان باز نشد.")}
                preview=pending;pending=null
            }
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {message(error(e,"فایل پشتیبان معتبر نیست یا قابل خواندن نیست؛ اطلاعات فعلی تغییر نکرد."))}
            finally {pending?.close();working=false}
        }
    }
    OutlinedCard {Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("پشتیبان‌گیری و بازیابی",style=MaterialTheme.typography.titleMedium)
        Text("جلسات، مصوبات، نتایج پیگیری، تاریخچهٔ موعد، یادآوری‌ها، صوت‌های آرشیوی و تنظیمات در یک فایل ذخیره می‌شوند. محل ذخیره را خودتان انتخاب کنید.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={export.launch("Peygir-${LocalDate.now()}.zip")},enabled=!working,modifier=Modifier.fillMaxWidth()) {Text("ذخیرهٔ فایل پشتیبان")}
        TextButton(onClick={restoreFile.launch(arrayOf("application/zip","application/octet-stream","*/*"))},enabled=!working,modifier=Modifier.fillMaxWidth()) {Text("بازیابی از فایل پشتیبان")}
        Text("فایل شامل اطلاعات شخصی شماست و رمزگذاری نشده است. آن را در محل مطمئن نگه دارید. پیش‌نویس‌های ثبت‌نشده در پشتیبان نیستند.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }}
    preview?.let {prepared->
        AlertDialog(onDismissRequest={if(!working) {preview=null;prepared.close()}},title={Text("بازیابی پشتیبان")},
            text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){CountLabel("جلسات",prepared.data.meetings.size);CountLabel("مصوبات",prepared.data.tasks.size);CountLabel("نتایج پیگیری",prepared.data.followUps.size);CountLabel("صداهای آرشیوی",prepared.data.recordings.size);Text("اطلاعات و تنظیمات فعلی با این پشتیبان جایگزین می‌شوند. برای حفظ اطلاعات فعلی، ابتدا از آن‌ها پشتیبان بگیرید.")}},
            dismissButton={TextButton(onClick={preview=null;prepared.close()},enabled=!working) {Text("انصراف")}},
            confirmButton={Button(enabled=!working,onClick={scope.launch {
                working=true;preview=null
                try {
                    withContext(NonCancellable+Dispatchers.IO) {
                        try {
                            // Use the same lock order as dispatch: reminders, then the database.
                            // An in-flight old alarm must not mark a restored reminder as delivered.
                            synchronized(Reminders) {
                                Backup.restore(context,prepared)
                                runCatching {Accounts.cancelNotifications(context);Reminders.process(context)}
                            }
                            runCatching {ReviewWidget.updateAll(context)}
                        } finally {prepared.close()}
                    }
                    restored();message("پشتیبان بازیابی شد.")
                } catch(e:CancellationException) {throw e}
                catch(e:Exception) {message(error(e,"بازیابی ناموفق بود؛ اطلاعات فعلی حفظ شد."))}
                finally {working=false}
            }}) {Text("بازیابی و جایگزینی")}})
    }
    if(working) {
        BackHandler {}
        AlertDialog(onDismissRequest={},confirmButton={},title={Text("در حال پردازش پشتیبان")},text={LinearProgressIndicator(Modifier.fillMaxWidth())})
    }
}
