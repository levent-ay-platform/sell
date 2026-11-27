package tn.malak.app.ui.parcel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tn.malak.app.R
import tn.malak.app.ui.*
import tn.malak.app.ui.explore.*
import java.util.Locale

@Composable
internal fun PlanMapControlEditor(draft:OwnerPlanDraft,start:DiscoveryPoint?,onDismiss:()->Unit,onAccept:(List<DiscoveryPoint>)->Unit){
    val vertices=remember(draft){openParcelRing(draft.vertices)}
    val indices=remember(vertices){planControlIndices(vertices)}
    var controls by remember(draft){mutableStateOf(emptyList<DiscoveryPoint>())}
    var ready by remember{mutableStateOf(false)}
    var failed by remember{mutableStateOf(false)}
    var zoom by remember{mutableStateOf(if(start!=null)17.0 else 7.0)}
    val fit=remember(vertices,indices,controls){fitPlanToMap(vertices,indices,controls)}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
        Surface(Modifier.fillMaxSize(),color=Brand.Pearl){
            Column(Modifier.fillMaxSize().systemBarsPadding()){
                PremiumPageHeading(s(R.string.intake_georef_title),onDismiss)
                if(indices.size!=4){
                    Text(s(R.string.intake_georef_unavailable),Modifier.padding(16.dp))
                }else{
                    Text(s(R.string.intake_georef_instruction),Modifier.padding(horizontal=16.dp,vertical=4.dp),color=Brand.Muted)
                    val minX=vertices.minOf{it.x};val minY=vertices.minOf{it.y}
                    val spanX=vertices.maxOf{it.x}-minX;val spanY=vertices.maxOf{it.y}-minY
                    Canvas(Modifier.fillMaxWidth().height(122.dp).padding(horizontal=16.dp)
                        .background(Color.White,RoundedCornerShape(12.dp))){
                        val scale=minOf((size.width-48)/spanX,(size.height-38)/spanY)
                        fun screen(p:ParcelPoint)=Offset(((p.x-minX)*scale+(size.width-spanX*scale)/2).toFloat(),
                            ((p.y-minY)*scale+(size.height-spanY*scale)/2).toFloat())
                        val shape=Path().apply{
                            val first=screen(vertices.first());moveTo(first.x,first.y)
                            vertices.drop(1).forEach{val q=screen(it);lineTo(q.x,q.y)};close()
                        }
                        drawPath(shape,Brand.Crimson.copy(alpha=.10f))
                        drawPath(shape,Brand.Crimson,style=Stroke(width=2.dp.toPx()))
                        indices.forEachIndexed{i,index->
                            val at=screen(vertices[index]);val active=i==controls.size
                            drawCircle(if(active)Brand.Crimson else if(i<controls.size)IntakeGreen else Color.White,
                                radius=12.dp.toPx(),center=at)
                            drawCircle(Brand.Crimson,radius=12.dp.toPx(),center=at,style=Stroke(width=1.dp.toPx()))
                            drawContext.canvas.nativeCanvas.drawText((i+1).toString(),at.x,at.y+4.dp.toPx(),
                                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{
                                    textAlign=android.graphics.Paint.Align.CENTER;textSize=12.dp.toPx()
                                    color=if(active||i<controls.size)android.graphics.Color.WHITE else android.graphics.Color.BLACK
                                })
                        }
                    }
                    Text(s(R.string.intake_georef_progress,controls.size),Modifier.padding(horizontal=16.dp,vertical=5.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()){
                        DiscoveryMap(styleUrl=DiscoveryStyleUrl,
                            initial=DiscoveryCamera(start?:InitialCenter,if(start!=null)17.0 else 7.0,InitialBounds),
                            jump=null,markers=DiscoveryMarkers(emptyList()),selectedId=null,priceLabel={""},
                            modifier=Modifier.fillMaxSize(),onCameraIdle={cam,_->zoom=cam.zoom},
                            onListing={},onCluster={},onFailure={failed=true;ready=false},onReady={ready=true;failed=false},
                            draftBoundary=fit?.points?:controls,onDraftVertex={point->
                                if(ready&&zoom>=16&&controls.size<4&&point in InitialBounds&&point !in controls)
                                    controls=controls+point
                            })
                        if(!ready)Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
                            if(failed)Text(s(R.string.v2_tiles_error))else CircularProgressIndicator()
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                            TextButton(onClick={controls=controls.dropLast(1)},enabled=controls.isNotEmpty()){Text(s(R.string.intake_undo_vertex))}
                            TextButton(onClick={controls=emptyList()},enabled=controls.isNotEmpty()){Text(s(R.string.intake_clear_boundary))}
                        }
                        if(zoom<16)Text(s(R.string.intake_zoom_boundary),color=Brand.Muted)
                        if(controls.size==4&&fit==null)Text(s(R.string.intake_georef_failed),color=Brand.Crimson)
                        fit?.let{Text(s(R.string.intake_georef_residual,String.format(Locale.ROOT,"%.2f",it.checkErrorM)),color=IntakeGreen)}
                        Button(onClick={fit?.let{onAccept(it.points)}},enabled=fit!=null,modifier=Modifier.fillMaxWidth()){
                            Text(s(R.string.intake_georef_accept))
                        }
                        Text(s(R.string.intake_georef_disclaimer),style=MaterialTheme.typography.bodySmall,color=Brand.Muted)
                    }
                }
            }
        }
    }
}
