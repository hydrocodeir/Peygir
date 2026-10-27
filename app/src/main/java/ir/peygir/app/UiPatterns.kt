package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class PageAction(val label: String,val run: ()->Unit,val tag: String="",val destructive: Boolean=false)
@Composable fun CountBadge(count: Int,label: String,modifier: Modifier=Modifier,containerColor: Color=MaterialTheme.colorScheme.primaryContainer,contentColor: Color=MaterialTheme.colorScheme.onPrimaryContainer) {
    Badge(modifier=modifier.clearAndSetSemantics {contentDescription="تعداد $label: ${count.fa()}"},containerColor=containerColor,contentColor=contentColor) {
        Text(count.fa(),maxLines=1)
    }
}
@Composable fun CountLabel(label: String,count: Int,modifier: Modifier=Modifier,style: TextStyle=MaterialTheme.typography.labelMedium,color: Color=MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(modifier,verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(label,Modifier.weight(1f,fill=false),style=style,color=color,maxLines=1,overflow=TextOverflow.Ellipsis)
        CountBadge(count,label)
    }
}
@Composable fun PageActions(label: String,actions: List<PageAction>,enabled: Boolean=true) {
    var open by remember {mutableStateOf(false)}
    if(actions.isNotEmpty()) Box {
        IconButton(onClick={open=true},enabled=enabled){Icon(Icons.Outlined.MoreVert,label)}
        DropdownMenu(open,{open=false}) {actions.forEach {action->
            DropdownMenuItem(text={Text(action.label,color=if(action.destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)},onClick={open=false;action.run()},enabled=enabled,modifier=Modifier.testTag(action.tag))
        }}
    }
}
@Composable fun MoreDetails(open: Boolean,toggle: ()->Unit,tag: String) {
    TextButton(onClick=toggle,modifier=Modifier.fillMaxWidth().testTag(tag)) {Icon(if(open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,null);Spacer(Modifier.width(6.dp));Text("جزئیات بیشتر")}
}
@Composable fun TaskState(task: Resolution) {
    Text(task.status.label,style=MaterialTheme.typography.labelMedium,fontWeight=FontWeight.Medium,color=if(task.status==Status.REVIEW) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
}
object Presentation {
    fun taskAction(role: String,task: Resolution): Pair<String,Status>? = when {
        task.status==Status.DONE -> null
        task.status==Status.REVIEW && (task.assignee!=null || role=="deputy") && role!="manager" -> null
        task.status==Status.REVIEW && role=="manager" -> "تأیید نهایی انجام" to Status.DONE
        task.assignee!=null || role=="deputy" -> "ارسال برای تأیید" to Status.REVIEW
        else -> "انجام شد" to Status.DONE
    }
    fun order(today: Long,role: String)=compareBy<Resolution>{
        when {it.status==Status.DONE->5;role=="manager" && it.status==Status.REVIEW->0;it.status==Status.REVIEW->4;it.due<today->1;it.due==today->2;else->3}
    }.thenBy{it.due}.thenByDescending{it.priority.ordinal}.thenBy{it.id}
}
