package ir.peygir.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal object WidgetAccounts {
    private fun prefs(c: Context)=Accounts.root(c).getSharedPreferences("widget_accounts",0)
    fun bind(c: Context,widget: Int,account: String) {require(widget>0 && (Accounts.profile(c,account)!=null || account.isEmpty() && Accounts.list(c).isEmpty()));check(prefs(c).edit().putString("account_$widget",account).commit())}
    fun remove(c: Context,widget: Int){prefs(c).edit().remove("account_$widget").apply()}
    fun id(c: Context,widget: Int): String = prefs(c).getString("account_$widget",null) ?: (Accounts.profile(c)?.id ?: Accounts.list(c).firstOrNull()?.id.orEmpty()).also{prefs(c).edit().putString("account_$widget",it).commit()}
    fun context(c: Context,widget: Int): Context? {val id=id(c,widget);return if(id.isEmpty() && Accounts.list(c).isEmpty() || Accounts.profile(c,id)!=null) Accounts.context(c,id) else null}
    fun pinExisting(c: Context,account: String) {
        val manager=AppWidgetManager.getInstance(c)
        for(type in listOf(ReviewWidget::class.java,MeetingWidget::class.java)) manager.getAppWidgetIds(ComponentName(c,type)).forEach{id->if(!prefs(c).contains("account_$id")) bind(c,id,account)}
    }
}

class WidgetAccountActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id=intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED)
        if(id==AppWidgetManager.INVALID_APPWIDGET_ID){finish();return}
        Accounts.initialize(this)
        fun choose(profile: String) {WidgetAccounts.bind(this,id,profile);Reminders.background{ReviewWidget.updateAll(this)};setResult(Activity.RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id));finish()}
        val profiles=Accounts.list(this)
        if(profiles.size<=1){choose(profiles.firstOrNull()?.id.orEmpty());return}
        setContent{PeygirTheme("system"){Surface(Modifier.fillMaxSize()){Column(Modifier.safeDrawingPadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("شرکت این ویجت",style=MaterialTheme.typography.titleLarge)
            Text("این ویجت همیشه اطلاعات شرکت انتخاب‌شده را نشان می‌دهد.")
            LazyColumn(Modifier.weight(1f)) {items(profiles,key={it.id}){p->OutlinedCard(onClick={choose(p.id)},modifier=Modifier.fillMaxWidth().padding(vertical=4.dp).testTag("widget_company_${p.id}")){Column(Modifier.padding(16.dp)){Text(p.label,style=MaterialTheme.typography.titleMedium);Text(p.name,style=MaterialTheme.typography.bodySmall)}}}}
            TextButton(onClick={finish()}){Text("انصراف")}
        }}}}
    }
}
