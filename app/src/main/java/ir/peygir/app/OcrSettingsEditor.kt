package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OcrServices(val settings: OcrSettings, val reader: OnlinePhotoReader)
internal val LocalOcrServices=staticCompositionLocalOf<OcrServices?> {null}
@Composable internal fun rememberOcrServices(): OcrServices {
    val context=LocalContext.current
    val provided=LocalOcrServices.current
    return provided ?: remember(context.applicationContext) {OcrServices(OcrSettings(context),MistralOcr)}
}

@Composable fun OcrSettingsEditor(dismiss: ()->Unit, changed: ()->Unit, settings: OcrSettings = rememberOcrServices().settings) {
    val initial=remember {settings.profile()}
    var providerName by rememberSaveable {mutableStateOf(initial.provider.name)}
    val provider=OcrProvider.valueOf(providerName)
    var model by rememberSaveable {mutableStateOf(initial.model)}
    // Deliberately not rememberSaveable: a typed secret never enters Activity saved state.
    var key by remember {mutableStateOf("")}
    var advanced by rememberSaveable {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf("")}
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val kept=initial.configured && initial.provider==provider
    FullEditor("تنظیمات Mistral OCR",busy,dismiss,{
        busy=true;error=""
        scope.launch {
            try {withContext(Dispatchers.IO) {settings.save(provider,model,key)};key="";changed()}
            catch(e:CancellationException) {throw e}
            catch(e:Exception) {error=if(e is IllegalArgumentException || e is OcrFailure) e.message.orEmpty() else "ذخیرهٔ امن تنظیمات ناموفق بود؛ دوباره تلاش کنید."}
            finally {busy=false}
        }
    },feedback=error,saveLabel="ذخیرهٔ اتصال") {
        Text("خواندن آنلاین عکس و دست‌نوشته",style=MaterialTheme.typography.titleMedium)
        Text("کلید شخصی حساب خود را وارد کنید. عکس هنگام انتخاب Mistral آنلاین به ارائه‌دهندهٔ زیر ارسال می‌شود و ممکن است هزینه داشته باشد.",style=MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OcrProvider.entries.forEach {p->FilterChip(provider==p,{providerName=p.name;model=p.defaultModel;key="";error=""},label={Text(p.label)},enabled=!busy)}
        }
        OutlinedTextField(key,{key=it;error=""},label={Text("کلید API")},placeholder={Text(if(kept) "برای حفظ کلید قبلی، خالی بگذارید" else "کلید همین ارائه‌دهنده")},
            visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true,
            enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("ocr_api_key"))
        if(kept) Text("کلید ذخیره شده است؛ برای جایگزینی کلید تازه وارد کنید.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.primary)
        TextButton(onClick={
            try {context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(if(provider==OcrProvider.MISTRAL) "https://console.mistral.ai" else "https://chat.avalai.ir")))}
            catch(_:Exception) {error="مرورگر در دسترس نیست؛ پنل ارائه‌دهنده را در مرورگر باز کنید."}
        },enabled=!busy) {Text("باز کردن پنل ${provider.label}")}
        TextButton(onClick={advanced=!advanced},enabled=!busy) {Text(if(advanced) "بستن تنظیم مدل" else "تنظیم مدل (اختیاری)")}
        if(advanced) OutlinedTextField(model,{model=it},label={Text("نام مدل OCR")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth())
        if(provider==OcrProvider.AVALAI) Text("کلید AvalAI لازم است. نسخهٔ مدل و تعرفهٔ واسط ممکن است با سرویس مستقیم متفاوت باشد.",style=MaterialTheme.typography.bodySmall)
        Text("کلید با Android Keystore روی همین گوشی رمزگذاری می‌شود و داخل فایل نصب یا نسخهٔ پشتیبان قرار نمی‌گیرد. صوت و فهرست جلسات ارسال نمی‌شوند.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(initial.configured) OutlinedButton(onClick={
            busy=true
            scope.launch {try {withContext(Dispatchers.IO){settings.clear()};key="";changed()} catch(_:Exception){error="حذف کلید ناموفق بود."} finally {busy=false}}
        },enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("حذف کلید و غیرفعال‌کردن آنلاین")}
        Text("ذخیرهٔ اتصال فقط تنظیمات را نگه می‌دارد؛ آزمایش اعتبار کلید هنگام خواندن اولین عکس انجام می‌شود. نتیجهٔ دست‌خط، نام و تاریخ را پیش از ثبت بررسی کنید.",style=MaterialTheme.typography.bodySmall)
    }
}
