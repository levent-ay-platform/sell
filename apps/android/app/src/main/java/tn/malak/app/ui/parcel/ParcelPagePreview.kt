package tn.malak.app.ui.parcel

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

internal data class ParcelPagePreview(val bitmap: Bitmap, val frame: ParcelFrame, val unit: String)

private const val MAX_PREVIEW_SIDE = 1600
private const val MAX_SOURCE_SIDE = 100000

internal suspend fun decodeParcelPage(
    resolver: ContentResolver, uri: Uri, mime: String, page: Int
): ParcelPagePreview = withContext(Dispatchers.IO) {
    require(uri.scheme == "content" && page in 1..8)
    if (mime == "application/pdf") {
        val descriptor = checkNotNull(resolver.openFileDescriptor(uri, "r"))
        val renderer = try {
            PdfRenderer(descriptor)
        } catch (error: Exception) {
            descriptor.close()
            throw error
        }
        renderer.use { pdf ->
            check(page <= pdf.pageCount)
            pdf.openPage(page - 1).use { pdfPage ->
                val frame = ParcelFrame(pdfPage.width, pdfPage.height)
                val scale = min(3.0, MAX_PREVIEW_SIDE.toDouble() / max(frame.width, frame.height))
                val bitmap = Bitmap.createBitmap(
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888
                )
                bitmap.eraseColor(android.graphics.Color.WHITE)
                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ParcelPagePreview(bitmap, frame, "PAGE_POINT")
            }
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        check(bounds.outWidth in 1..MAX_SOURCE_SIDE && bounds.outHeight in 1..MAX_SOURCE_SIDE)
        val exif = if (mime == "image/jpeg") resolver.openInputStream(uri)?.use {
            runCatching { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }.getOrNull()
        } ?: 1 else 1
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (max(bounds.outWidth, bounds.outHeight) / inSampleSize > MAX_PREVIEW_SIDE) {
                inSampleSize *= 2
            }
        }
        val original = checkNotNull(resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        })
        val transform = Matrix().apply {
            when (exif) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        val rotated = exif in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270
        val frame = if (rotated) ParcelFrame(bounds.outHeight, bounds.outWidth)
            else ParcelFrame(bounds.outWidth, bounds.outHeight)
        val bitmap = if (transform.isIdentity) original else {
            Bitmap.createBitmap(original, 0, 0, original.width, original.height, transform, true).also {
                if (it !== original) original.recycle()
            }
        }
        ParcelPagePreview(bitmap, frame, "PIXEL")
    }
}
