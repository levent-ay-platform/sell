package tn.malak.app.ui

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal const val PILOT_PHOTO_MAX_BYTES=6L*1024*1024
internal object PilotListingClient {
    suspend fun call(session:PilotOcrSession?,path:String,data:JSONObject?=null,privateReadConsent:Boolean=false):JSONObject=
        withContext(Dispatchers.IO) {
            val base=session?.httpsBaseUrl?:PilotOcrLogin.configuredBase()?:throw OwnerPairException(503)
            val c=URL(base+path).openConnection() as HttpsURLConnection
            try{
                c.requestMethod=if(data==null)"GET" else "POST"
                c.connectTimeout=10000;c.readTimeout=30000;c.instanceFollowRedirects=false;c.useCaches=false
                c.setRequestProperty("Cache-Control","no-store")
                if(privateReadConsent)c.setRequestProperty("X-Pilot-Ocr-Consent","1")
                session?.let{c.setRequestProperty("Authorization","Bearer "+it.bearer)}
                if(data!=null){
                    val raw=data.toString().toByteArray(Charsets.UTF_8)
                    try{c.doOutput=true;c.setRequestProperty("Content-Type","application/json")
                        c.setFixedLengthStreamingMode(raw.size);c.outputStream.use{it.write(raw)}}finally{raw.fill(0)}
                }
                if(c.responseCode!=200)throw OwnerPairException(c.responseCode)
                val raw=c.inputStream.use{readPilotBytes(it,1024*1024)}
                try{
                    if(raw.size>1024*1024)throw OwnerPairException(502)
                    val result=JSONObject(String(raw,Charsets.UTF_8))
                    if(!result.optBoolean("ok"))throw OwnerPairException(502)
                    result
                }finally{raw.fill(0)}
            }finally{c.disconnect()}
        }
    suspend fun photo(context:Context,session:PilotOcrSession,reviewId:String,uri:Uri,length:Long)=
        withContext(Dispatchers.IO){
            if(length !in 20..PILOT_PHOTO_MAX_BYTES)throw OwnerPairException(413)
            val c=URL(session.httpsBaseUrl+"/api/pilot/reviews/"+reviewId+"/photos").openConnection() as HttpsURLConnection
            try{
                c.requestMethod="POST";c.connectTimeout=10000;c.readTimeout=30000
                c.instanceFollowRedirects=false;c.useCaches=false;c.doOutput=true
                c.setRequestProperty("Authorization","Bearer "+session.bearer)
                c.setRequestProperty("Content-Type","application/octet-stream")
                c.setFixedLengthStreamingMode(length.toInt())
                c.outputStream.use{output->context.contentResolver.openInputStream(uri)?.use{input->
                    val buffer=ByteArray(32768);var sent=0L
                    try{while(true){val n=input.read(buffer);if(n<0)break;sent+=n
                        if(sent>length)throw OwnerPairException(422);output.write(buffer,0,n)}
                        if(sent!=length)throw OwnerPairException(422)
                    }finally{buffer.fill(0)}
                }?:throw OwnerPairException(422)}
                if(c.responseCode!=200)throw OwnerPairException(c.responseCode)
                c.inputStream.close()
            }finally{c.disconnect()}
        }
}

internal fun readPilotBytes(input:java.io.InputStream,limit:Int):ByteArray {
    val output=java.io.ByteArrayOutputStream()
    val buffer=ByteArray(8192)
    try{while(true){val n=input.read(buffer);if(n<0)break
        if(output.size()+n>limit)throw OwnerPairException(502)
        output.write(buffer,0,n)}
        return output.toByteArray()
    }finally{buffer.fill(0);output.reset()}
}
