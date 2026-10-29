package ir.peygir.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity

@Composable internal fun ServerTitle(title: String,state: ServerState?,compactTitle: Boolean=false,switchCompany: (()->Unit)?=null,details: ()->Unit) {
    Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f).heightIn(min=48.dp).then(if(switchCompany!=null) Modifier.clickable(onClickLabel="انتخاب شرکت",onClick=switchCompany).testTag("company_switcher") else Modifier),verticalArrangement=Arrangement.Center) {
            Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Text(title,Modifier.weight(1f,fill=false),style=if(compactTitle) MaterialTheme.typography.titleSmall.copy(lineHeight=18.sp) else MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis);if(switchCompany!=null) Icon(Icons.Outlined.ExpandMore,null,Modifier.size(16.dp))}
            if(state!=null) Text(state.compactLabel,style=MaterialTheme.typography.labelSmall,maxLines=1,overflow=TextOverflow.Ellipsis,
                modifier=Modifier.testTag("server_status_text").semantics{liveRegion=LiveRegionMode.Polite;contentDescription="اتصال سرور - ${state.label}"})
        }
        if(state!=null) IconButton(onClick=details,modifier=Modifier.testTag("server_connection")) {
            Icon(if(state.connected) Icons.Outlined.CloudDone else if(state.checking) Icons.Outlined.CloudSync else Icons.Outlined.CloudOff,
                "وضعیت سرور - ${state.label}",tint=if(state.connected) (if(MaterialTheme.colorScheme.surface.luminance()<0.5f) Color(0xFF8ADBA8) else Color(0xFF21834A)) else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable internal fun ServerRefresh(busy: Boolean,refresh: ()->Unit) {
    IconButton(onClick=refresh,enabled=!busy,modifier=Modifier.testTag("server_refresh")) {
        if(busy) CircularProgressIndicator(Modifier.size(22.dp).semantics{contentDescription="در حال تازه‌سازی"},strokeWidth=2.dp)
        else Icon(Icons.Outlined.Refresh,"تازه‌سازی اتصال و اطلاعات")
    }
}
