package tn.malak.app

import android.content.Intent
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in real HTTPS acceptance; fictional documents only, never runs by default. */
@RunWith(AndroidJUnit4::class)
class LivePilotJourneyTest{
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(instrumentation)
    private fun shot(name:String){
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"qa");folder.mkdirs()
        device.takeScreenshot(File(folder,"$name.png"))
        device.dumpWindowHierarchy(File(folder,"$name.xml"))
        Log.i("MalakLivePilot","CHECKPOINT "+name)
    }
    private fun visible(text:String,timeout:Long=20000):UiObject2{
        val start=android.os.SystemClock.uptimeMillis()
        device.wait(Until.findObject(By.text(text)),minOf(timeout,3000))?.let{return it}
        var step=0
        while(android.os.SystemClock.uptimeMillis()-start<timeout){
            val down=step%20<10
            val x=device.displayWidth/2
            val low=(device.displayHeight*.78).toInt();val high=(device.displayHeight*.32).toInt()
            device.swipe(x,if(down)low else high,x,if(down)high else low,14)
            device.wait(Until.findObject(By.text(text)),350)?.let{return it}
            step++
        }
        throw AssertionError("Missing UI text: "+text)
    }
    private fun tap(text:String){visible(text).click();device.waitForIdle()}
    private fun hideKeyboard(){
        device.waitForIdle(1000)
        val google=device.wait(Until.hasObject(By.pkg("com.google.android.inputmethod.latin")),2000)
        if(google||device.hasObject(By.pkg("com.android.inputmethod.latin"))){
            device.pressBack()
            device.wait(Until.gone(By.pkg("com.google.android.inputmethod.latin")),3000)
        }
    }
    private fun fill(label:String,value:String){
        var node=visible(label)
        repeat(4){
            if(node.className=="android.widget.EditText"){node.click();node.text=value;hideKeyboard();return}
            node.findObject(By.clazz("android.widget.EditText"))?.let{it.click();it.text=value;hideKeyboard();return}
            node=node.parent?:throw AssertionError("Missing text input for "+label)
        }
        throw AssertionError("Missing editable field "+label)
    }
    @Test fun installedApkUsesRealMacService(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePilot")=="true")
        val ctx=instrumentation.targetContext
        val phoneHash=java.security.MessageDigest.getInstance("SHA-256")
            .digest("+21623456789".toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        ctx.getSharedPreferences("malak_pilot_land_draft_"+phoneHash,0).edit().clear().commit()
        ctx.getSharedPreferences("malak_public_preferences",0).edit().putString("language","fr").commit()
        val intent=ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        try{
            device.wait(Until.hasObject(By.clazz("android.widget.EditText")),30000)
            device.findObject(By.clazz("android.widget.EditText")).text="23456789"
            tap("Continuer")
            device.findObject(By.clazz("android.widget.EditText")).text="1234"
            tap("Accéder à Malak")
            visible("Déposer un terrain",40000);shot("01-connected-home")
            if(InstrumentationRegistry.getArguments().getString("stage")=="feed"){
                visible("EMULATOR QA - not for sale",40000);shot("08-public-listing");return
            }
            tap("Déposer un terrain");tap("Choisir des fichiers")
            if(!device.wait(Until.hasObject(By.text("malak-synthetic-sale.png")),3000)){
                device.findObject(By.desc("Show roots"))?.click()
                device.wait(Until.findObject(By.text("Downloads")),5000)?.click()
            }
            tap("malak-synthetic-sale.png")
            // Android's picker can retain multi-select mode after a previous run.
            device.wait(Until.findObject(By.text("Select")),1500)?.click()
            shot("02-document-selected")
            tap("Continuer");tap("Photographier et lire la carte")
            val shutter=device.wait(Until.findObject(By.desc("Prendre la photo")),30000)
                ?:throw AssertionError("Camera shutter unavailable")
            val ready=device.wait(Until.hasObject(By.text(java.util.regex.Pattern.compile(
                "Restez immobile, puis photographiez|CIN illisible.*"))),45000)
            shot("03-camera");assertTrue("Camera should reach a stable capture hint",ready)
            device.findObject(By.desc("Prendre la photo")).click()
            visible("Utiliser cette photo",60000)
            device.wait(Until.findObject(By.text("Utiliser cette photo").enabled(true)),60000)
            visible("بن سالم",60000)
            shot("04-real-identity-result")
            tap("Utiliser cette photo");tap("Continuer")
            shot("05-before-comparison")
            tap("Lire et comparer les documents")
            visible("555941",45000);shot("06-real-document-result")
            // Personalize the public title so test data cannot be mistaken for a real listing.
            tap("Modifier")
            fill("Titre de l’annonce","EMULATOR QA - not for sale")
            fill("Région / délégation","Tunis")
            fill("Superficie (m²)","185.25")
            fill("Prix (TND)","50000")
            hideKeyboard()
            tap("Continuer")
            // The form can retain the property's scroll offset across steps.
            repeat(4){device.swipe(device.displayWidth/2,
                (device.displayHeight*.35).toInt(),device.displayWidth/2,
                (device.displayHeight*.78).toInt(),14)}
            fill("Votre nom","SYNTHETIC EMULATOR QA")
            hideKeyboard()
            tap("Envoyer pour examen")
            visible("Envoyé pour examen",45000);shot("07-submitted-to-admin")
        }catch(e:Throwable){shot("failure");throw e}
    }
}
