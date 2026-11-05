package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable internal fun AppUpdatePanel() {
    val context=LocalContext.current
    val state by AppUpdater.state.collectAsState()
    var source by rememberSaveable{mutableStateOf(AppUpdater.source(context))}
    var advanced by rememberSaveable{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("نسخهٔ نصب‌شده: ${BuildConfig.VERSION_NAME.fa()}",style=MaterialTheme.typography.titleSmall)
        Text("نسخهٔ جدید را مستقیم از سرور پیگیر دریافت کنید. اطلاعات و حساب‌ها با نصب به‌روزرسانی حفظ می‌شوند.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={AppUpdater.check(context)},enabled=!state.busy,modifier=Modifier.fillMaxWidth().testTag("check_app_update")){Text(if(state.busy && !state.downloading) "در حال بررسی نسخه…" else "بررسی نسخهٔ جدید")}
        if(state.message.isNotBlank()) Text(state.message,color=if(state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,modifier=Modifier.testTag("app_update_status"))
        state.info?.takeIf{it.code>BuildConfig.VERSION_CODE}?.let {info->
            Text("نسخهٔ ${info.version.fa()} • ${(info.size/1024/1024).fa()} مگابایت",style=MaterialTheme.typography.titleSmall)
            if(info.notes.isNotBlank()) Text(info.notes,style=MaterialTheme.typography.bodySmall)
            if(state.downloading) {
                LinearProgressIndicator(progress={state.progress/100f},modifier=Modifier.fillMaxWidth())
                Text("${state.progress.fa()}٪ دریافت شده؛ تا پایان دریافت برنامه را باز نگه دارید.")
                TextButton(onClick={AppUpdater.cancel()}){Text("لغو دریافت")}
            } else if(state.ready) {
                Button(onClick={runCatching{AppUpdater.install(context)}.onFailure{error=it.message.orEmpty()}},enabled=!state.busy,modifier=Modifier.fillMaxWidth().testTag("install_app_update")){Text("نصب نسخهٔ جدید")}
            } else Button(onClick={AppUpdater.download(context)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()){Text("دریافت نسخهٔ جدید")}
        }
        TextButton(onClick={advanced=!advanced}){Text("نشانی سرور به‌روزرسانی")}
        if(advanced) {
            OutlinedTextField(source,{source=it;error=""},label={Text("نشانی اطلاعات نسخه (HTTPS)")},enabled=!state.busy,modifier=Modifier.fillMaxWidth(),singleLine=false)
            OutlinedButton(onClick={runCatching{AppUpdater.saveSource(context,source.trim());error=""}.onFailure{error=it.message ?: "نشانی معتبر نیست."}},enabled=!state.busy){Text("ذخیرهٔ نشانی")}
            Text("این نشانی برای همهٔ حساب‌های همین گوشی استفاده می‌شود.",style=MaterialTheme.typography.bodySmall)
        }
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
    }
}
