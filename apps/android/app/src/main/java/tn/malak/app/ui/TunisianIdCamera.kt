package tn.malak.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tn.malak.app.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Rotated center-frame luminance sample for fast, private capture guidance.
 * These statistics cannot by themselves prove an ID or text is legible.
 */
private fun checkLight(frame:ImageProxy,previous:IntArray?):Pair<FrameAssessment,IntArray> {
    val plane=frame.planes.firstOrNull()?:return FrameAssessment(CaptureHint.WAIT) to IntArray(0)
    val buffer=plane.buffer
    val rot=frame.imageInfo.rotationDegrees
    val width=if(rot%180==0)frame.width else frame.height
    val height=if(rot%180==0)frame.height else frame.width
    if(width<64||height<64)return FrameAssessment(CaptureHint.WAIT) to IntArray(0)
    val roiW=(width*.99).toInt()
    val roiH=minOf((roiW/1.38f).toInt(),(height*.74).toInt())
    val left=(width-roiW)/2;val top=(height-roiH)/2
    val cols=160;val rows=112
    val samples=IntArray(cols*rows)
    for(r in 0 until rows)for(c in 0 until cols){
        val u=left+(c*roiW+roiW/2)/cols
        val v=top+(r*roiH+roiH/2)/rows
        val (x,y)=when(rot){
            90->v to (frame.height-1-u)
            180->(frame.width-1-u) to (frame.height-1-v)
            270->(frame.width-1-v) to u
            else->u to v
        }
        val offset=y*plane.rowStride+x*plane.pixelStride
        if(x<0||y<0||x>=frame.width||y>=frame.height||
            offset !in 0 until buffer.limit())
            return FrameAssessment(CaptureHint.WAIT) to IntArray(0)
        samples[r*cols+c]=buffer.get(offset).toInt() and 255
    }
    return CaptureFrameAssessment.inspect(samples,cols,rows,previous) to samples
}

private fun rotateAndEncode(image:ImageProxy):ByteArray {
    if(image.format!=ImageFormat.JPEG &&
        !(android.os.Build.VERSION.SDK_INT>=34 &&
            image.format==ImageFormat.JPEG_R))
        throw IllegalStateException("UNSUPPORTED_CAMERA_FRAME")
    val source=image.planes[0].buffer
    val raw=ByteArray(source.remaining())
    source.get(raw)
    val allocated=mutableListOf<Bitmap>()
    try{
        val decoded=try{BitmapFactory.decodeByteArray(raw,0,raw.size)}
            finally{raw.fill(0)}
            ?:throw IllegalStateException("CAMERA_DECODE")
        allocated.add(decoded)
        // CameraX ViewPort ensures the capture and PreviewView share a field
        // of view; in-memory JPEG can still include uncropped sensor pixels.
        val rect=image.cropRect
        val left=rect.left.coerceIn(0,decoded.width-1)
        val top=rect.top.coerceIn(0,decoded.height-1)
        val cropW=rect.width().coerceAtMost(decoded.width-left)
        val cropH=rect.height().coerceAtMost(decoded.height-top)
        val field=if(cropW>32&&cropH>32&&
            (left!=0||top!=0||cropW!=decoded.width||cropH!=decoded.height)){
            Bitmap.createBitmap(decoded,left,top,cropW,cropH).also{allocated.add(it)}
        }else decoded
        val degrees=image.imageInfo.rotationDegrees
        val oriented=if(degrees!=0){
            val m=Matrix().apply{postRotate(degrees.toFloat())}
            Bitmap.createBitmap(field,0,0,field.width,field.height,m,true).also{
                allocated.add(it)
            }
        }else field
        // The overlay is centered and 91% of the visible preview width.
        // Add a small margin so document corners remain visible to OCR.
        val width=minOf((oriented.width*.96f).toInt(),
            (oriented.height*.96f*1.585f).toInt()).coerceAtLeast(40)
        val height=(width/1.585f).toInt().coerceAtLeast(25)
        val framed=Bitmap.createBitmap(oriented,
            ((oriented.width-width)/2).coerceAtLeast(0),
            ((oriented.height-height)/2).coerceAtLeast(0),
            width.coerceAtMost(oriented.width),
            height.coerceAtMost(oriented.height)).also{allocated.add(it)}
        val maxEdge=maxOf(framed.width,framed.height)
        val resized=if(maxEdge>2400) {
            val factor=2400f/maxEdge
            Bitmap.createScaledBitmap(framed,
                (framed.width*factor).toInt().coerceAtLeast(1),
                (framed.height*factor).toInt().coerceAtLeast(1),true).also{
                allocated.add(it)
            }
        }else framed
        val out=ByteArrayOutputStream()
        check(resized.compress(Bitmap.CompressFormat.JPEG,86,out))
        val bytes=out.toByteArray()
        require(bytes.size in 20..12*1024*1024)
        return bytes
    }finally{
        raw.fill(0)
        allocated.asReversed().distinct().forEach{
            if(!it.isRecycled)it.recycle()
        }
    }
}

/** Real back-camera CameraX preview. A bounded in-memory JPEG is emitted only
 * on user shutter; the next UI immediately scans and can offer retake.
 */
@Composable
internal fun TunisianIdCamera(
    onClose:()->Unit,
    onPhoto:(android.net.Uri,Long)->Unit
){
    val context=LocalContext.current
    val lifecycleOwner=LocalLifecycleOwner.current
    val handler=remember{Handler(Looper.getMainLooper())}
    val executor=remember{Executors.newSingleThreadExecutor()}
    val active=remember{AtomicBoolean(true)}
    var permission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context,Manifest.permission.CAMERA)
            ==PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission=rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()){permission=it}
    LaunchedEffect(Unit){
        if(!permission)requestPermission.launch(Manifest.permission.CAMERA)
    }
    var pictureQuality by remember {mutableStateOf(FrameAssessment(CaptureHint.WAIT))}
    var ready by remember {mutableStateOf(false)}
    var capturing by remember {mutableStateOf(false)}
    var cameraError by remember {mutableStateOf(false)}
    val previewView=remember {
        PreviewView(context).apply {
            scaleType=PreviewView.ScaleType.FILL_CENTER
            implementationMode=PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val capture=remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            // Avoid default maximum sensor-size JPEGs and large peak memory.
            // ~4MP is sufficient for a framed card at a 2400 px OCR ceiling.
            .setResolutionSelector(ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(2400,1800),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build())
            .build()
    }
    DisposableEffect(lifecycleOwner,permission){
        var provider:ProcessCameraProvider?=null
        val valid=AtomicBoolean(true)
        if(permission){
            val future=ProcessCameraProvider.getInstance(context)
            future.addListener({
                if(valid.get())try {
                    provider=future.get()
                    val preview=Preview.Builder().build().also{
                        it.surfaceProvider=previewView.surfaceProvider
                    }
                    var frames=0
                    var previous:IntArray?=null
                    var stable=0
                    val analyzer=ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analyzer.setAnalyzer(executor) { frame->
                        try {
                            frames++
                            if(frames%5==0){
                                val (measure,pixels)=checkLight(frame,previous)
                                previous=pixels.takeIf{it.isNotEmpty()}
                                stable=if(measure.hint==CaptureHint.GOOD)
                                    (stable+1).coerceAtMost(3) else 0
                                // Require two matching stable analyzer samples.
                                val result=if(measure.hint==CaptureHint.GOOD&&stable<2)
                                    measure.copy(hint=CaptureHint.WAIT) else measure
                                handler.post {
                                    if(active.get()&&valid.get())pictureQuality=result
                                }
                            }
                        }finally{frame.close()}
                    }
                    previewView.post {
                        if(valid.get())try {
                            provider?.unbindAll()
                            val group=UseCaseGroup.Builder()
                                .addUseCase(preview).addUseCase(capture)
                                .addUseCase(analyzer)
                            previewView.viewPort?.let{group.setViewPort(it)}
                            provider?.bindToLifecycle(lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,group.build())
                            ready=true
                        }catch (_:Exception){cameraError=true}
                    }
                }catch (_:Exception){cameraError=true}
            },context.mainExecutor)
        }
        onDispose{
            valid.set(false)
            ready=false
            provider?.unbindAll()
        }
    }
    DisposableEffect(Unit){onDispose{active.set(false);executor.shutdown()}}
    BackHandler{onClose()}
    val hint=when(pictureQuality.hint){
        CaptureHint.DARK->R.string.camera_more_light
        CaptureHint.GLARE->R.string.camera_reduce_glare
        CaptureHint.TOO_FAR->R.string.camera_move_closer
        CaptureHint.MOVE_BACK->R.string.camera_move_back
        CaptureHint.MOVING->R.string.camera_stabilize
        CaptureHint.FOCUS->R.string.camera_focus_fields
        CaptureHint.TILT->R.string.camera_correct_angle
        CaptureHint.GOOD->R.string.camera_hold_steady
        CaptureHint.WAIT->R.string.camera_align_hint
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF10151C))){
        if(permission){
            AndroidView(factory={previewView},modifier=Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize()){
                Row(Modifier.align(Alignment.TopStart).fillMaxWidth()
                    .background(Color.Black.copy(alpha=.40f)).padding(20.dp),
                    verticalAlignment=Alignment.CenterVertically){
                    IconButton(onClick=onClose){
                        Icon(Icons.Outlined.Close,s(R.string.back),tint=Color.White)
                    }
                    Text(s(R.string.camera_scan_front),
                        color=Color.White,fontSize=19.sp)
                }
                Box(Modifier.align(Alignment.Center)
                    .fillMaxWidth(.84f).aspectRatio(1.585f)
                    .border(BorderStroke(2.dp,Color.White.copy(alpha=.55f)),
                        RoundedCornerShape(19.dp))){
                    // Guides approximate printed zones; green does NOT mean OCR passed.
                    Box(Modifier.align(Alignment.TopCenter).padding(top=10.dp)
                        .fillMaxWidth(.48f)
                        .border(1.dp,Color(0xFF5AD3A2),RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha=.50f),RoundedCornerShape(6.dp))
                        .padding(4.dp),contentAlignment=Alignment.Center){
                        Text("CIN · 8",color=Color.White,fontSize=11.sp)
                    }
                    Column(Modifier.align(Alignment.CenterEnd).fillMaxWidth(.48f)
                        .padding(end=10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                        listOf(R.string.local_surname,R.string.local_given_name,
                            R.string.camera_review_birth).forEach { label ->
                            Box(Modifier.fillMaxWidth()
                                .border(1.dp,Color(0xFF5AD3A2),RoundedCornerShape(5.dp))
                                .background(Color.Black.copy(alpha=.43f),RoundedCornerShape(5.dp))
                                .padding(4.dp)){
                                Text(s(label),color=Color.White,fontSize=10.sp,
                                    maxLines=1)
                            }
                        }
                    }
                }
                Canvas(Modifier.align(Alignment.Center)
                    .fillMaxWidth(.99f).aspectRatio(1.38f)){
                    val b=pictureQuality.bounds
                    if(b!=null){
                        val green=Color(0xFF5AD3A2)
                        val outline=if(pictureQuality.hint==CaptureHint.GOOD)
                            green else Color(0xFFFFD37C)
                        val x0=b.left*size.width
                        val y0=b.top*size.height
                        val x1=b.right*size.width
                        val y1=b.bottom*size.height
                        val path=Path().apply{
                            moveTo(x0,y0);lineTo(x1,y0);lineTo(x1,y1)
                            lineTo(x0,y1);close()
                        }
                        drawPath(path,outline,style=Stroke(width=3.dp.toPx()))
                        for(zoneIndex in CaptureFrameAssessment.zones.indices){
                            val z=CaptureFrameAssessment.zones[zoneIndex]
                            val good=pictureQuality.zones.getOrNull(zoneIndex)==true
                            val color=if(good) green else Color(0xFFFFD37C)
                            val zl=x0+(x1-x0)*z.x0;val zr=x0+(x1-x0)*z.x1
                            val zt=y0+(y1-y0)*z.y0;val zb=y0+(y1-y0)*z.y1
                            val fieldPath=Path().apply{
                                moveTo(zl,zt);lineTo(zr,zt);lineTo(zr,zb)
                                lineTo(zl,zb);close()
                            }
                            drawPath(fieldPath,color,style=Stroke(width=1.dp.toPx()))
                        }
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha=.76f))
                    .padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Text(s(hint),color=Color.White,fontSize=15.sp)
                    Text(s(R.string.camera_preview_quality_only),
                        color=Color.White.copy(alpha=.72f),fontSize=11.sp)
                    Spacer(Modifier.height(17.dp))
                    if(capturing)CircularProgressIndicator(color=Color.White)
                    else Button(onClick={
                        if(!active.get()||!ready||capturing)return@Button
                        capturing=true
                        cameraError=false
                        capture.takePicture(executor,object:ImageCapture.OnImageCapturedCallback(){
                            override fun onCaptureSuccess(image:ImageProxy){
                                try{
                                    val bytes=try{rotateAndEncode(image)}
                                        finally{image.close()}
                                    val uri=PrivateCaptureMemory.addJpeg(bytes)
                                    handler.post{
                                        if(active.get())onPhoto(uri,bytes.size.toLong())
                                        else PrivateCaptureMemory.discard(uri)
                                    }
                                }catch (_:Exception){
                                    handler.post{if(active.get()){
                                        cameraError=true;capturing=false
                                    }}
                                }
                            }
                            override fun onError(exception:ImageCaptureException){
                                handler.post{if(active.get()){
                                    cameraError=true;capturing=false
                                }}
                            }
                        })
                    },enabled=ready&&!capturing&&pictureQuality.hint==CaptureHint.GOOD,
                        colors=ButtonDefaults.buttonColors(containerColor=Color.White),
                        shape=CircleShape,modifier=Modifier.size(76.dp),
                        contentPadding=PaddingValues(0.dp)){
                        Icon(Icons.Outlined.CameraAlt,s(R.string.camera_shutter),
                            tint=Color(0xFF15252C),modifier=Modifier.size(32.dp))
                    }
                    if(cameraError)Text(s(R.string.camera_capture_error),
                        color=Color(0xFFFFC0C0),fontSize=12.sp)
                }
            }
        }else{
            Column(Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment=Alignment.CenterHorizontally){
                Text(s(R.string.camera_permission_needed),color=Color.White)
                Spacer(Modifier.height(16.dp))
                Button(onClick={requestPermission.launch(Manifest.permission.CAMERA)}){
                    Text(s(R.string.camera_retry_permission))
                }
                TextButton(onClick=onClose){Text(s(R.string.back))}
            }
        }
    }
}
