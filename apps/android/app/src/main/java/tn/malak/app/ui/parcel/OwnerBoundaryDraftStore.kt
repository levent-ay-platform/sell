package tn.malak.app.ui.parcel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.MessageDigest
import org.json.JSONArray
import tn.malak.app.ui.explore.DiscoveryPoint

/** Explicitly saved unpublished polygon only; no documents, OCR, tokens, or names.
 * No Android backup; key stays in Android Keystore. Scope is account/draft-bound.
 * Missing/invalid key or malformed ciphertext never yields a geometry. */
internal class OwnerBoundaryDraftStore(context:Context,scope:String) {
    private val digest=MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
        .joinToString(""){"%02x".format(it.toInt() and 255)}
    private val alias="amlak-boundary-v1-$digest"
    private val file=AtomicFile(File(context.noBackupFilesDir,"$alias.bin"))
    private val aad=alias.toByteArray(Charsets.UTF_8)
    private fun key(create:Boolean):SecretKey?{
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (keys.getKey(alias,null) as? SecretKey)?.let{return it}
        if(!create)return null
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generator.generateKey()
    }
    @Synchronized fun save(points:List<DiscoveryPoint>){
        require(measureOwnerMap(points)!=null)
        val bytes=JSONArray(points.map{JSONArray(listOf(it.latitude,it.longitude))}).toString().toByteArray(Charsets.UTF_8)
        try{
            val cipher=Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE,key(true)?:error("KEY_UNAVAILABLE"))
            cipher.updateAAD(aad)
            require(cipher.iv.size==12)
            val encrypted=cipher.doFinal(bytes)
            val stream=file.startWrite()
            try{stream.write(byteArrayOf(1));stream.write(cipher.iv);stream.write(encrypted);file.finishWrite(stream)}
            catch(e:Exception){file.failWrite(stream);throw e}
        }finally{bytes.fill(0)}
    }
    @Synchronized fun read():List<DiscoveryPoint>{
        if(!file.baseFile.exists())return emptyList()
        require(file.baseFile.length() in 30..8192)
        val data=file.readFully()
        require(data.size in 30..8192&&data[0]==1.toByte())
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(false)?:error("KEY_UNAVAILABLE"),GCMParameterSpec(128,data.copyOfRange(1,13)))
        cipher.updateAAD(aad)
        val clear=cipher.doFinal(data,13,data.size-13)
        try{
            val raw=JSONArray(clear.toString(Charsets.UTF_8))
            require(raw.length() in 3..64)
            val points=List(raw.length()){i->val v=raw.getJSONArray(i);require(v.length()==2)
                DiscoveryPoint(v.getDouble(0),v.getDouble(1))}
            require(measureOwnerMap(points)!=null)
            return points
        }finally{clear.fill(0)}
    }
    @Synchronized fun clear(){
        file.delete()
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        if(keys.containsAlias(alias))keys.deleteEntry(alias)
    }
}
