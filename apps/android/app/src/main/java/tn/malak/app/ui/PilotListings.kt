package tn.malak.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import tn.malak.app.R

@Composable
internal fun PilotListings(query:String,category:String,onReplaceDocuments:(JSONObject)->Unit,
    onlyOwn:Boolean=false,focusId:String?=null,onOpenRequests:()->Unit={}){
    val session=LocalPilotOcrSession.current
    val scope=rememberCoroutineScope()
    var refresh by remember{mutableIntStateOf(0)}
    var public by remember{mutableStateOf<List<JSONObject>>(emptyList())}
    var own by remember(session){mutableStateOf<List<JSONObject>>(emptyList())}
    var failed by remember{mutableStateOf(false)}
    var loading by remember{mutableStateOf(false)}
    LaunchedEffect(refresh,session){
        loading=true;failed=false
        try{
            if(!onlyOwn){
                val result=PilotListingClient.call(null,"/api/public/listings").getJSONArray("listings")
                public=(0 until result.length()).map{result.getJSONObject(it)}
            }
            if(session!=null){
                val mine=PilotListingClient.call(session,"/api/pilot/submissions").getJSONArray("submissions")
                own=(0 until mine.length()).map{mine.getJSONObject(it)}
            }
        }catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
        catch(_:Exception){failed=true}
        finally{loading=false}
    }
    if(!onlyOwn){
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
        Text(s(R.string.public_listings),fontSize=19.sp)
        TextButton(onClick={refresh++},enabled=!loading){Text(s(R.string.listing_refresh))}
    }
    if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(failed)Text(s(R.string.listing_load_failed),color=MaterialTheme.colorScheme.error)
    val cards=public.filter{(category=="all"||category=="terrains")&&
        (query.isBlank()||(it.optString("title")+" "+it.optString("region")).contains(query,true))}
    if(!loading&&!failed&&cards.isEmpty())Text(s(R.string.listings_empty_hint),color=Brand.Muted)
    cards.forEach{card->
        Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(18.dp),border=BorderStroke(1.dp,Brand.Muted.copy(alpha=.2f))){
            Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                val photo=card.optJSONArray("photos")?.optString(0)?.takeIf{it.matches(Regex("[0-9a-f-]{36}"))}
                if(photo!=null)PublicPropertyPhoto(photo)
                Text(card.optString("title"),fontSize=18.sp)
                Text(card.optString("region"),color=Brand.Muted)
                Text(card.optString("area")+" m² · "+card.optString("price")+" TND")
                if(card.optString("description").isNotBlank())Text(card.optString("description"),fontSize=13.sp)
            }
        }
    }
    }
    if(session!=null){
        Row(Modifier.fillMaxWidth().padding(top=14.dp),horizontalArrangement=Arrangement.SpaceBetween){
            Text(s(R.string.my_submissions),fontSize=19.sp)
            if(!onlyOwn)TextButton(onClick=onOpenRequests){Text(s(R.string.owner_all_requests))}
            else TextButton(onClick={refresh++},enabled=!loading){Text(s(R.string.listing_refresh))}
        }
        if(onlyOwn&&loading)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(onlyOwn&&failed)Text(s(R.string.listing_load_failed),color=MaterialTheme.colorScheme.error)
        own.sortedByDescending{it.optString("id")==focusId}.forEach{item->
            var editing by remember(item.optString("id"),item.optInt("version")){mutableStateOf(false)}
            var reading by remember(item.optString("id")){mutableStateOf(false)}
            Surface(color=MaterialTheme.colorScheme.surface,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,Brand.Muted.copy(alpha=.2f))){
                Column(Modifier.fillMaxWidth().padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    if(item.optString("id")==focusId)Text(s(R.string.owner_latest_request),color=Brand.Crimson)
                    Text(item.optString("title"))
                    Text(s(when(item.optString("status")){
                        "PUBLISHED"->R.string.status_published
                        "CHANGES_REQUESTED"->R.string.status_changes
                        "REJECTED"->R.string.status_rejected
                        "PAUSED"->R.string.status_paused
                        else->R.string.status_pending
                    }),color=Brand.Crimson)
                    if(item.optString("note").isNotBlank())Text(item.optString("note"),fontSize=13.sp)
                    TextButton(onClick={reading=!reading}){Text(s(R.string.owner_read_request_documents))}
                    if(reading)OwnerStoredDocumentReadout(session,item.optString("reviewId"),
                        item.optString("status") in setOf("PENDING_REVIEW","CHANGES_REQUESTED"))
                    if(item.optString("status")=="CHANGES_REQUESTED"){
                        TextButton(onClick={editing=!editing}){Text(s(R.string.edit_property))}
                        TextButton(onClick={onReplaceDocuments(item)}){Text(s(R.string.submission_replace_documents))}
                        if(editing)ResubmitForm(item,session){refresh++}
                    }
                }
            }
        }
    }
}

@Composable
private fun ResubmitForm(item:JSONObject,session:PilotOcrSession,onSent:()->Unit){
    val scope=rememberCoroutineScope()
    var title by remember{mutableStateOf(item.optString("title"))}
    var region by remember{mutableStateOf(item.optString("region"))}
    var area by remember{mutableStateOf(item.optString("area"))}
    var price by remember{mutableStateOf(item.optString("price"))}
    var description by remember{mutableStateOf(item.optString("description"))}
    var busy by remember{mutableStateOf(false)}
    var failed by remember{mutableStateOf(false)}
    val key=remember{UUID.randomUUID().toString()}
    OutlinedTextField(title,{title=it.take(100)},label={Text(s(R.string.summary_property))},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(region,{region=it.take(100)},label={Text(s(R.string.summary_region))},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(area,{area=it.take(16)},label={Text(s(R.string.summary_area))},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(price,{price=it.take(15)},label={Text(s(R.string.summary_price))},modifier=Modifier.fillMaxWidth())
    OutlinedTextField(description,{description=it.take(5000)},modifier=Modifier.fillMaxWidth())
    if(failed)Text(s(R.string.submission_failed),color=MaterialTheme.colorScheme.error)
    Button(onClick={
        busy=true;failed=false
        val data=JSONObject(item.toString()).put("requestKey",key).put("title",title)
            .put("region",region).put("area",area).put("price",price).put("description",description)
        scope.launch{try{PilotListingClient.call(session,"/api/pilot/submissions",data);onSent()}
            catch(cancel:kotlinx.coroutines.CancellationException){throw cancel}
            catch(_:Exception){failed=true}finally{busy=false}}
    },enabled=!busy){Text(s(R.string.submission_resend))}
}

@Composable
private fun PublicPropertyPhoto(id:String){
    var bitmap by remember(id){mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(id){
        bitmap=withContext(Dispatchers.IO){runCatching{
            val base=PilotOcrLogin.configuredBase()?:return@runCatching null
            val c=URL(base+"/api/public/photos/"+id).openConnection() as javax.net.ssl.HttpsURLConnection
            try{c.connectTimeout=10000;c.readTimeout=15000;c.instanceFollowRedirects=false
                if(c.responseCode!=200)return@runCatching null
                val raw=c.inputStream.use{readPilotBytes(it,6*1024*1024)}
                if(raw.size>6*1024*1024)return@runCatching null
                android.graphics.BitmapFactory.decodeByteArray(raw,0,raw.size)
            }finally{c.disconnect()}
        }.getOrNull()}
    }
    bitmap?.let{Image(it.asImageBitmap(),null,Modifier.fillMaxWidth().height(200.dp),contentScale=ContentScale.Crop)}
}
