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

/** Live guidance is about lighting/contrast, NOT a false assertion that the
 * card was located, its edges aligned or its identity verified.
 */
private enum class LightCheck { WAIT, GOOD, TOO_DARK, GLARE, LOW_CONTRAST }

/** Sample only the luminance plane (no face processing or frame storage).
 * Sparse, capped sampling keeps preview responsive on budget Android devices.
 */
private fun checkLight(frame:ImageProxy):LightCheck {
    val yPlane=frame.planes.firstOrNull()?:return LightCheck.WAIT
    val buffer=yPlane.buffer
    val width=frame.width;val height=frame.height
    if(width<=0||height<=0)return LightCheck.WAIT
    var total=0.0;var square=0.0;var bright=0;var count=0
    for(y in height/4 until 3*height/4 step (height/26).coerceAtLeast(1)){
        for(x in width/5 until 4*width/5 step (width/34).coerceAtLeast(1)){
            val offset=y*yPlane.rowStride+x*yPlane.pixelStride
            if(offset>=buffer.limit())continue
            val lum=(buffer.get(offset).toInt() and 255).toDouble()
            total+=lum;square+=lum*lum;count++
            if(lum>246)bright++
        }
    }
    if(count<40)return LightCheck.WAIT
    val mean=total/count
    val deviation=sqrt((square/count-mean*mean).coerceAtLeast(0.0))
    return when {
        mean<56->LightCheck.TOO_DARK
        bright.toDouble()/count>.43->LightCheck.GLARE
        deviation<16->LightCheck.LOW_CONTRAST
        else->LightCheck.GOOD
    }
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
    back:Boolean,
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
    var lighting by remember {mutableStateOf(LightCheck.WAIT)}
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
                    val analyzer=ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analyzer.setAnalyzer(executor) { frame->
                        try {
                            frames++
                            if(frames%5==0){
                                val measure=checkLight(frame)
                                handler.post{
                                    if(active.get()&&valid.get())lighting=measure
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
    val hint=when(lighting){
        LightCheck.TOO_DARK->R.string.camera_more_light
        LightCheck.GLARE->R.string.camera_reduce_glare
        LightCheck.LOW_CONTRAST->R.string.camera_move_closer
        LightCheck.GOOD->R.string.camera_hold_steady
        LightCheck.WAIT->R.string.camera_align_hint
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
                    Text(s(if(back)R.string.camera_scan_back else R.string.camera_scan_front),
                        color=Color.White,fontSize=19.sp)
                }
                Box(Modifier.align(Alignment.Center)
                    .fillMaxWidth(.91f).aspectRatio(1.585f)
                    .border(BorderStroke(3.dp,if(lighting==LightCheck.GOOD)
                        Color(0xFF5AD3A2) else Color.White),
                        RoundedCornerShape(19.dp))){
                    // The wireframe marks the intended document bounds.
                    Box(Modifier.align(Alignment.TopCenter).padding(top=8.dp)
                        .background(Color.Black.copy(alpha=.57f),RoundedCornerShape(10.dp))
                        .padding(horizontal=12.dp,vertical=5.dp)){
                        Text(s(R.string.camera_align_hint),color=Color.White,fontSize=12.sp)
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Color.Black.copy(alpha=.76f))
                    .padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally){
                    Text(s(hint),color=Color.White,fontSize=15.sp)
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
                    },enabled=ready&&!capturing&&
                        lighting!=LightCheck.TOO_DARK&&lighting!=LightCheck.GLARE,
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
