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
    internal data class Attachment(val id:String,val kind:String,val mime:String)
    private val attachmentId=Regex("^[0-9a-f-]{36}$")
    private val kinds=setOf("CADASTRAL_PLAN","OTHER_LEGAL_PROOF")
    private val mimes=setOf("application/pdf","image/png","image/jpeg","image/tiff")
    private fun attachment(item:JSONObject):Attachment{
        val value=Attachment(item.getString("id"),item.getString("kind"),item.getString("mime"))
        if(!attachmentId.matches(value.id)||value.kind !in kinds||value.mime !in mimes)throw OwnerPairException(502)
        return value
    }
    private fun attachmentPath(review:String):String{
        require(attachmentId.matches(review))
        return "/api/pilot/reviews/$review/attachments"
    }
    suspend fun attachments(session:PilotOcrSession,review:String):List<Attachment>{
        val rows=call(session,attachmentPath(review)).getJSONArray("attachments")
        if(rows.length()>2)throw OwnerPairException(502)
        return List(rows.length()){attachment(rows.getJSONObject(it))}.also{
            if(it.map{row->row.kind}.distinct().size!=it.size)throw OwnerPairException(502)
        }
    }
    suspend fun removeAttachment(session:PilotOcrSession,review:String,item:Attachment){
        call(session,attachmentPath(review)+"/"+item.id,JSONObject(),privateReadConsent=true)
    }
    suspend fun downloadAttachment(session:PilotOcrSession,review:String,item:Attachment):ByteArray=withContext(Dispatchers.IO){
        val c=URL(session.httpsBaseUrl+attachmentPath(review)+"/"+item.id).openConnection() as HttpsURLConnection
        try{
            c.connectTimeout=10000;c.readTimeout=30000;c.instanceFollowRedirects=false;c.useCaches=false
            c.setRequestProperty("Authorization","Bearer "+session.bearer)
            c.setRequestProperty("Cache-Control","no-store")
            if(c.responseCode!=200)throw OwnerPairException(c.responseCode)
            if(c.contentType?.substringBefore(';')!="application/octet-stream")throw OwnerPairException(502)
            c.inputStream.use{readPilotBytes(it,PILOT_PHOTO_MAX_BYTES.toInt())}.also{
                if(it.size<20){it.fill(0);throw OwnerPairException(502)}
            }
        }finally{c.disconnect()}
    }
    suspend fun uploadAttachment(context:Context,session:PilotOcrSession,review:String,kind:String,
        uri:Uri,length:Long,mime:String,replace:Attachment?):Attachment=withContext(Dispatchers.IO){
        if(kind !in kinds||mime !in mimes||length !in 20..PILOT_PHOTO_MAX_BYTES)throw OwnerPairException(422)
        val c=URL(session.httpsBaseUrl+attachmentPath(review)).openConnection() as HttpsURLConnection
        try{
            c.requestMethod="POST";c.connectTimeout=10000;c.readTimeout=30000;c.instanceFollowRedirects=false;c.useCaches=false;c.doOutput=true
            c.setRequestProperty("Authorization","Bearer "+session.bearer)
            c.setRequestProperty("Content-Type","application/octet-stream")
            c.setRequestProperty("X-Pilot-Ocr-Consent","1")
            c.setRequestProperty("X-Attachment-Kind",kind)
            c.setRequestProperty("X-Claimed-Mime",mime)
            replace?.let{require(it.kind==kind);c.setRequestProperty("X-Replace-Attachment",it.id)}
            c.setFixedLengthStreamingMode(length.toInt())
            c.outputStream.use{out->context.contentResolver.openInputStream(uri)?.use{input->
                val buffer=ByteArray(32768);var sent=0L
                try{while(true){val n=input.read(buffer);if(n<0)break;sent+=n
                    if(sent>length)throw OwnerPairException(422);out.write(buffer,0,n)}
                    if(sent!=length)throw OwnerPairException(422)
                }finally{buffer.fill(0)}
            }?:throw OwnerPairException(422)}
            if(c.responseCode!=200)throw OwnerPairException(c.responseCode)
            val response=c.inputStream.use{readPilotBytes(it,16384)}
            try{
                val envelope=JSONObject(String(response,Charsets.UTF_8))
                if(!envelope.optBoolean("ok")||envelope.optBoolean("mayPublish",true))throw OwnerPairException(502)
                attachment(envelope.getJSONObject("attachment")).also{if(it.kind!=kind||it.mime!=mime)throw OwnerPairException(502)}
            }finally{response.fill(0)}
        }finally{c.disconnect()}
    }
    internal data class Boundary(val id:String,val points:List<tn.malak.app.ui.explore.DiscoveryPoint>)
    private fun decodeBoundary(envelope:JSONObject):Boundary?{
        val value=envelope.optJSONObject("boundary")?:return null
        val id=value.getString("id")
        if(!attachmentId.matches(id)||value.optBoolean("official",true)||value.optString("method")!="OWNER_TRACED_ESTIMATE")throw OwnerPairException(502)
        val ring=value.getJSONArray("vertices")
        if(ring.length() !in 3..64)throw OwnerPairException(502)
        val points=List(ring.length()){i->val pair=ring.getJSONArray(i)
            if(pair.length()!=2)throw OwnerPairException(502)
            tn.malak.app.ui.explore.DiscoveryPoint(pair.getDouble(1),pair.getDouble(0))}
        if(tn.malak.app.ui.parcel.measureOwnerMap(points)==null)throw OwnerPairException(502)
        return Boundary(id,points)
    }
    private fun boundaryPath(review:String)=attachmentPath(review).removeSuffix("/attachments")+"/boundary"
    suspend fun boundary(session:PilotOcrSession,review:String):Boundary?=
        decodeBoundary(call(session,boundaryPath(review)))
    suspend fun saveBoundary(session:PilotOcrSession,review:String,points:List<tn.malak.app.ui.explore.DiscoveryPoint>,replace:Boundary?):Boundary?{
        if(points.isNotEmpty()&&tn.malak.app.ui.parcel.measureOwnerMap(points)==null)throw OwnerPairException(422)
        val payload=JSONObject().put("replaceId",replace?.id?:JSONObject.NULL)
            .put("vertices",org.json.JSONArray(points.map{org.json.JSONArray(listOf(it.longitude,it.latitude))}))
        val response=call(session,boundaryPath(review),payload,privateReadConsent=true)
        if(response.optBoolean("mayPublish",true))throw OwnerPairException(502)
        return decodeBoundary(response).also{if(it?.points.orEmpty()!=points)throw OwnerPairException(502)}
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
                val response=c.inputStream.use{readPilotBytes(it,16384)}
                JSONObject(String(response,Charsets.UTF_8)).getString("photoId")
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
