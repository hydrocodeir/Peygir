package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable internal fun ProgressReminderSettings(options: UserOptions,reschedule: ()->Unit) {
    val context=LocalContext.current
    val prefs=remember(context){context.getSharedPreferences("settings",0)}
    var percent by rememberSaveable(options.progressPercent){mutableStateOf(options.progressPercent.toString())}
    val value=percent.latin().toIntOrNull()
    OutlinedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("پیگیری خودکار پیشرفت",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().toggleable(options.progressEnabled,role=Role.Switch,onValueChange={prefs.edit().putBoolean("progress_enabled",it).apply();reschedule()}).testTag("option_progress_enabled"),verticalAlignment=Alignment.CenterVertically) {
            Text("یادآوری درصدی از مهلت",Modifier.weight(1f));Switch(options.progressEnabled,null)
        }
        Text("از زمان ثبت مصوبه تا پایان روز سررسید محاسبه می‌شود. برای کار انجام‌شده یا منتظر تأیید اعلان نمی‌فرستد.",style=MaterialTheme.typography.bodySmall)
        OutlinedTextField(percent,{percent=it.latin().filter(Char::isDigit).take(2)},label={Text("درصد سپری‌شدن مهلت (۱ تا ۹۹)")},suffix={Text("٪")},singleLine=true,
            enabled=options.progressEnabled,isError=value !in 1..99,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth().testTag("option_progress_percent"))
        OutlinedButton(onClick={prefs.edit().putInt("progress_percent",value!!).apply();reschedule()},enabled=options.progressEnabled && value in 1..99 && value!=options.progressPercent,modifier=Modifier.testTag("save_progress_percent")){Text("ذخیرهٔ درصد یادآوری")}
        Text(if(options.progressEnabled) "مقدار ذخیره‌شده: ${options.progressPercent.fa()}٪؛ اگر این زمان گذشته باشد، یادآوری پس از ذخیره ارسال می‌شود." else "یادآوری خاموش است؛ گزارش پیشرفت و درخواست تمدید همچنان قابل ثبت‌اند.",style=MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().toggleable(options.taskReminders,role=Role.Switch,onValueChange={prefs.edit().putBoolean("task_reminders",it).apply();reschedule()}).testTag("option_task_reminders"),verticalAlignment=Alignment.CenterVertically) {
            Text("یادآوری‌های دستی مصوبات",Modifier.weight(1f));Switch(options.taskReminders,null)
        }
        Text("این تنظیمات فقط برای این حساب روی همین گوشی است. خاموش‌کردن، یادآوری‌های ثبت‌شده را حذف نمی‌کند.",style=MaterialTheme.typography.bodySmall)
    }}
}
