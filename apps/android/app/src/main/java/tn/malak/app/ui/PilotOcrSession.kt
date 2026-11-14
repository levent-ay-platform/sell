package tn.malak.app.ui

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import tn.malak.app.BuildConfig

/** In-memory server-authenticated QA bearer; never a legal identity claim. */
internal data class PilotOcrSession(
    val httpsBaseUrl:String,val bearer:String,val expiresAtMs:Long
)
internal val LocalPilotOcrSession=
    staticCompositionLocalOf<PilotOcrSession?>{null}

internal class PilotOcrLoginException(val status:Int):
    Exception("PILOT_OCR_SERVER_LOGIN_FAILED")

internal object PilotOcrLogin {
    private val phone=Regex("^[2-9][0-9]{7}$")
    private val token=Regex("^[A-Za-z0-9_-]{43}$")
    fun configuredBase():String?{
        val value=BuildConfig.MALAK_PRIVATE_SERVICE_URL.trim()
        val uri=runCatching{URI(value)}.getOrNull()?:return null
        if(uri.scheme!="https"||uri.host.isNullOrBlank()||uri.userInfo!=null||
           uri.query!=null||uri.fragment!=null||
           (uri.path!=""&&uri.path!="/")||uri.port !in -1..65535)
            return null
        return uri.toASCIIString().trimEnd('/')
    }
    private fun deviceKey(context:Context):String{
        val prefs=context.getSharedPreferences("malak_qa_device",Context.MODE_PRIVATE)
        val saved=prefs.getString("random_device_key",null)
        if(saved!=null&&token.matches(saved))return saved
        val random=ByteArray(32)
        SecureRandom().nextBytes(random)
        val fresh=try{Base64.encodeToString(random,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)}
        finally{random.fill(0)}
        if(!prefs.edit().putString("random_device_key",fresh).commit())
            throw PilotOcrLoginException(503)
        return fresh
    }
    suspend fun login(context:Context,localPhone:String,code:String):
        PilotOcrSession=withContext(Dispatchers.IO){
        val base=configuredBase()?:throw PilotOcrLoginException(503)
        if(!phone.matches(localPhone)||!Regex("^\\d{4}$").matches(code))
            throw PilotOcrLoginException(400)
        val body=JSONObject().put("phone","+216"+localPhone)
            .put("code",code).put("deviceKey",deviceKey(context))
            .toString().toByteArray(Charsets.UTF_8)
        val conn=(URL(base+"/internal/v1/pilot/login").openConnection()
            as? HttpsURLConnection)?:throw PilotOcrLoginException(503)
        try {
            conn.requestMethod="POST"
            conn.instanceFollowRedirects=false
            conn.connectTimeout=7000
            conn.readTimeout=12000
            conn.useCaches=false
            conn.doOutput=true
            conn.setRequestProperty("Cache-Control","no-store")
            conn.setRequestProperty("Content-Type","application/json")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use{it.write(body)}
            val status=conn.responseCode
            if(status!=200)throw PilotOcrLoginException(status)
            val response=conn.inputStream.use{input->
                val result=ByteArrayOutputStream()
                val buffer=ByteArray(1024)
                try{
                    while(true){
                        val n=input.read(buffer)
                        if(n<0)break
                        if(result.size()+n>2048)throw PilotOcrLoginException(502)
                        result.write(buffer,0,n)
                    }
                    result.toByteArray()
                }finally{buffer.fill(0);result.reset()}
            }
            val data=try{JSONObject(String(response,Charsets.UTF_8))}
                finally{response.fill(0)}
            val issued=data.optString("bearer")
            if(!data.optBoolean("ok")||!data.optBoolean("pilotConfirmed")||
               data.optBoolean("identityVerified",true)||
               data.optBoolean("ownershipVerified",true)||
               data.optBoolean("mayPublish",true)||
               data.optInt("expiresInSeconds")!=43200||
               !token.matches(issued))
                throw PilotOcrLoginException(502)
            // Never store bearer on disk, and never forge a local session.
            PilotOcrSession(base,issued,System.currentTimeMillis()+43200000L)
        }catch(e:PilotOcrLoginException){throw e}
        catch(_:Exception){throw PilotOcrLoginException(503)}
        finally{body.fill(0);conn.disconnect()}
    }
}
