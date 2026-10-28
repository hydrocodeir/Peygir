package ir.peygir.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject

/** Phone-local choices. Missing keys reproduce the existing app exactly. */
data class UserOptions(
    val launch: String="meetings",val calendar: String="DAY",val focus: String="now",val workStart: Int=540,
    val duration: Int=60,val meetingLead: Int=-1,val updates: String="all",val cards: String="compact",val font: String="system"
) {
    fun launchSection(last: Int)=when(launch){"followup"->0;"tasks"->1;"review"->3;"last"->last.takeIf{it in 0..3} ?: 2;else->2}
    fun focusHour(minute: Int)=if(focus=="work") workStart/60f else MeetingTimeline.initialHour(minute)
    fun fontScale(system: Float)=when(font){"standard"->1f;"large"->1.25f;else->system}
    fun json()=JSONObject().put("launch",launch).put("calendar",calendar).put("focus",focus).put("workStart",workStart)
        .put("duration",duration).put("meetingLead",meetingLead).put("updates",updates).put("cards",cards).put("font",font)
    fun save(prefs: SharedPreferences.Editor): SharedPreferences.Editor=prefs
        .putString("start_page",launch).putString("calendar_mode",calendar).putString("calendar_focus",focus).putInt("work_start",workStart)
        .putInt("meeting_duration",duration).putInt("meeting_lead",meetingLead).putString("push_updates",updates).putString("card_details",cards).putString("text_size",font)
    companion object {
        val launches=linkedMapOf("meetings" to "جلسات","followup" to "پیگیری","tasks" to "مصوبات","review" to "مرور","last" to "آخرین بخش بازشده")
        val calendars=linkedMapOf("DAY" to "روزانه","WEEK" to "هفتگی","MONTH" to "ماهانه")
        val focuses=linkedMapOf("now" to "ساعت فعلی","work" to "شروع ساعت کاری")
        val durations=listOf(15,30,45,60,90,120)
        val leads=linkedMapOf(-1 to "خاموش",0 to "هنگام شروع",5 to "۵ دقیقه قبل",10 to "۱۰ دقیقه قبل",15 to "۱۵ دقیقه قبل",30 to "۳۰ دقیقه قبل",60 to "یک ساعت قبل")
        val updateModes=linkedMapOf("all" to "همهٔ تغییرات مرتبط","essential" to "فقط رویدادهای اصلی")
        val cardModes=linkedMapOf("compact" to "خلاصه","full" to "با جزئیات بیشتر")
        val fonts=linkedMapOf("system" to "همراه تنظیمات گوشی","standard" to "استاندارد","large" to "بزرگ")
        fun read(context: Context): UserOptions {
            val p=context.getSharedPreferences("settings",0)
            return UserOptions(
                p.getString("start_page",null).takeIf{it in launches} ?: "meetings",
                p.getString("calendar_mode",null).takeIf{it in calendars} ?: "DAY",
                p.getString("calendar_focus",null).takeIf{it in focuses} ?: "now",p.getInt("work_start",540).coerceIn(0,1439),
                p.getInt("meeting_duration",60).takeIf{it in durations} ?: 60,p.getInt("meeting_lead",-1).takeIf{it in leads} ?: -1,
                p.getString("push_updates",null).takeIf{it in updateModes} ?: "all",p.getString("card_details",null).takeIf{it in cardModes} ?: "compact",
                p.getString("text_size",null).takeIf{it in fonts} ?: "system")
        }
        fun from(p: JSONObject?): UserOptions {
            if(p==null) return UserOptions()
            listOf("launch","calendar","focus","updates","cards","font").filter{p.has(it)}.forEach{require(p.get(it) is String) {"قالب تنظیمات پشتیبان معتبر نیست."}}
            listOf("workStart","duration","meetingLead").filter{p.has(it)}.forEach{key->
                val n=p.get(key);require(n is Number && n.toDouble().isFinite() && n.toDouble()==n.toInt().toDouble()) {"قالب زمان در تنظیمات پشتیبان معتبر نیست."}
            }
            val v=UserOptions(p.optString("launch","meetings"),p.optString("calendar","DAY"),p.optString("focus","now"),p.optInt("workStart",540),
                p.optInt("duration",60),p.optInt("meetingLead",-1),p.optString("updates","all"),p.optString("cards","compact"),p.optString("font","system"))
            require(v.launch in launches && v.calendar in calendars && v.focus in focuses && v.workStart in 0..1439 && v.duration in durations && v.meetingLead in leads && v.updates in updateModes && v.cards in cardModes && v.font in fonts) {"تنظیمات راحتی استفاده در پشتیبان معتبر نیست."}
            return v
        }
    }
}

@Composable internal fun rememberUserOptions(): UserOptions {
    val context=LocalContext.current
    val prefs=remember(context){context.getSharedPreferences("settings",0)}
    var value by remember(prefs){mutableStateOf(UserOptions.read(context))}
    DisposableEffect(prefs) {
        val listener=SharedPreferences.OnSharedPreferenceChangeListener{_,_->value=UserOptions.read(context)}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    return value
}
