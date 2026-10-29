package ir.peygir.app

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class CompanyProfile(val id: String,val endpoint: String,val company: String,val user: String,val workspace: String,val label: String,val name: String) {
    fun json()=JSONObject().put("id",id).put("endpoint",endpoint).put("company",company).put("user",user).put("workspace",workspace).put("label",label).put("name",name)
    companion object {fun from(p: JSONObject)=CompanyProfile(p.getString("id"),p.getString("endpoint"),p.getString("company"),p.getString("user"),p.getString("workspace"),p.getString("label"),p.getString("name"))}
}

/** Explicit contexts pin asynchronous work to its account, even after the visible account changes. */
internal class CompanyContext(base: Context,val profileId: String): ContextWrapper(base) {
    override fun getApplicationContext(): Context=CompanyContext(Accounts.root(baseContext).applicationContext,profileId)
    override fun getSharedPreferences(name: String,mode: Int): SharedPreferences {
        val suffix=if(profileId.isEmpty() || profileId==Accounts.LEGACY || name !in Accounts.scopedPreferences) "" else "_$profileId"
        return Accounts.root(baseContext).getSharedPreferences(name+suffix,mode)
    }
    override fun getFilesDir(): File=if(profileId.isEmpty() || profileId==Accounts.LEGACY) Accounts.root(baseContext).filesDir else File(Accounts.root(baseContext).filesDir,"companies/$profileId").apply{mkdirs()}
}

internal object Accounts {
    const val LEGACY="legacy"
    const val EXTRA="peygir_account"
    val revision=MutableStateFlow(0L)
    val scopedPreferences=setOf("shared_private","shared_status","organization_access","push_private","settings","drafts")
    fun root(c: Context): Context=if(c is CompanyContext) root(c.baseContext) else c
    private fun prefs(c: Context)=root(c).getSharedPreferences("company_profiles",0)
    fun explicit(c: Context): String?=when(c){is CompanyContext->c.profileId;is ContextWrapper->if(c.baseContext!==c) explicit(c.baseContext) else null;else->null}
    fun id(c: Context)=explicit(c) ?: active(c)
    fun active(c: Context)=prefs(c).getString("active","").orEmpty()
    fun context(c: Context,id: String=id(c)): Context=if(c is CompanyContext && c.profileId==id) c else CompanyContext(root(c),id)
    fun list(c: Context): List<CompanyProfile> = runCatching{val a=JSONArray(prefs(c).getString("profiles","[]"));(0 until a.length()).map{CompanyProfile.from(a.getJSONObject(it))}}.getOrDefault(emptyList())
    fun profile(c: Context,id: String=id(c))=list(c).find{it.id==id}
    private fun write(c: Context,items: List<CompanyProfile>,active: String=active(c)) {check(prefs(c).edit().putString("profiles",JSONArray(items.map{it.json()}).toString()).putString("active",active).commit());revision.value++}
    @Synchronized fun initialize(c: Context,read: (Context)->SharedSession?={SharedCredentials(it).read()}) {
        if(list(c).isNotEmpty()) return
        val old=context(c,LEGACY)
        val session=runCatching{read(old)}.getOrNull() ?: return
        register(c,LEGACY,session,true)
    }
    fun matching(c: Context,s: SharedSession)=list(c).find{it.endpoint==s.endpoint && it.company==s.company && it.user==s.userId && it.workspace==s.workspace}
    @Synchronized fun register(c: Context,id: String,s: SharedSession,select: Boolean=false) {
        require(validId(id))
        val p=CompanyProfile(id,s.endpoint,s.company,s.userId,s.workspace,s.workspaceName.ifBlank{s.company.ifBlank{"فضای مشترک"}},s.name)
        val wasEmpty=list(c).isEmpty()
        val previous=list(c)
        val items=if(previous.any{it.id==id}) previous.map{if(it.id==id) p else it} else previous+p
        write(c,items,if(select) id else active(c))
        if(wasEmpty) WidgetAccounts.pinExisting(c,id)
    }
    fun validId(id: String)=id==LEGACY || id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
    @Synchronized fun select(c: Context,id: String) {
        require(profile(c,id)!=null) {"حساب شرکت در این گوشی موجود نیست."}
        check(prefs(c).edit().putString("active",id).commit())
        val scoped=context(c,id);Organization.members.value=Organization.users(Store.get(scoped));Organization.contacts.value=Organization.contactNames(Store.get(scoped))
        clearUnread(c,id);SharedSync.showConnection(scoped);revision.value++;SharedSync.revision.value++
    }
    @Synchronized fun publishPeople(c: Context,id: String,users: List<CompanyUser>?=null,contacts: List<String>?=null) {
        if(id!=active(c) && !(id.isEmpty() && active(c)==LEGACY)) return
        users?.let{Organization.members.value=it};contacts?.let{Organization.contacts.value=it}
    }
    fun contexts(c: Context): List<Context> = list(c).map{context(c,it.id)}.ifEmpty{listOf(context(c,""))}
    fun unread(c: Context,id: String)=prefs(c).getInt("unread_$id",0)
    @Synchronized fun markUnread(c: Context,id: String) {prefs(c).edit().putInt("unread_$id",(unread(c,id)+1).coerceAtMost(999)).commit();revision.value++}
    fun clearUnread(c: Context,id: String) {prefs(c).edit().remove("unread_$id").apply();revision.value++}
    @Synchronized fun remove(c: Context,id: String) {
        val items=list(c).filterNot{it.id==id};val next=if(active(c)==id) items.firstOrNull()?.id.orEmpty() else active(c)
        cancelNotifications(context(c,id));write(c,items,next);clearUnread(c,id)
        if(next.isNotEmpty()) select(c,next) else {Organization.members.value=emptyList();Organization.contacts.value=emptyList();SharedSync.connection.reset();SharedSync.revision.value++}
    }
    fun route(c: Context): String {
        val p=context(c).getSharedPreferences("push_private",0)
        return p.getString("route",null) ?: UUID.randomUUID().toString().also{check(p.edit().putString("route",it).commit())}
    }
    /** Missing routes on older servers are accepted only when the account match is unambiguous. */
    fun pushTarget(c: Context,data: Map<String,String>,sender: String?): Context? {
        val route=data["accountRoute"]
        val candidates=contexts(c).filter {a->
            SharedCredentials(a).connected() && !a.getSharedPreferences("shared_status",0).getBoolean("auth_expired",false) && OrgAccess.id(a)==data["userId"] &&
                ((OrgAccess.company(a).isEmpty() && data["company"]==null) || OrgAccess.company(a)==data["company"]) &&
                (route==null || a.getSharedPreferences("push_private",0).getString("route",null)==route) &&
                (sender==null || a.getSharedPreferences("push_private",0).getString("sender",sender)==sender)
        }
        return candidates.singleOrNull()
    }
    fun intent(c: Context,intent: Intent): Intent=intent.putExtra(EXTRA,id(c))
    fun fromIntent(c: Context,intent: Intent): Context? {
        if(!intent.hasExtra(EXTRA)) return if(list(c).size<=1) context(c) else null
        val id=intent.getStringExtra(EXTRA).orEmpty()
        return if(id.isEmpty() && list(c).isEmpty() || profile(c,id)!=null) context(c,id) else null
    }
    fun notificationTag(c: Context): String?=id(c).takeIf{it.isNotEmpty() && it!=LEGACY}?.let{"account:$it"}
    fun cancelTask(c: Context,id: Int) {c.getSystemService(NotificationManager::class.java).cancel(notificationTag(c),id)}
    fun cancelNotifications(c: Context) {
        val manager=c.getSystemService(NotificationManager::class.java);val tag=notificationTag(c)
        manager.activeNotifications.filter{it.tag==tag || it.notification.extras.getString(EXTRA)==id(c)}.forEach{manager.cancel(it.tag,it.id)}
    }
}
