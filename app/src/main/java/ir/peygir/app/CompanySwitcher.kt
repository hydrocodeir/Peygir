package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun CompanyAccountsShortcut() {
    var open by remember{mutableStateOf(false)}
    TextButton(onClick={open=true},modifier=Modifier.testTag("my_companies")){Icon(Icons.Outlined.Business,null);Spacer(Modifier.width(6.dp));Text("شرکت‌های من")}
    if(open) CompanySwitcher({open=false})
}

@Composable internal fun CompanySwitcher(dismiss: ()->Unit) {
    val c=LocalContext.current
    val revision by Accounts.revision.collectAsState()
    val items=remember(revision){Accounts.list(c)}
    var adding by remember{mutableStateOf(false)}
    var busy by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf("")}
    val scope=rememberCoroutineScope()
    FullEditor("شرکت‌های من",busy,dismiss,{adding=true},saveLabel="افزودن شرکت",feedback=error,scrollable=false) {
        if(items.isEmpty()) Text("حساب شرکت را اضافه کنید؛ اطلاعات هر شرکت جدا نگه داشته می‌شود.")
        LazyColumn(Modifier.weight(1f).testTag("company_list"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(items,key={it.id}) {p->
                val selected=p.id==Accounts.active(c)
                val account=Accounts.context(c,p.id)
                val duplicate=items.count{it.label==p.label || it.company==p.company}>1
                val identity=listOf(p.company,if(duplicate) java.net.URI(p.endpoint).host.orEmpty() else "").filter{it.isNotBlank()}.joinToString(" - ").ifBlank{p.endpoint}
                val expired=account.getSharedPreferences("shared_status",0).getBoolean("auth_expired",false)
                OutlinedCard(onClick={if(!busy) {busy=true;scope.launch{try{withContext(Dispatchers.IO){Accounts.select(c,p.id);SharedSync.request(account)};dismiss()}catch(e:Exception){error=e.message.orEmpty()}finally{busy=false}}}},modifier=Modifier.fillMaxWidth().testTag("company_${p.id}"),colors=CardDefaults.outlinedCardColors(containerColor=if(selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                    Row(Modifier.padding(12.dp).heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Icon(if(selected) Icons.Outlined.CheckCircle else Icons.Outlined.Business,null)
                        Column(Modifier.weight(1f)) {
                            Text(p.label,style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                            Text(p.name+" - "+when(OrgAccess.role(account)){"manager"->"مدیر";"assistant"->"دستیار";else->"کاربر"},style=MaterialTheme.typography.bodySmall)
                            Text(if(expired) "ورود دوباره لازم است" else identity,style=MaterialTheme.typography.labelSmall,color=if(expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val count=Accounts.unread(c,p.id)
                        if(count>0) CountBadge(count,"موارد تازهٔ ${p.label}")
                    }
                }
            }
        }
    }
    if(adding) SharedLoginDialog({adding=false},{adding=false;dismiss()},addAccount=true)
}
