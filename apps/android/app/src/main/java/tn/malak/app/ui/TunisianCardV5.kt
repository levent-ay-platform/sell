package tn.malak.app.ui

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.yaml.snakeyaml.Yaml
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/**
 * Tunisian CIN recognizer: private, offline, on-device.
 * Only PUBLIC, SHA256-pinned PP-OCRv5 weights are bundled in the APK.
 * No photos, names, OCR text, or ground truth are sent to any service or logs.
 *
 * Detection / perspective normalization / adaptive Arabic line alignment
 * precede Arabic v5 CTC recognition. Text is only a candidate, never identity
 * authentication or proof of real estate ownership.
 */
internal object TunisianCardV5 {
    internal data class Person(
        val surname:String,val given:String,val confidence:Int
    ){
        val name:String get()="$surname $given"
    }
    private data class Word(val text:String,val confidence:Float)
    private val monitor=Any()
    private var environment:OrtEnvironment?=null
    private var session:OrtSession?=null
    private var letters:List<String>?=null
    private val openCvReady=AtomicBoolean(false)

    private fun init(context:Context) {
        synchronized(monitor){
            if(session!=null)return
            if(!openCvReady.get()){
                check(OpenCVLoader.initLocal()){"PRIVATE_CIN_OPENCV_MISSING"}
                openCvReady.set(true)
            }
            val model=context.assets.open("arabic-v5/inference.onnx").use{it.readBytes()}
            require(model.size in 7_000_000..10_000_000){"PUBLIC_V5_MODEL_MISSING"}
            val config=context.assets.open("arabic-v5/inference.yml").bufferedReader()
                .use{it.readText()}
            @Suppress("UNCHECKED_CAST")
            val yaml=Yaml().load<Map<String,Any>>(config)
            @Suppress("UNCHECKED_CAST")
            val chars=(yaml["PostProcess"] as Map<String,Any>)["character_dict"]
                as List<String>
            check(chars.size==747){"PUBLIC_V5_DICTIONARY_INCOMPLETE"}
            val env=OrtEnvironment.getEnvironment()
            val opts=OrtSession.SessionOptions().apply{
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            try{
                val loaded=env.createSession(model,opts)
                environment=env
                letters=chars+listOf(" ")
                session=loaded
            }finally{opts.close();model.fill(0)}
        }
    }
    private fun order(input:Array<Point>):Array<Point> {
        val pts=input.toList()
        return arrayOf(
            pts.minBy{it.x+it.y},
            pts.minBy{it.y-it.x},
            pts.maxBy{it.x+it.y},
            pts.maxBy{it.y-it.x}
        )
    }
    private fun dist(a:Point,b:Point):Double=hypot(a.x-b.x,a.y-b.y)
    private fun detectQuad(bitmap:Bitmap):Array<Point>? {
        val rgba=Mat()
        val small=Mat()
        val gray=Mat()
        val blur=Mat()
        val canny=Mat()
        val close=Mat()
        val hierarchy=Mat()
        val kernel=Imgproc.getStructuringElement(
            Imgproc.MORPH_RECT,Size(7.0,7.0))
        val contours=ArrayList<MatOfPoint>()
        try{
            Utils.bitmapToMat(bitmap,rgba)
            val scale=min(1.0,900.0/maxOf(bitmap.width,bitmap.height))
            Imgproc.resize(rgba,small,
                Size(bitmap.width*scale,bitmap.height*scale))
            Imgproc.cvtColor(small,gray,Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray,blur,Size(5.0,5.0),0.0)
            Imgproc.Canny(blur,canny,35.0,110.0)
            Imgproc.morphologyEx(canny,close,Imgproc.MORPH_CLOSE,kernel)
            Imgproc.findContours(close,contours,hierarchy,
                Imgproc.RETR_LIST,Imgproc.CHAIN_APPROX_SIMPLE)
            var best:Array<Point>?=null
            var score=Double.NEGATIVE_INFINITY
            val area=small.width().toDouble()*small.height()
            for(contour in contours){
                val candidateArea=Imgproc.contourArea(contour)
                if(candidateArea<area*.12)continue
                val poly=MatOfPoint2f(*contour.toArray())
                try{
                    val length=Imgproc.arcLength(poly,true)
                    for(epsilon in doubleArrayOf(.014,.025,.04,.06)){
                        val points=MatOfPoint2f()
                        try{
                            Imgproc.approxPolyDP(poly,points,length*epsilon,true)
                            if(points.rows()!=4)continue
                            val corners=MatOfPoint(*points.toArray())
                            val convex=try{Imgproc.isContourConvex(corners)}
                                finally{corners.release()}
                            if(!convex)continue
                            val ordered=order(points.toArray())
                            val width=(dist(ordered[0],ordered[1])+
                                dist(ordered[2],ordered[3]))/2.0
                            val height=(dist(ordered[0],ordered[3])+
                                dist(ordered[1],ordered[2]))/2.0
                            val ratio=width/height.coerceAtLeast(1.0)
                            if(ratio !in 1.30..2.05)continue
                            val quality=2*candidateArea/area-abs(ratio-1.585)*.4
                            if(quality>score){
                                score=quality
                                best=ordered.map{Point(it.x/scale,it.y/scale)}
                                    .toTypedArray()
                            }
                        }finally{points.release()}
                    }
                }finally{poly.release()}
            }
            return best
        }finally{
            contours.forEach{it.release()}
            listOf(rgba,small,gray,blur,canny,close,hierarchy,kernel)
                .forEach{it.release()}
        }
    }
    private fun upright(bitmap:Bitmap):Bitmap? {
        // A portrait sheet with a front and back stacked vertically (two
        // photos in one) is not a single CIN. Only the top front is used.
        val top=if(bitmap.height>bitmap.width*1.13){
            Bitmap.createBitmap(bitmap,
                (bitmap.width*.06f).toInt(),(bitmap.height*.012f).toInt(),
                (bitmap.width*.90f).toInt().coerceAtLeast(2),
                (bitmap.height*.478f).toInt().coerceAtLeast(2))
        }else bitmap
        try{
            val corners=detectQuad(top)
            if(corners==null){
                val ratio=top.width.toFloat()/top.height
                if(ratio !in 1.25f..2.05f)return null
                return Bitmap.createScaledBitmap(top,1200,760,true)
            }
            val src=Mat()
            val warped=Mat()
            val transform=Mat()
            val source=MatOfPoint2f(*corners)
            val target=MatOfPoint2f(
                Point(0.0,0.0),Point(1199.0,0.0),
                Point(1199.0,759.0),Point(0.0,759.0))
            try{
                Utils.bitmapToMat(top,src)
                val m=Imgproc.getPerspectiveTransform(source,target)
                try{
                    Imgproc.warpPerspective(src,warped,m,Size(1200.0,760.0),
                        Imgproc.INTER_LINEAR)
                }finally{m.release()}
                val ready=Bitmap.createBitmap(1200,760,Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(warped,ready)
                return ready
            }finally{
                src.release();warped.release();transform.release()
                source.release();target.release()
            }
        }finally{if(top!==bitmap)top.recycle()}
    }
    private fun fieldCenters(card:Bitmap):TunisianNameRows.PairOfRows? {
        val w=card.width;val h=card.height
        val pixels=IntArray(w*h)
        card.getPixels(pixels,0,w,0,0,w,h)
        val x0=(w*.66).toInt();val x1=(w*.88).toInt()
        val sample=IntArray(((h*.26).toInt()/3+2)*((x1-x0)/4+2))
        var count=0
        for(y in (h*.43).toInt() until (h*.69).toInt() step 3){
            for(x in x0 until x1 step 4){
                val p=pixels[y*w+x]
                sample[count++]=(Color.red(p)*299+Color.green(p)*587+
                    Color.blue(p)*114)/1000
            }
        }
        if(count<50)return null
        val ordered=sample.copyOf(count).also{it.sort()}
        val median=ordered[count/2]
        val limit=(if(median>185)median-40 else median-25).coerceIn(90,155)
        val density=DoubleArray(h)
        for(y in (h*.40).toInt() until (h*.72).toInt()){
            var dark=0
            for(x in x0 until x1){
                val p=pixels[y*w+x]
                val lum=(Color.red(p)*299+Color.green(p)*587+
                    Color.blue(p)*114)/1000
                if(lum<limit)dark++
            }
            density[y]=dark.toDouble()/(x1-x0)
        }
        return TunisianNameRows.locate(density)
    }
    private fun fieldCrop(card:Bitmap,center:Int):Bitmap{
        val x=(card.width*.46).toInt()
        val end=(card.width*.88).toInt()
        val top=(center-card.height*.05).toInt().coerceAtLeast(0)
        val bottom=(center+card.height*.046).toInt()
            .coerceAtMost(card.height)
        return Bitmap.createBitmap(card,x,top,end-x,bottom-top)
    }
    private fun recognize(crop:Bitmap):Word {
        val env=environment?:return Word("",0f)
        val model=session?:return Word("",0f)
        val dict=letters?:return Word("",0f)
        val w=320;val h=48
        val width=min(320,ceil(48.0*crop.width/crop.height).toInt())
            .coerceAtLeast(1)
        val resized=Bitmap.createScaledBitmap(crop,width,h,true)
        try{
            val pixels=IntArray(width*h)
            resized.getPixels(pixels,0,width,0,0,width,h)
            val data=ByteBuffer.allocateDirect(3*w*h*4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            val plane=w*h
            val input=FloatArray(3*plane)
            for(y in 0 until h)for(x in 0 until width){
                val pix=pixels[y*width+x]
                val i=y*w+x
                input[i]=(Color.blue(pix)/255f-.5f)/.5f
                input[plane+i]=(Color.green(pix)/255f-.5f)/.5f
                input[2*plane+i]=(Color.red(pix)/255f-.5f)/.5f
            }
            data.put(input);data.rewind()
            OnnxTensor.createTensor(env,data,longArrayOf(1,3,48,320)).use{tensor->
                model.run(mapOf(model.inputNames.first() to tensor)).use{result->
                    @Suppress("UNCHECKED_CAST")
                    val scores=(result[0].value as Array<Array<FloatArray>>)[0]
                    var last=-1
                    var total=0f
                    var used=0
                    val visual=StringBuilder()
                    for(row in scores){
                        var best=0
                        var prob=row[0]
                        for(i in 1 until row.size){
                            if(row[i]>prob){best=i;prob=row[i]}
                        }
                        if(best>0&&best!=last&&best-1 in dict.indices){
                            visual.append(dict[best-1])
                            total+=prob
                            used++
                        }
                        last=best
                    }
                    return Word(visual.reverse().toString().trim(),
                        if(used>0)total/used else 0f)
                }
            }
        }finally{if(resized!==crop)resized.recycle()}
    }
    private fun validArabic(text:String):String? {
        val trimmed=text.replace(Regex("""\s+""")," ").trim()
        if(trimmed.length !in 2..55)return null
        if(!trimmed.all{it==' '||it in '\u0621'..'\u064a'})return null
        if(trimmed.split(' ').any{it.length<2})return null
        return trimmed
    }
    fun read(context:Context,bitmap:Bitmap):Person? = synchronized(monitor) {
        init(context)
        val card=upright(bitmap)?:return@synchronized null
        try{
            val aligned=fieldCenters(card)?:return@synchronized null
            val surnameCrop=fieldCrop(card,aligned.surnameCenter)
            val givenCrop=fieldCrop(card,aligned.givenCenter)
            try{
                val surname=recognize(surnameCrop)
                val given=recognize(givenCrop)
                if(surname.confidence<.72 || given.confidence<.72)
                    return@synchronized null
                val a=validArabic(surname.text)?:return@synchronized null
                val b=validArabic(given.text)?:return@synchronized null
                Person(a,b,(min(surname.confidence,given.confidence)*100)
                    .toInt().coerceIn(0,100))
            }finally{surnameCrop.recycle();givenCrop.recycle()}
        }finally{card.recycle()}
    }
}
