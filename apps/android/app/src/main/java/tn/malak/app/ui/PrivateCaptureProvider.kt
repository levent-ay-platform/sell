package tn.malak.app.ui

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
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

    fun addJpeg(encoded:ByteArray):Uri {
        require(encoded.size in 20..MAX_BYTES)
        synchronized(held) {
            if(held.size>=4)throw IllegalStateException("CAPTURE_CAPACITY")
            val id=UUID.randomUUID().toString()
            held[id]=encoded
            return Uri.Builder().scheme("content").authority(AUTHORITY)
                .appendPath(id).build()
        }
    }
    fun length(uri:Uri):Long?=synchronized(held) {
        held[uri.lastPathSegment]?.size?.toLong()
    }
    fun get(uri:Uri):ByteArray?=synchronized(held) {
        if(uri.authority!=AUTHORITY)return@synchronized null
        held[uri.lastPathSegment]
    }
    fun discard(uri:Uri){
        if(uri.authority!=AUTHORITY)return
        synchronized(held){held.remove(uri.lastPathSegment)?.fill(0)}
    }
    fun isPrivate(uri:Uri):Boolean=uri.authority==AUTHORITY
}

class PrivateCaptureProvider:ContentProvider() {
    override fun onCreate():Boolean=true
    private fun data(uri:Uri)=PrivateCaptureMemory.get(uri)
        ?:throw FileNotFoundException("Capture no longer available")
    override fun getType(uri:Uri):String?=
        if(PrivateCaptureMemory.get(uri)!=null) "image/jpeg" else null
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,
        selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val bytes=data(uri)
        val columns=projection?:arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE)
        return MatrixCursor(columns).also { cursor->
            cursor.addRow(columns.map {
                when(it){
                    OpenableColumns.DISPLAY_NAME->"cin-camera.jpg"
                    OpenableColumns.SIZE->bytes.size
                    else->null
                }
            })
        }
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
        if(mode!="r")throw FileNotFoundException("Read-only private capture")
        val bytes=data(uri)
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
