package tn.malak.app.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.HandlerThread
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import java.util.UUID

/** Camera evidence is in process memory only: no MediaStore, disk caches,
 * third party sharing or persistence across process restart.
 * This provider is declared non-exported and readable only by our app UID.
 */
internal object PrivateCaptureMemory {
    private const val AUTHORITY = "tn.malak.app.privatecapture"
    private const val MAX_BYTES = 12 * 1024 * 1024
    private val held = mutableMapOf<String, ByteArray>()
    private val media = mutableMapOf<String, String>()

    fun addJpeg(encoded:ByteArray):Uri = addDocument(encoded,"image/jpeg")
    fun addDocument(encoded:ByteArray,mime:String):Uri {
        require(mime in setOf("image/jpeg","image/png","image/tiff","application/pdf"))
        require(encoded.size in 20..MAX_BYTES)
        synchronized(held) {
            if(held.size>=12 || held.values.sumOf { it.size.toLong() } + encoded.size > 32L*1024*1024)
                throw IllegalStateException("CAPTURE_CAPACITY")
            val id=UUID.randomUUID().toString()
            held[id]=encoded
            media[id]=mime
            return Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(id).build()
        }
    }
    fun length(uri:Uri):Long?=synchronized(held) {
        if(uri.authority!=AUTHORITY)null else held[uri.lastPathSegment]?.size?.toLong()
    }
    fun get(uri:Uri):ByteArray?=synchronized(held) {
        if(uri.authority!=AUTHORITY)return@synchronized null
        held[uri.lastPathSegment]
    }
    fun readAt(uri:Uri,offset:Long,size:Int,target:ByteArray):Int=synchronized(held){
        val bytes=if(uri.authority==AUTHORITY)held[uri.lastPathSegment] else null
        if(bytes==null)throw ErrnoException("read",OsConstants.EBADF)
        if(offset<0||size<0||size>target.size)throw ErrnoException("read",OsConstants.EINVAL)
        if(offset>=bytes.size)return@synchronized 0
        val start=offset.toInt()
        val count=minOf(size,bytes.size-start)
        bytes.copyInto(target,0,start,start+count)
        count
    }
    fun mime(uri:Uri):String?=synchronized(held){if(uri.authority==AUTHORITY)media[uri.lastPathSegment] else null}
    fun discard(uri:Uri){
        if(uri.authority!=AUTHORITY)return
        synchronized(held){held.remove(uri.lastPathSegment)?.fill(0);media.remove(uri.lastPathSegment)}
    }
    fun isPrivate(uri:Uri):Boolean=uri.authority==AUTHORITY
}

class PrivateCaptureProvider:ContentProvider() {
    private val proxyHandler by lazy { Handler(HandlerThread("amlak-private-file").apply{start()}.looper) }
    override fun onCreate():Boolean=true
    private fun data(uri:Uri)=PrivateCaptureMemory.get(uri)
        ?:throw FileNotFoundException("Capture no longer available")
    override fun getType(uri:Uri):String?=
        PrivateCaptureMemory.mime(uri)
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,
        selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val bytes=data(uri)
        val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).also { cursor->
            cursor.addRow(columns.map {
                when(it){
                    OpenableColumns.DISPLAY_NAME->when(PrivateCaptureMemory.mime(uri)){
                        "application/pdf"->"private-plan.pdf"
                        "image/png"->"private-document.png"
                        "image/tiff"->"private-document.tiff"
                        else->"private-document.jpg"
                    }
                    OpenableColumns.SIZE->bytes.size
                    else->null
                }
            })
        }
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        if(mode!="r")throw FileNotFoundException("Read-only private capture")
        val bytes=data(uri)
        if(PrivateCaptureMemory.mime(uri)=="application/pdf"){
            // PdfRenderer needs seekable reads. No plaintext temporary file.
            val storage=checkNotNull(context).getSystemService(StorageManager::class.java)
                ?:throw FileNotFoundException("Private PDF unavailable")
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY,
                object:ProxyFileDescriptorCallback(){
                    override fun onGetSize():Long=PrivateCaptureMemory.length(uri)
                        ?:throw ErrnoException("size",OsConstants.EBADF)
                    override fun onRead(offset:Long,size:Int,data:ByteArray):Int=
                        PrivateCaptureMemory.readAt(uri,offset,size,data)
                    override fun onRelease(){}
                },proxyHandler)
        }
        val pipe=ParcelFileDescriptor.createPipe()
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use{ output->
                    output.write(bytes)
                }
            }catch (_:Exception){runCatching{pipe[1].close()}}
        },"malak-private-capture-read").start()
        return pipe[0]
    }
    override fun insert(uri:Uri,values:ContentValues?):Uri?=
        throw UnsupportedOperationException("Read only")
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int=0
    override fun update(uri:Uri,values:ContentValues?,selection:String?,
        selectionArgs:Array<out String>?):Int=0
}
