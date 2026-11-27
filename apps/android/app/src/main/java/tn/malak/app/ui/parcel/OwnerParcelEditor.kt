package tn.malak.app.ui.parcel

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tn.malak.app.R
import tn.malak.app.ui.Brand
import tn.malak.app.ui.OwnerEvidenceSession
import tn.malak.app.ui.s
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class OwnerParcelEditorSource(
    val uri: Uri,
    val mime: String,
    val documentPage: Int,
    val evidencePage: Int,
    val evidenceId: String?,
    val scan: ParcelScan?,
    val measurements: List<ParcelOcrLength>,
    val titleReference: String,
    val plotReference: String,
    val reportedAreaM2: String?
)

private data class EditableParcelEdge(val label: String = "", val length: String = "")
private val EdgeLabel = Regex("^[A-Za-z0-9]{1,5}(?:[-–][A-Za-z0-9]{1,5})?$")

@Composable
internal fun OwnerParcelEditor(
    source: OwnerParcelEditorSource,
    session: OwnerEvidenceSession?,
    previewDraft: OwnerPlanDraft?,
    onPreview: (OwnerPlanDraft) -> Unit,
    onFinish: (Boolean) -> Unit
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val evidenceId = source.evidenceId
    var preview by remember(source) { mutableStateOf<ParcelPagePreview?>(null) }
    var loading by remember(source) { mutableStateOf(true) }
    var sourceError by remember(source) { mutableStateOf(false) }
    var loadError by remember(source) { mutableStateOf(false) }
    var saving by remember(source) { mutableStateOf(false) }
    var saveError by remember(source) { mutableStateOf(false) }
    var initialCandidate by remember(source) { mutableStateOf(false) }
    var localCandidates by remember(source) { mutableStateOf(emptyList<List<ParcelPoint>>()) }
    var localCandidateIndex by remember(source) { mutableIntStateOf(0) }
    var localScanned by remember(source) { mutableStateOf(false) }
    var vertices by remember(source) { mutableStateOf(emptyList<ParcelPoint>()) }
    var edges by remember(source) { mutableStateOf(emptyMap<Int, EditableParcelEdge>()) }
    var selected by remember(source) { mutableStateOf<Int?>(null) }
    var referenceEdge by remember(source) { mutableStateOf<Int?>(null) }
    var parcelId by remember(source) { mutableStateOf<String?>(null) }
    var title by remember(source) { mutableStateOf(source.titleReference) }
    var plot by remember(source) { mutableStateOf(source.plotReference) }
    var area by remember(source) { mutableStateOf(source.reportedAreaM2.orEmpty()) }

    LaunchedEffect(source, session) {
        try {
            val page = decodeParcelPage(resolver, source.uri, source.mime, source.documentPage)
            val prior = if (session != null && evidenceId != null) {
                try {
                    loadOwnerParcelReview(session, evidenceId, source.evidencePage, page.unit, page.frame)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    loadError = true
                    null
                }
            } else previewDraft
            if (prior != null) {
                vertices = openParcelRing(prior.vertices)
                edges = prior.edges.associate { it.ordinal - 1 to EditableParcelEdge(it.label.orEmpty(), it.lengthM) }
                title = prior.titleReference
                plot = prior.plotReference
                area = prior.statedAreaM2.orEmpty()
                parcelId = prior.parcelId
            } else {
                vertices = source.scan?.let { scaleCandidate(it, page.frame) }.orEmpty()
                initialCandidate = vertices.isNotEmpty()
            }
            // Compute local hypotheses off the UI thread only when no existing
            // user draft or server candidate is available.
            if(prior==null && vertices.isEmpty()){
                localCandidates=withContext(Dispatchers.Default){
                    val scale=minOf(1.0,640.0/maxOf(page.bitmap.width,page.bitmap.height))
                    val w=(page.bitmap.width*scale).toInt();val h=(page.bitmap.height*scale).toInt()
                    if(w<32||h<32)emptyList() else {
                        val small=android.graphics.Bitmap.createScaledBitmap(page.bitmap,w,h,true)
                        try{
                            val pixels=IntArray(w*h)
                            small.getPixels(pixels,0,w,0,0,w,h)
                            val mask=BooleanArray(pixels.size){i->
                                val p=pixels[i]
                                android.graphics.Color.alpha(p)>100 &&
                                    (android.graphics.Color.red(p)*3+android.graphics.Color.green(p)*6+
                                        android.graphics.Color.blue(p))/10<145
                            }
                            LocalPlanOutline.candidates(w,h,mask,page.frame)
                        }finally{if(small!==page.bitmap)small.recycle()}
                    }
                }
                localScanned=true
            }
            preview = page
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            sourceError = true
        } finally {
            loading = false
        }
    }

    val page = preview
    val ring = remember(page, vertices) { page?.frame?.validatedRing(vertices) }
    val calibration = remember(page, vertices, edges, referenceEdge) {
        val edge = referenceEdge
        if (edge == null) null else page?.frame?.calibrateParcel(vertices, edge, edges[edge]?.length.orEmpty())
    }
    val estimatedLengths = calibration?.lengthsM?.map { s(R.string.parcel_editor_calculated_length, it) }.orEmpty()
    val invalidArea = area.isNotBlank() && parseParcelLength(area) == null
    val invalidMeasurement = edges.values.any {
        (it.length.isNotBlank() && parseParcelLength(it.length) == null) ||
            (it.label.isNotBlank() && (!EdgeLabel.matches(it.label) || it.length.isBlank()))
    }
    val previewOnly = session == null || evidenceId == null
    val active = !saving && !loading
    BackHandler { if (active) onFinish(false) }

    fun removeVertex(index: Int) {
        if (index !in vertices.indices) return
        vertices = vertices.filterIndexed { i, _ -> i != index }
        // The two incident measurements no longer describe any edge. Retain the others.
        val oldCount = vertices.size + 1
        val previous = (index + oldCount - 1) % oldCount
        edges = edges.filterKeys { it != index && it != previous }
            .mapKeys { (ordinal, _) -> if (ordinal > index) ordinal - 1 else ordinal }
        selected = null
        referenceEdge = null
        saveError = false
    }

    Column(Modifier.fillMaxSize().background(Brand.Pearl)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onFinish(false) }, enabled = active) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, s(R.string.back), tint = Brand.Crimson)
            }
            Column {
                Text(s(R.string.parcel_editor_title), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = Brand.Ink)
                Text(s(R.string.parcel_editor_source, source.evidencePage),
                    style = MaterialTheme.typography.bodySmall, color = Brand.Muted)
            }
        }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Brand.Crimson)
        } else if (sourceError || page == null) {
            Text(s(R.string.parcel_editor_preview_error), Modifier.padding(18.dp), color = Brand.Crimson)
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(s(R.string.parcel_editor_local), fontWeight = FontWeight.SemiBold, color = Brand.Crimson)
                    if (previewOnly) Text(s(R.string.parcel_editor_preview_only), color = Brand.Muted,
                        style = MaterialTheme.typography.bodySmall)
                    if (loadError) Text(s(R.string.parcel_editor_load_failed), color = Brand.Muted)
                }
                item {
                    if (initialCandidate) Text(s(R.string.parcel_editor_candidate), color = Brand.Muted,
                        style = MaterialTheme.typography.bodySmall)
                    Text(s(R.string.parcel_editor_manual), color = Brand.Muted,
                        style = MaterialTheme.typography.bodySmall)
                    if(localCandidates.isNotEmpty()){
                        Text(s(R.string.intake_local_outline_notice),color=Brand.Muted,
                            style=MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick={
                            vertices=localCandidates[localCandidateIndex]
                            localCandidateIndex=(localCandidateIndex+1)%localCandidates.size
                            edges=emptyMap();selected=null;referenceEdge=null;saveError=false
                        },enabled=active&&vertices.isEmpty(),modifier=Modifier.fillMaxWidth()){
                            Text(s(R.string.intake_local_outline_action,localCandidateIndex+1,localCandidates.size))
                        }
                        if(vertices.isNotEmpty()&&priorIsLocalCandidate(vertices,localCandidates)){
                            TextButton(onClick={vertices=emptyList();edges=emptyMap();selected=null;referenceEdge=null}){
                                Text(s(R.string.intake_local_outline_clear))
                            }
                        }
                    } else if(localScanned){
                        Text(s(R.string.intake_local_outline_none),color=Brand.Muted,
                            style=MaterialTheme.typography.bodySmall)
                    }
                }
                item {
                    Surface(color = Color.White, shape = MaterialTheme.shapes.medium) {
                        val description = s(R.string.parcel_editor_canvas)
                        ParcelPageCanvas(
                            page, vertices, edges, estimatedLengths, selected, active,
                            Modifier.fillMaxWidth().height(330.dp)
                                .semantics { contentDescription = description },
                            onAdd = { point ->
                                if (vertices.size < 128) {
                                    // Adding a vertex splits the former closing edge.
                                    edges = edges - vertices.lastIndex
                                    vertices = vertices + point
                                    selected = vertices.lastIndex
                                    referenceEdge = null
                                    saveError = false
                                }
                            },
                            onSelect = { selected = it },
                            onMove = { index, point ->
                                vertices = vertices.mapIndexed { i, old -> if (i == index) point else old }
                                saveError = false
                            }
                        )
                    }
                }
                if (ring != null) item {
                    Surface(color = Color.White, contentColor = Brand.Ink, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(s(R.string.parcel_editor_scale_title), fontWeight = FontWeight.SemiBold)
                            if (calibration == null) {
                                Text(s(R.string.parcel_editor_scale_hint), color = Brand.Muted,
                                    style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text(s(R.string.parcel_editor_calculated_area, calibration.areaM2),
                                    style = MaterialTheme.typography.titleLarge)
                                Text(s(R.string.parcel_editor_calculated_perimeter, calibration.perimeterM))
                                Text(s(R.string.parcel_editor_scale_caution), color = Brand.Muted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(s(R.string.parcel_editor_vertices, vertices.size), color = Brand.Muted)
                        val choice = selected
                        TextButton(onClick = { removeVertex(choice ?: vertices.lastIndex) },
                            enabled = active && vertices.isNotEmpty()) {
                            Text(if (choice != null) s(R.string.parcel_editor_remove, choice + 1)
                                else s(R.string.parcel_editor_undo))
                        }
                    }
                    val choice = selected
                    if (choice != null && choice in vertices.indices) {
                        VertexFields(choice, vertices[choice], page.frame, active) { point ->
                            vertices = vertices.mapIndexed { index, old -> if (index == choice) point else old }
                            saveError = false
                        }
                    }
                    if (vertices.size >= 3 && ring == null)
                        Text(s(R.string.parcel_editor_crossing), color = Brand.Crimson)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(title, { title = it.take(100) }, Modifier.weight(1f),
                            singleLine = true, enabled = active,
                            label = { Text(s(R.string.parcel_editor_title_reference)) })
                        OutlinedTextField(plot, { plot = it.take(100) }, Modifier.weight(1f),
                            singleLine = true, enabled = active,
                            label = { Text(s(R.string.parcel_editor_plot)) })
                    }
                    OutlinedTextField(area, { area = it.take(18) }, Modifier.fillMaxWidth(),
                        singleLine = true, enabled = active, isError = invalidArea,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = { Text(s(R.string.parcel_editor_stated_area)) })
                }
                item {
                    Text(s(R.string.parcel_editor_edges), fontWeight = FontWeight.Bold, color = Brand.Ink)
                    Text(s(R.string.parcel_editor_measure_hint), color = Brand.Muted,
                        style = MaterialTheme.typography.bodySmall)
                    if (source.measurements.isNotEmpty()) {
                        Text(s(R.string.parcel_editor_printed), color = Brand.Ink)
                        val uncertain = s(R.string.smart_read_needs_review)
                        Text(source.measurements.joinToString("  ·  ") {
                            "${it.from}–${it.to}: ${it.metres} m" + if (it.reviewNeeded) " ($uncertain)" else ""
                        }, color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                    if (invalidMeasurement) Text(s(R.string.parcel_editor_invalid_measure), color = Brand.Crimson)
                }
                if (vertices.size >= 3) items(vertices.indices.toList()) { index ->
                    val end = (index + 1) % vertices.size
                    val edge = edges[index] ?: EditableParcelEdge()
                    EdgeFields(
                        index, end, edge, active, source.measurements, estimatedLengths.getOrNull(index),
                        isReference = referenceEdge == index,
                        onReference = { referenceEdge = if (referenceEdge == index) null else index },
                        onChange = { next ->
                            edges = edges + (index to next)
                            saveError = false
                        }
                    )
                }
                if (saveError) item {
                    Text(s(R.string.parcel_editor_failed), color = Brand.Crimson)
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Button(onClick = {
                    val validated = ring ?: return@Button
                    val draft = OwnerPlanDraft(parcelId, validated, title.trim(), plot.trim(),
                        edges.entries.sortedBy { it.key }.mapNotNull { (index, edge) ->
                            val length = parseParcelLength(edge.length) ?: return@mapNotNull null
                            ParcelEditEdge(index + 1, length, edge.label.ifBlank { null })
                        }, parseParcelLength(area))
                    if (previewOnly) {
                        onPreview(draft)
                        onFinish(false)
                    } else if (session != null && evidenceId != null) {
                        saving = true
                        saveError = false
                        scope.launch {
                            try {
                                saveOwnerParcelReview(session, evidenceId, source.evidencePage, page.unit, draft)
                                onPreview(draft)
                                onFinish(true)
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (_: Exception) {
                                saveError = true
                            } finally {
                                saving = false
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(), enabled = active && ring != null && !invalidMeasurement && !invalidArea) {
                    Text(s(when {
                        saving -> R.string.parcel_editor_sending
                        previewOnly -> R.string.parcel_editor_keep_preview
                        else -> R.string.parcel_editor_submit
                    }))
                }
            }
        }
    }
}

@Composable
private fun VertexFields(
    index: Int, coordinate: ParcelPoint, frame: ParcelFrame, active: Boolean,
    onMove: (ParcelPoint) -> Unit
) {
    var x by remember(index, coordinate) { mutableStateOf("%1.2f".format(java.util.Locale.ROOT, coordinate.x)) }
    var y by remember(index, coordinate) { mutableStateOf("%1.2f".format(java.util.Locale.ROOT, coordinate.y)) }
    val newX = parseCoordinate(x)
    val newY = parseCoordinate(y)
    val point = if (newX != null && newY != null) ParcelPoint(newX, newY).takeIf(frame::contains) else null
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(x, { x = it.take(12) }, Modifier.weight(1f), enabled = active,
            singleLine = true, label = { Text(s(R.string.parcel_editor_x)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(y, { y = it.take(12) }, Modifier.weight(1f), enabled = active,
            singleLine = true, label = { Text(s(R.string.parcel_editor_y)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
    }
    if (point != null) TextButton(onClick = { onMove(point) }, enabled = active) {
        Text(s(R.string.parcel_editor_move))
    }
}

private fun parseCoordinate(input: String): Double? = buildString {
    for (character in input.trim()) append(character.digitToIntOrNull()?.toString()
        ?: if (character == ',' || character == '٫') "." else character.toString())
}.toDoubleOrNull()

@Composable
private fun EdgeFields(
    index: Int, end: Int, edge: EditableParcelEdge, active: Boolean,
    readings: List<ParcelOcrLength>, estimate: String?, isReference: Boolean,
    onReference: () -> Unit, onChange: (EditableParcelEdge) -> Unit
) {
    var showReadings by remember(index) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(s(R.string.parcel_editor_edge_name, index + 1, end + 1), color = Brand.Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(edge.label, { onChange(edge.copy(label = it.take(11))) },
                Modifier.weight(1f), enabled = active, singleLine = true,
                label = { Text(s(R.string.parcel_editor_edge_label)) })
            OutlinedTextField(edge.length, { onChange(edge.copy(length = it.take(14))) },
                Modifier.weight(1f), enabled = active, singleLine = true,
                label = { Text(s(R.string.parcel_editor_edge_length)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        if (estimate != null && edge.length.isBlank()) {
            Text(estimate, color = Brand.Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (isReference || parseParcelLength(edge.length) != null) {
            FilterChip(selected = isReference, onClick = onReference, enabled = active, label = {
                Text(s(if (isReference) R.string.parcel_editor_reference_selected else R.string.parcel_editor_use_reference))
            })
        }
        if (readings.isNotEmpty()) Box {
            TextButton(enabled = active, onClick = { showReadings = true }) {
                Text(s(R.string.parcel_editor_pick_reading))
            }
            DropdownMenu(expanded = showReadings, onDismissRequest = { showReadings = false },
                modifier = Modifier.heightIn(max = 240.dp)) {
                for (reading in readings) {
                    val length = parseParcelLength(reading.metres) ?: continue
                    val caption = "${reading.from}–${reading.to}: $length m"
                    val note = if (reading.reviewNeeded) s(R.string.smart_read_needs_review) else null
                    DropdownMenuItem(text = { Text(listOfNotNull(caption, note).joinToString(" · ")) },
                        onClick = {
                            onChange(edge.copy(length = length))
                            showReadings = false
                        })
                }
            }
        }
    }
}

private fun scaleCandidate(scan: ParcelScan, frame: ParcelFrame): List<ParcelPoint> {
    val originalAspect = scan.frame.width.toDouble() / scan.frame.height
    val previewAspect = frame.width.toDouble() / frame.height
    if (abs(previewAspect / originalAspect - 1) > .015) return emptyList()
    return scan.outline.map {
        ParcelPoint(it.x * frame.width / scan.frame.width, it.y * frame.height / scan.frame.height)
    }.takeIf { frame.validatedRing(it) != null }.orEmpty()
}

@Composable
private fun ParcelPageCanvas(
    page: ParcelPagePreview,
    vertices: List<ParcelPoint>,
    edges: Map<Int, EditableParcelEdge>,
    estimatedLengths: List<String>,
    selected: Int?,
    active: Boolean,
    modifier: Modifier,
    onAdd: (ParcelPoint) -> Unit,
    onSelect: (Int) -> Unit,
    onMove: (Int, ParcelPoint) -> Unit
) {
    val currentVertices by rememberUpdatedState(vertices)
    val currentAdd by rememberUpdatedState(onAdd)
    val currentSelect by rememberUpdatedState(onSelect)
    val currentMove by rememberUpdatedState(onMove)
    val raster = remember(page) { page.bitmap.asImageBitmap() }

    Canvas(modifier
        .pointerInput(page.frame, active) {
            if (!active) return@pointerInput
            detectTapGestures { touch ->
                val fit = page.frame.fit(size.width.toDouble(), size.height.toDouble())
                val index = closestVertex(currentVertices, fit, touch, 20.dp.toPx())
                if (index != null) currentSelect(index)
                else fit.toSource(ParcelPoint(touch.x.toDouble(), touch.y.toDouble()))?.let(currentAdd)
            }
        }
        .pointerInput(page.frame, active) {
            if (!active) return@pointerInput
            var dragging: Int? = null
            detectDragGestures(onDragStart = { touch ->
                val fit = page.frame.fit(size.width.toDouble(), size.height.toDouble())
                dragging = closestVertex(currentVertices, fit, touch, 24.dp.toPx())
                dragging?.let(currentSelect)
            }, onDragCancel = { dragging = null }, onDragEnd = { dragging = null }) { change, _ ->
                val index = dragging ?: return@detectDragGestures
                val fit = page.frame.fit(size.width.toDouble(), size.height.toDouble())
                val position = fit.toSource(ParcelPoint(change.position.x.toDouble(), change.position.y.toDouble()))
                if (position != null) currentMove(index, position)
                change.consume()
            }
        }) {
        val fit = page.frame.fit(size.width.toDouble(), size.height.toDouble())
        drawRect(Brand.Pearl)
        drawImage(raster, dstOffset = IntOffset(fit.left.roundToInt(), fit.top.roundToInt()),
            dstSize = IntSize((page.frame.width * fit.scale).roundToInt(), (page.frame.height * fit.scale).roundToInt()))
        val centres = vertices.map { fit.toView(it).let { offset -> Offset(offset.x.toFloat(), offset.y.toFloat()) } }
        if (centres.isEmpty()) return@Canvas
        val outline = Path().apply {
            moveTo(centres.first().x, centres.first().y)
            centres.drop(1).forEach { lineTo(it.x, it.y) }
            if (centres.size >= 3) close()
        }
        if (centres.size >= 3) drawPath(outline, Brand.Crimson.copy(alpha = .16f))
        drawPath(outline, Color.White, style = Stroke(width = 4.dp.toPx()))
        drawPath(outline, Brand.Crimson, style = Stroke(width = 2.dp.toPx()))
        val labelPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12.dp.toPx()
            color = android.graphics.Color.BLACK
            isFakeBoldText = true
        }
        val textBackground = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
        }
        fun label(value: String, x: Float, y: Float) {
            val width = labelPaint.measureText(value)
            val left = x.coerceIn(0f, (size.width - width - 8f).coerceAtLeast(0f))
            val bottom = y.coerceIn(labelPaint.textSize + 4f, size.height)
            drawIntoCanvas {
                it.nativeCanvas.drawRoundRect(left, bottom - labelPaint.textSize - 4f,
                    left + width + 8f, bottom + 2f, 6f, 6f, textBackground)
                it.nativeCanvas.drawText(value, left + 4f, bottom - 2f, labelPaint)
            }
        }
        centres.forEachIndexed { index, centre ->
            val radius = if (index == selected) 8.dp.toPx() else 6.dp.toPx()
            drawCircle(Color.White, radius + 2.dp.toPx(), centre)
            drawCircle(Brand.Crimson, radius, centre)
            label("P${index + 1}", centre.x + radius + 3.dp.toPx(), centre.y - radius)
            if (centres.size >= 3) {
                val edge = edges[index]
                val length = edge?.let { parseParcelLength(it.length) }
                val caption = if (length == null) estimatedLengths.getOrNull(index) ?: return@forEachIndexed
                    else listOfNotNull(edge?.label?.takeIf(String::isNotBlank), "$length m").joinToString(" · ")
                val next = centres[(index + 1) % centres.size]
                label(caption, (centre.x + next.x) / 2f, (centre.y + next.y) / 2f)
            }
        }
    }
}

private fun closestVertex(
    vertices: List<ParcelPoint>, fit: ParcelFit, touch: Offset, threshold: Float
): Int? {
    var closest: Int? = null
    var best = threshold * threshold.toDouble()
    for ((index, vertex) in vertices.withIndex()) {
        val displayed = fit.toView(vertex)
        val dx = displayed.x - touch.x
        val dy = displayed.y - touch.y
        val distance = dx * dx + dy * dy
        if (distance < best) {
            best = distance
            closest = index
        }
    }
    return closest
}

private fun priorIsLocalCandidate(vertices:List<ParcelPoint>, candidates:List<List<ParcelPoint>>):Boolean = candidates.any { it==vertices }
