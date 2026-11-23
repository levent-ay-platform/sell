package tn.malak.app.ui.explore

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tn.malak.app.ui.AppCue
import tn.malak.app.ui.LocalFeedback
import tn.malak.app.R
import tn.malak.app.ui.Brand
import tn.malak.app.ui.s
import java.io.File
import java.io.IOException

@Composable
internal fun PublicParcelSection(source: DiscoverySource, listingId: String, language: String) {
    var parcel by remember(source, listingId) { mutableStateOf(PublicParcel()) }
    var failure by remember(source, listingId) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var openDocumentId by rememberSaveable(listingId) { mutableStateOf<String?>(null) }
    LaunchedEffect(source, listingId, refresh) {
        failure = false
        try {
            parcel = source.parcel(listingId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            parcel = PublicParcel()
            failure = true
        }
    }
    if (failure) {
        ResultMessage(s(R.string.parcel_fetch_error), retry = { refresh++ })
        return
    }
    if (parcel.showVerifiedBadge) {
        Surface(shape = RoundedCornerShape(12.dp), color = Brand.NeutralContainer, contentColor = Brand.TrustGreen) {
            Text(s(R.string.parcel_verified_badge), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge)
        }
    }
    val proof = parcel.proof
    val documents = parcel.documents + listOfNotNull(proof?.document)
    if (documents.isNotEmpty()) {
        Surface(color = Color.White, contentColor = Brand.Ink, shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Brand.Border)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s(R.string.parcel_documents), Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleMedium)
                documents.forEach { document ->
                    Surface(onClick = { openDocumentId = document.id }, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp), color = Brand.NeutralContainer, contentColor = Brand.Ink) {
                        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 10.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Description, null, tint = Brand.Muted)
                            Text(s(document.label()), modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            Text(s(R.string.parcel_preview), color = Brand.Crimson,
                                style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
    if (parcel.boundaries.isNotEmpty()) GeographicParcel(parcel.boundaries, language)
    if (proof != null) {
        Text(s(R.string.parcel_private_proof), color = Brand.Crimson, style = MaterialTheme.typography.labelMedium)
        Text(s(R.string.parcel_local_only), style = MaterialTheme.typography.titleMedium)
        Text(s(R.string.parcel_plot, proof.plot) + " · " + s(R.string.v2_area, proof.area))
        LocalOutline(proof.vertices)
        Text(proof.source, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        Text(s(R.string.parcel_pixel_measurements) + " · " + proof.vertices.indices.joinToString(" / ") { edge ->
            val start = proof.vertices[edge]
            val end = proof.vertices[(edge + 1) % proof.vertices.size]
            kotlin.math.hypot((end.x - start.x).toDouble(), (end.y - start.y).toDouble()).let {
                discoveryNumber(String.format(java.util.Locale.ROOT, "%.1f", it), language)
            }
        }, style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
        Text(s(R.string.parcel_no_measured_sides), style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
    }
    documents.firstOrNull { it.id == openDocumentId }?.let { document ->
        PropertyDocumentViewer(document, s(document.label()), { openDocumentId = null }, source::documentBytes)
    }
}

@Composable
private fun GeographicParcel(boundaries: List<PublicParcelBoundary>, language: String) {
    val camera = remember(boundaries) { parcelCamera(boundaries.flatMap { it.points }) } ?: return
    var mapFailed by remember(boundaries) { mutableStateOf(false) }
    Text(s(R.string.parcel_boundaries), style = MaterialTheme.typography.titleLarge)
    if (!mapFailed) {
        DiscoveryMap(
            styleUrl = DiscoveryStyleUrl, initial = camera, jump = null, markers = DiscoveryMarkers(emptyList()),
            selectedId = null, priceLabel = { "" },
            modifier = Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(20.dp)),
            boundaries = boundaries,
            onCameraIdle = { _, _ -> }, onListing = {}, onCluster = {},
            onFailure = { mapFailed = true }, onReady = {}
        )
    }
    if (mapFailed) Text(s(R.string.v2_tiles_error), color = Brand.Muted)
    boundaries.forEach { boundary ->
        val label = when (boundary.kind) {
            BoundaryKind.Official -> R.string.parcel_official
            BoundaryKind.Georeferenced -> R.string.parcel_georeferenced
            BoundaryKind.Estimate -> R.string.parcel_estimate
        }
        Text(s(label), color = if (boundary.kind == BoundaryKind.Official) Brand.Crimson else Brand.Ink,
            style = MaterialTheme.typography.titleSmall)
        val tf = boundary.titleReference
        val plot = boundary.plotReference
        val references = when {
            tf != null && plot != null -> s(R.string.parcel_references, tf, plot)
            tf != null -> s(R.string.parcel_title, tf)
            plot != null -> s(R.string.parcel_plot, plot)
            else -> null
        }
        references?.let { Text(it, color = Brand.Muted) }
        boundary.edges.forEach { edge ->
            Text(s(R.string.parcel_edge, edge.label, discoveryNumber(edge.lengthM, language)),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LocalOutline(vertices: List<PlanPoint>) {
    if (vertices.size < 3) return
    Canvas(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(16.dp)).background(Brand.Rose)) {
        val xMin = vertices.minOf { it.x }
        val yMin = vertices.minOf { it.y }
        val xSpan = vertices.maxOf { it.x } - xMin
        val ySpan = vertices.maxOf { it.y } - yMin
        if (xSpan <= 0f || ySpan <= 0f) return@Canvas
        val scale = minOf(size.width * .75f / xSpan, size.height * .75f / ySpan)
        val start = Offset((size.width - xSpan * scale) / 2, (size.height - ySpan * scale) / 2)
        val points = vertices.map { Offset(start.x + (it.x - xMin) * scale, start.y + (it.y - yMin) * scale) }
        val path = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        drawPath(path, Brand.Crimson.copy(alpha = .15f))
        drawPath(path, Brand.Crimson, style = Stroke(width = 2.dp.toPx()))
        points.forEach { drawCircle(Brand.Crimson, 3.dp.toPx(), it) }
    }
}

@Composable
internal fun PropertyDocumentViewer(
    document: PublicPropertyDocument,
    title: String,
    onDismiss: () -> Unit,
    readBytes: (PublicPropertyDocument) -> ByteArray = ::downloadPublicDocument
) {
    val context = LocalContext.current
    val feedback = LocalFeedback.current
    val isPdf = document.mime == "application/pdf"
    var zoom by rememberSaveable(document.id) { mutableFloatStateOf(1f) }
    var page by rememberSaveable(document.id) { mutableIntStateOf(document.startPage) }
    var preview by remember(document.location, page) { mutableStateOf<DocumentPage?>(null) }
    var failed by remember(document.location, page) { mutableStateOf(false) }

    LaunchedEffect(document, page) {
        try {
            preview = withContext(Dispatchers.IO) {
                readDocumentPage(context, document, readBytes(document), page)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Brand.Pearl) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 3)
                    IconButton(onClick = { zoom = (zoom - .5f).coerceAtLeast(1f) }, enabled = zoom > 1f) {
                        Icon(Icons.Outlined.ZoomOut, s(R.string.parcel_zoom_out))
                    }
                    IconButton(onClick = { zoom = (zoom + .5f).coerceAtMost(4f) }, enabled = zoom < 4f) {
                        Icon(Icons.Outlined.ZoomIn, s(R.string.parcel_zoom_in))
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, s(R.string.back)) }
                }
                val shown = preview
                when {
                    failed -> ResultMessage(s(R.string.parcel_preview_error))
                    shown == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    else -> {
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val documentWidth = maxWidth * zoom
                            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                                .horizontalScroll(rememberScrollState())) {
                                Image(shown.bitmap.asImageBitmap(), s(R.string.parcel_pdf_page),
                                    Modifier.width(documentWidth).aspectRatio(
                                        shown.bitmap.width.toFloat() / shown.bitmap.height
                                    ), contentScale = ContentScale.Fit)
                            }
                        }
                        if (isPdf && shown.count > 1) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween) {
                                IconButton(onClick = { feedback(AppCue.Step); page-- }, enabled = page > 0) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, s(R.string.parcel_previous))
                                }
                                Text(s(R.string.parcel_page, page + 1, shown.count))
                                IconButton(onClick = { feedback(AppCue.Step); page++ }, enabled = page + 1 < shown.count) {
                                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, s(R.string.parcel_next))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun PublicPropertyDocument.label(): Int = when (kind) {
    "CADASTRAL_PLAN", "SUBDIVISION_PLAN", "PARCEL_PLAN" -> R.string.parcel_doc_plan
    "TITLE_EXTRACT", "OWNERSHIP_CERTIFICATE", "SHARED_OWNERSHIP_CERTIFICATE" -> R.string.parcel_doc_title
    else -> R.string.parcel_doc_other
}

private data class DocumentPage(val bitmap: Bitmap, val count: Int)

private fun readDocumentPage(
    context: Context, document: PublicPropertyDocument, bytes: ByteArray, index: Int
): DocumentPage {
    if (document.mime != "application/pdf") {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        require(longest in 1..12000)
        var divisor = 1
        while (longest / divisor > 1800) divisor *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = divisor }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("Invalid image")
        return DocumentPage(bitmap, 1)
    }
    val temp = File.createTempFile("approved-property-", ".pdf", context.cacheDir)
    try {
        temp.writeBytes(bytes)
        ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                require(renderer.pageCount in 1..200 && index in 0 until renderer.pageCount)
                renderer.openPage(index).use { page ->
                    val scale = 1700f / maxOf(page.width, page.height)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return DocumentPage(bitmap, renderer.pageCount)
                }
            }
        }
    } finally {
        temp.delete()
    }
}
