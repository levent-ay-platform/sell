package tn.malak.app.ui.parcel

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tn.malak.app.R
import tn.malak.app.ui.*
import tn.malak.app.ui.explore.*
import java.util.Locale

@Composable
internal fun OwnerMapBoundaryEditor(start:DiscoveryPoint?,existing:List<DiscoveryPoint>,
    onDismiss:()->Unit,onSave:(List<DiscoveryPoint>)->Unit,searchCamera:DiscoveryCamera?=null){
    var points by remember{mutableStateOf(existing)}
    var ready by remember{mutableStateOf(false)}
    var failed by remember{mutableStateOf(false)}
    var zoom by remember{mutableStateOf(if(start!=null||existing.isNotEmpty())17.0 else searchCamera?.zoom?:7.0)}
    val measurement=remember(points){measureOwnerMap(points)}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=Brand.Pearl){
            Column(Modifier.fillMaxSize().systemBarsPadding()){
                PremiumPageHeading(s(R.string.intake_map_boundary),onDismiss)
                Text(s(R.string.intake_map_instruction),Modifier.padding(horizontal=16.dp,vertical=5.dp),color=Brand.Muted)
                Box(Modifier.weight(1f).fillMaxWidth()){
                    DiscoveryMap(styleUrl=DiscoveryStyleUrl,
                        initial=if(start==null&&existing.isEmpty()&&searchCamera!=null)searchCamera else DiscoveryCamera(existing.firstOrNull()?:start?:InitialCenter,
                            if(start!=null||existing.isNotEmpty())17.0 else 7.0,InitialBounds),
                        jump=null,markers=DiscoveryMarkers(emptyList()),selectedId=null,priceLabel={""},
                        modifier=Modifier.fillMaxSize(),onCameraIdle={camera,_->zoom=camera.zoom},
                        onListing={},onCluster={},onFailure={failed=true;ready=false},onReady={ready=true;failed=false},
                        draftBoundary=points,onDraftVertex={point->
                            if(ready&&zoom>=16&&points.size<64&&point in InitialBounds&&point !in points)points=points+point
                        })
                    if(!ready)Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                        if(failed)Text(s(R.string.v2_tiles_error))else CircularProgressIndicator()
                    }
                }
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                        Text(s(R.string.parcel_editor_vertices,points.size),Modifier.weight(1f))
                        TextButton(onClick={points=points.dropLast(1)},enabled=points.isNotEmpty()){
                            Icon(Icons.Outlined.Undo,null,Modifier.size(18.dp));Text(s(R.string.intake_undo_vertex))
                        }
                        TextButton(onClick={points=emptyList()},enabled=points.isNotEmpty()){Text(s(R.string.intake_clear_boundary))}
                    }
                    if(zoom<16)Text(s(R.string.intake_zoom_boundary),color=Brand.Muted)
                    if(points.size>=3&&measurement==null)Text(s(R.string.parcel_editor_crossing),color=Brand.Crimson)
                    measurement?.let{m->
                        Text(s(R.string.parcel_editor_calculated_area,m.areaM2),style=MaterialTheme.typography.titleMedium)
                        Text(m.lengthsM.mapIndexed{i,length->"${i+1}: ${String.format(Locale.ROOT,"%.1f",length)} m"}.joinToString(" · "),
                            Modifier.heightIn(max=62.dp).verticalScroll(rememberScrollState()),color=Brand.Muted)
                    }
                    Button(onClick={onSave(points)},enabled=measurement!=null,modifier=Modifier.fillMaxWidth()){
                        Text(s(R.string.intake_keep_boundary))
                    }
                    Text(s(R.string.intake_boundary_draft_notice),color=Brand.Muted,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
