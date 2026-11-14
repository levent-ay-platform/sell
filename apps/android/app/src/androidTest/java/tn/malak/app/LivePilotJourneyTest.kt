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
    private fun tap(text:String){
        repeat(3){
            try{visible(text).click();device.waitForIdle();return}
            catch(_:androidx.test.uiautomator.StaleObjectException){device.waitForIdle(1000)}
        }
        throw AssertionError("Control kept changing: "+text)
    }
    private fun hideKeyboard(){
        instrumentation.runOnMainSync {
            val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()
            activity?.let {
                it.currentFocus?.clearFocus()
                (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(it.window.decorView.windowToken,0)
            }
        }
        repeat(10){
            var showing=false
            instrumentation.runOnMainSync {
                val activity=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull()
                if(android.os.Build.VERSION.SDK_INT>=30)
                    showing=activity?.window?.decorView?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime())==true
            }
            if(!showing)return
            android.os.SystemClock.sleep(150)
        }
        throw AssertionError("Keyboard did not close; stopping before gestures")
    }
    private fun fill(label:String,value:String){
        repeat(3){
            try{
                var node=visible(label)
                repeat(4){
                    if(node.className=="android.widget.EditText"){node.click();node.text=value;hideKeyboard();return}
                    node.findObject(By.clazz("android.widget.EditText"))?.let{it.click();it.text=value;hideKeyboard();return}
                    node=node.parent?:throw AssertionError("Missing text input for "+label)
                }
            }catch(_:androidx.test.uiautomator.StaleObjectException){device.waitForIdle(1000)}
        }
        throw AssertionError("Missing editable field "+label)
    }
    @Test fun installedApkUsesRealMacService(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePilot")=="true")
        val stage=InstrumentationRegistry.getArguments().getString("stage")
        val ctx=instrumentation.targetContext
        val phoneHash=java.security.MessageDigest.getInstance("SHA-256")
            .digest("+21623456789".toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
        if(stage!="resume")ctx.getSharedPreferences("malak_pilot_land_draft_"+phoneHash,0).edit().clear()
            .putString("description","SYNTHETIC DRAFT DESCRIPTION")
            .putString("latitude","36.8").putString("longitude","10.18").commit()
        ctx.getSharedPreferences("malak_public_preferences",0).edit().putString("language","fr").commit()
        val intent=ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ctx.startActivity(intent)
        try{
            device.wait(Until.hasObject(By.clazz("android.widget.EditText")),30000)
            fill("Numéro tunisien","23456789")
            tap("Continuer")
            fill("Code d’accès","1234")
            tap("Accéder à Malak")
            visible("Déposer un terrain",40000);shot("01-connected-home")
            if(InstrumentationRegistry.getArguments().getString("stage")=="feed"){
                visible("EMULATOR QA - not for sale",40000);shot("08-public-listing");return
            }
            tap("Déposer un terrain")
            if(stage!="resume"){
            tap("Choisir des fichiers")
            if(!device.wait(Until.hasObject(By.text("malak-synthetic-sale.png")),3000)){
                device.findObject(By.desc("Show roots"))?.click()
                device.waitForIdle(1000)
                tap("Downloads")
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
            visible("Confirmer et analyser",60000)
            device.wait(Until.findObject(By.text("Confirmer et analyser").enabled(true)),60000)
            visible("بن سالم",60000)
            shot("04-real-identity-result")
            tap("Confirmer et analyser")
            shot("05-before-comparison")
            }
            visible("555941",45000);shot("06-real-document-result")
            if(stage=="analysis-only"){
                assertNotNull(ctx.getSharedPreferences("malak_pilot_land_draft_"+phoneHash,0).getString("review_id",null))
                shot("06-analysis-saved");return
            }

            val draft=ctx.getSharedPreferences("malak_pilot_land_draft_"+phoneHash,0)
            val alreadyEntered=draft.getString("title","")=="EMULATOR QA - not for sale"&&
                draft.getString("region","")=="Tunis"&&draft.getString("area","")=="185.25"&&
                draft.getString("price","")=="50000"
            if(!alreadyEntered){
            if(ctx.getSharedPreferences("malak_pilot_land_draft_"+phoneHash,0).getString("price","").isNullOrBlank()){
            tap("Continuer")
            assertNotNull(device.wait(Until.findObject(By.textStartsWith("Complétez ces champs :")),5000))
            }
            // Personalize the public title so test data cannot be mistaken for a real listing.
            visible("Votre titre est créé automatiquement")
            if(device.wait(Until.hasObject(By.text("Modifier")),1000))tap("Modifier")
            fill("Titre de l’annonce","EMULATOR QA - not for sale")
            fill("Région / délégation","Tunis")
            fill("Superficie (m²)","185.25")
            fill("Prix (TND)","50000")
            tap("Modifier le nom du demandeur")
            fill("Votre nom","SYNTHETIC EMULATOR QA")
            tap("Photos, position et description")
            visible("SYNTHETIC DRAFT DESCRIPTION")
            }
            hideKeyboard()
            tap("Continuer")
            visible("SYNTHETIC DRAFT DESCRIPTION")
            tap("Envoyer pour examen")
            visible("Envoyé pour examen",45000);shot("07-submitted-to-admin")
            visible("Mes demandes")
            assertFalse(ctx.getSharedPreferences("malak_pilot_land_draft_"+phoneHash,0).contains("title"))
            tap("Mes demandes")
            visible("Votre demande envoyée",30000)
            visible("EMULATOR QA - not for sale")
            shot("08-my-requests")
        }catch(e:Throwable){shot("failure");throw e}
    }
}
