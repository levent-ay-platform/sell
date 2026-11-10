package tn.malak.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MAX_DOCUMENT_BYTES = 12L * 1024L * 1024L
private data class PickedEvidence(val uri: Uri, val label: String, val size: Long?)
private data class DemoReadField(
    val label: String, val value: String, val source: String,
    val state: String, val confidence: String? = null
)

/**
 * Visual, explicitly labelled synthetic OCR preview. It is NOT extracted from
 * the selected phone document. Replace only with an authenticated private server
 * OCR presentation DTO when the backend endpoint has been approved and mounted.
 */
private val SyntheticOcrPreview = listOf(
    DemoReadField("المساحة حسب الشهادة", "240", "صفحة 1 · OCR عربي", "قيمة أولية", "96%"),
    DemoReadField("عدد صفحات الشهادة", "2", "صفحة 1 · OCR", "ناقص صفحة", "94%"),
    DemoReadField("مرجع الرسم العقاري", "—", "الشهادة", "بحاجة إلى قراءة أوضح"),
    DemoReadField("الأنصبة والحصص", "—", "صفحة 2 غير موجودة", "تحتاج مراجعة بشرية"),
    DemoReadField("تاريخ إصدار الشهادة", "—", "الشهادة", "لم يُستخرج")
)

@Composable
fun SellerOcrJourney(onBack: () -> Unit) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var propertyTitle by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("") }
    var declaredArea by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    var legalCapacity by rememberSaveable { mutableStateOf("مالك منفرد") }
    var showSyntheticDemo by rememberSaveable { mutableStateOf(false) }
    var documentNotice by remember { mutableStateOf("") }
    val files = remember { mutableStateListOf<PickedEvidence>() }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        var rejected = 0
        for (uri in uris) {
            if (files.size >= 8) { rejected++; continue }
            if (files.any { it.uri == uri }) continue
            var name = "وثيقة محددة"
            var size: Long? = null
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0) name = cursor.getString(nameIndex)?.take(80) ?: name
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
                if (size == null || size <= 0L || size > MAX_DOCUMENT_BYTES) { rejected++; continue }
                val type = context.contentResolver.getType(uri)
                if (type !in setOf("application/pdf", "image/jpeg", "image/png", "image/tiff")) {
                    rejected++; continue
                }
                files.add(PickedEvidence(uri, name, size))
            } catch (_: Exception) { rejected++ }
        }
        documentNotice = if (rejected > 0) "$rejected ملف لم يُقبل (نوع أو حجم غير مدعوم، أو أكثر من 8 صفحات)." else "تم اختيار الملفات محلياً. لم تُرفع أو تُقرأ بعد."
        showSyntheticDemo = false
    }

    Column(
        Modifier.fillMaxSize().background(Brand.Pearl).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("‹ رجوع", Modifier.clickable { if (step > 0) step-- else onBack() }, color = Brand.Crimson)
            Text("أضف أرضك", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Brand.Ink)
        }
        Text("طلب أرض جديد · مسودة غير منشورة", color = Brand.Muted, fontSize = 12.sp)
        LinearProgressIndicator(progress = { (step + 1) / 4f }, modifier = Modifier.fillMaxWidth(),
            color = Brand.Crimson, trackColor = Brand.Rose)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("العقار", "الصفة", "الشهادة", "الملخّص").forEachIndexed { index, label ->
                Text(label, color = if (index == step) Brand.Crimson else Brand.Muted,
                    fontWeight = if (index == step) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp)
            }
        }
        when (step) {
            0 -> {
                JourneyHeadline("عرّفنا على أرضك", "أضف معلومات واضحة. سنقارن المساحة لاحقاً بما تقرؤه أداة OCR من الشهادة.")
                OutlinedTextField(propertyTitle, { propertyTitle = it.take(100) },
                    label = { Text("عنوان الإعلان") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(region, { region = it.take(100) },
                    label = { Text("المنطقة / المعتمدية") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(declaredArea, { declaredArea = it.filter { ch -> ch.isDigit() || ch == '.' }.take(12) },
                    label = { Text("المساحة بالمتر المربع") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                OutlinedTextField(price, { price = it.filter { ch -> ch.isDigit() }.take(15) },
                    label = { Text("السعر بالدينار التونسي") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
            }
            1 -> {
                JourneyHeadline("ما صفتك القانونية؟", "الاختيار إقرار أولي منك، ولا يثبت الملكية أو حق التصرف.")
                listOf("مالك منفرد", "شريك في الملكية", "وريث", "صاحب وكالة").forEach { capacity ->
                    Surface(Modifier.fillMaxWidth().clickable { legalCapacity = capacity },
                        color = if (legalCapacity == capacity) Brand.Rose else Color.White,
                        shape = RoundedCornerShape(15.dp)) {
                        Text((if (legalCapacity == capacity) "✓  " else "○  ") + capacity,
                            Modifier.padding(15.dp), fontSize = 15.sp, color = Brand.Ink)
                    }
                }
                JourneyNote("التحقق من الصفة، الأنصبة والهوية يتم لاحقاً على المستندات الأصلية ومن مصدر مخوّل.")
            }
            2 -> {
                JourneyHeadline("شهادة الملكية", "صوّر جميع الصفحات، أو اختر PDF كاملاً. تظهر البيانات المقروءة هنا بعد تشغيل خدمة OCR الخاصة.")
                Surface(color = Color.White, shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text("▤  مستندات العقار", fontWeight = FontWeight.Bold, color = Brand.Ink, fontSize = 17.sp)
                        Text("PDF / JPG / PNG / TIFF · حتى 8 ملفات · 12 MB لكل ملف",
                            color = Brand.Muted, fontSize = 12.sp)
                        Button(onClick = { pickFiles.launch(arrayOf("application/pdf", "image/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = Brand.Crimson),
                            modifier = Modifier.fillMaxWidth()) {
                            Text("اختيار صور أو PDF من الهاتف")
                        }
                    }
                }
                files.forEachIndexed { index, file ->
                    Row(Modifier.fillMaxWidth().background(Brand.Rose, RoundedCornerShape(12.dp))
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}. ${file.label}", Modifier.weight(1f),
                            fontSize = 12.sp, color = Brand.Ink, maxLines = 2)
                        Text("إزالة", Modifier.clickable { files.remove(file); showSyntheticDemo = false },
                            color = Brand.Crimson, fontSize = 12.sp)
                    }
                }
                if (documentNotice.isNotBlank()) JourneyNote(documentNotice)
                JourneyNote("خصوصيتك: اختيار الملف لا يعني رفعه. هذه النسخة لا ترسل مستندات إلى خادم حتى تجهز المصادقة والتخزين الخاص.")
                OutlinedButton(onClick = { showSyntheticDemo = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("شاهد مثالاً توضيحياً لقراءة OCR")
                }
            }
            else -> {
                JourneyHeadline("ملخّص عقارك الذكي", "معلومات منظّمة مع مصادرها ونقاط المراجعة. لا توجد موافقة ملكية تلقائية.")
                JourneyFact("العقار", propertyTitle.ifBlank { "غير مُدخل" })
                JourneyFact("الموقع", region.ifBlank { "غير مُدخل" })
                JourneyFact("المساحة المُصرّح بها", if (declaredArea.isBlank()) "غير مُدخلة" else "$declaredArea م²")
                JourneyFact("صفة مقدّم الطلب", legalCapacity)
                if (showSyntheticDemo) {
                    JourneyNote("مثال اصطناعي للعرض فقط. القيم التالية لم تُقرأ من ملفك، ولا تُعد بيانات عقار حقيقي.")
                    SyntheticOcrPreview.forEach { field -> OcrPreviewFact(field) }
                    val area = declaredArea.toDoubleOrNull()
                    val demoArea = 240.0
                    val comparison = when {
                        area == null -> "أدخل المساحة حتى تظهر المقارنة"
                        area == demoArea -> "القيمتان متساويتان عددياً في المثال فقط"
                        else -> "اختلاف يستلزم مراجعة: المصرّح $declaredArea م²، مثال OCR: 240 م²"
                    }
                    JourneyFact("مقارنة أولية للمساحة", comparison)
                } else {
                    JourneyNote("لم تصدر نتيجة OCR بعد. ${files.size} ملف/ملفات محددة محلياً، لكن لا يوجد رفع أو استخراج حقيقي في هذا الإصدار.")
                }
                Surface(color = Brand.Rose, shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("المرحلة الثانية — التحقق والتطابق", fontWeight = FontWeight.Bold, color = Brand.Crimson)
                        Text("تطابق الهوية مع صاحب الحق، مرجع شهادة الملكية، المساحة، الأنصبة، وحداثة الوضعية القانونية لدى الجهة المختصة.",
                            color = Brand.Ink, fontSize = 13.sp)
                        Text("الحالة: لم يبدأ التحقق الرسمي · الإعلان غير قابل للنشر",
                            color = Brand.Crimson, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Button(onClick = { if (step < 3) step++ else onBack() },
            enabled = step != 0 || (propertyTitle.isNotBlank() && region.isNotBlank() &&
                (declaredArea.toDoubleOrNull() ?: 0.0) > 0 && (price.toLongOrNull() ?: 0) > 0),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Brand.Crimson),
            shape = RoundedCornerShape(24.dp)) {
            Text(if (step < 3) "متابعة ←" else "إنهاء المعاينة", fontSize = 15.sp)
        }
        Text("تجربة واجهة محلية · لا إرسال ولا توثيق ملكية ولا نشر للعامة",
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            fontSize = 11.sp, color = Brand.Muted)
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun JourneyHeadline(title: String, subtitle: String) {
    Surface(color = Color.White, shape = RoundedCornerShape(19.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Brand.Crimson)
            Text(subtitle, fontSize = 13.sp, color = Brand.Muted, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun JourneyNote(message: String) {
    Surface(color = Brand.Rose, shape = RoundedCornerShape(14.dp)) {
        Text(message, Modifier.fillMaxWidth().padding(13.dp), color = Brand.Ink,
            fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun JourneyFact(label: String, value: String) {
    Surface(color = Color.White, shape = RoundedCornerShape(15.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Brand.Muted, fontSize = 12.sp, modifier = Modifier.weight(0.85f))
            Text(value, color = Brand.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1.15f), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun OcrPreviewFact(field: DemoReadField) {
    Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(field.label, color = Brand.Muted, fontSize = 12.sp)
                Text(field.state, color = Brand.Crimson, fontSize = 11.sp)
            }
            Text(field.value, color = Brand.Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(field.source, color = Brand.Muted, fontSize = 11.sp)
                if (field.confidence != null) Text("ثقة القراءة: ${field.confidence}",
                    color = Brand.Muted, fontSize = 11.sp)
            }
        }
    }
}
