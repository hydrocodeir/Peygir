package ir.peygir.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow

internal data class ServerState(val connected: Boolean=false,val checking: Boolean=false,val loginRequired: Boolean=false,
    val lastSuccess: Long=0,val nextAttempt: Long=0,val failures: Int=0) {
    val compactLabel get()=when {loginRequired->"ورود دوباره";connected->"متصل";checking->"اتصال…";failures>0->"قطع";else->"اتصال…"}
    val label get()=when {loginRequired->"ورود دوباره لازم است";connected->"متصل";checking->"در حال اتصال…";failures>0->"قطع - تلاش مجدد خودکار";else->"در انتظار اتصال"}
}
internal class ConnectionTracker(private val now: ()->Long = System::currentTimeMillis) {
    val state=MutableStateFlow(ServerState())
    fun reset(){state.value=ServerState()}
    fun start(){state.value=state.value.copy(checking=true)}
    fun success(){val at=now();state.value=ServerState(connected=true,lastSuccess=at,nextAttempt=at+30_000)}
    fun failure(auth: Boolean=false) {
        val old=state.value;val failures=(old.failures+1).coerceAtMost(100)
        state.value=old.copy(connected=false,checking=false,loginRequired=auth,failures=failures,nextAttempt=if(auth) Long.MAX_VALUE else now()+retryDelay(failures))
    }
    fun networkLost(){val old=state.value;if(!old.loginRequired) state.value=old.copy(connected=false,failures=maxOf(1,old.failures))}
    fun due()=!state.value.loginRequired && now()>=state.value.nextAttempt
    companion object {fun retryDelay(failures: Int)=listOf(10_000L,30_000L,60_000L,120_000L,300_000L)[(failures-1).coerceIn(0,4)]}
}
internal object ServerNetwork {
    private var manager: ConnectivityManager?=null
    private var observer: ConnectivityManager.NetworkCallback?=null
    @Synchronized fun observe(context: Context) {
        if(observer!=null || Accounts.contexts(context).none{SharedCredentials(it).connected()}) return
        val app=Accounts.root(context).applicationContext
        val service=app.getSystemService(ConnectivityManager::class.java) ?: return
        val callback=object: ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {SharedSync.request(app)}
            override fun onLost(network: Network) {if(service.activeNetwork==null) SharedSync.connection.networkLost()}
        }
        runCatching{service.registerDefaultNetworkCallback(callback)}.onSuccess{manager=service;observer=callback}
    }
    @Synchronized fun stop(){observer?.let{runCatching{manager?.unregisterNetworkCallback(it)}};observer=null;manager=null}
}
