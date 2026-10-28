package ir.peygir.app

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun OptionPicker(title: String,value: String,options: Map<String,String>,tag: String,change: (String)->Unit) {
    Column(Modifier.fillMaxWidth().testTag(tag),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(title,style=MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            options.forEach{(key,label)->FilterChip(selected=value==key,onClick={change(key)},label={Text(label)},modifier=Modifier.testTag("${tag}_$key"))}
        }
    }
}

@Composable internal fun PersonalSettings(options: UserOptions) {
    val c=LocalContext.current;val p=remember{c.getSharedPreferences("settings",0)}
    OptionPicker("صفحهٔ شروع",options.launch,UserOptions.launches,"option_launch"){p.edit().putString("start_page",it).apply()}
    Text("در بازکردن تازهٔ برنامه اعمال می‌شود؛ اعلان و ویجت، مورد انتخاب‌شده را باز می‌کنند.",style=MaterialTheme.typography.bodySmall)
    OptionPicker("نمای پیش‌فرض تقویم",options.calendar,UserOptions.calendars,"option_calendar"){p.edit().putString("calendar_mode",it).apply()}
    OptionPicker("ابتدای نمایش روزانه و هفتگی",options.focus,UserOptions.focuses,"option_focus"){p.edit().putString("calendar_focus",it).apply()}
    if(options.focus=="work") OutlinedButton(onClick={TimePickerDialog(c,{_,h,m->p.edit().putInt("work_start",h*60+m).apply()},options.workStart/60,options.workStart%60,true).show()},modifier=Modifier.fillMaxWidth().testTag("option_work_start")) {Text("شروع ساعت کاری - ${"%02d:%02d".format(options.workStart/60,options.workStart%60).fa()}")}
    OptionPicker("مدت پیشنهادی جلسهٔ تازه",options.duration.toString(),UserOptions.durations.associate{it.toString() to "${it.fa()} دقیقه"},"option_duration"){p.edit().putInt("meeting_duration",it.toInt()).apply()}
    Text("ساعت پایان هنگام انتخاب شروع پیشنهاد می‌شود؛ در همان جلسه قابل تغییر است. زمان جلسات قبلی تغییر نمی‌کند.",style=MaterialTheme.typography.bodySmall)
}

@Composable internal fun MeetingAlertPicker(value: Int?,change: (Int?)->Unit) {
    OptionPicker("یادآوری در این گوشی",value?.toString() ?: "default",linkedMapOf("default" to "طبق تنظیمات این گوشی")+UserOptions.leads.mapKeys{it.key.toString()},"meeting_alert") {change(it.toIntOrNull())}
}
