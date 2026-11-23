package tn.malak.app.ui.explore

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import tn.malak.app.ui.Brand
import java.time.Instant
import java.math.BigDecimal

/** No network call here. Only accepts an approved public Nozha card contract. */
internal enum class NozhaLotStatus {
    AVAILABLE, HELD, RESERVED, SOLD, BLOCKED;
    fun canInquire(fresh: Boolean): Boolean = fresh && this == AVAILABLE
    val color: Color get() = when (this) {
        AVAILABLE -> Color(0xFF16833D)
        SOLD -> Color(0xFFCA252F)
        RESERVED -> Color(0xFFB76B12)
        HELD -> Color(0xFF9B741A)
        BLOCKED -> Color(0xFF637080)
    }
    fun label(ar: Boolean): String = when (this) {
        AVAILABLE -> if (ar) "متاح" else "Disponible"
        SOLD -> if (ar) "مباع" else "Vendu"
        RESERVED -> if (ar) "محجوز" else "Réservé"
        HELD -> if (ar) "معلّق" else "En attente"
        BLOCKED -> if (ar) "غير معروض" else "Retiré"
    }
}
internal data class NozhaLot(
    val code: String, val state: NozhaLotStatus,
    val area: String?, val askingMinor: String?, val inquiryAllowed: Boolean
)
internal data class NozhaProjectCard(
    val updatedAt: String,
    val current: Boolean,
    val lots: List<NozhaLot>,
    val counts: Map<NozhaLotStatus, Int>,
    val businessLandClassification: String?,
    val businessWater: String?,
    val businessElectricity: String?
)

/**
 * Amlak must fetch this JSON from a separately authorized HTTPS gateway,
 * never directly from CRM's private tRPC router. No automatic Atlas ownership
 * or cadastral approval and no person/legal TF fields in this model.
 */
internal fun decodeNozhaProject(body: JSONObject, nowMillis: Long): NozhaProjectCard? {
    if (body.optInt("schemaVersion") != 1 ||
        body.optString("source") != "CASAIMED_CRM") return null
    val project = body.optJSONObject("project") ?: return null
    if (project.optString("id") != "3" || project.optString("code") != "561663" ||
        project.optString("title") != "Nozha") return null
    val advertiser = body.optJSONObject("advertisedBy") ?: return null
    if (advertiser.optString("name") != "CasaImed Group" ||
        advertiser.optString("role") != "MARKETING_AGENT_NOT_LEGAL_OWNER") return null
    val proof = body.optJSONObject("verification") ?: return null
    if (listOf("ownership","title","cadastralBoundary","atlasSpatialMatch","overall").any {
            proof.optString(it) !in setOf("NOT_VERIFIED", "NO_APPROVED_OFFICIAL_REFERENCE")
        }) return null
    if (body.optJSONObject("sourcePlan")?.optBoolean("display", true) != false) return null
    val refreshedAt = body.optString("refreshedAt")
    val timestamp = runCatching { Instant.parse(refreshedAt).toEpochMilli() }.getOrNull() ?: return null
    if (nowMillis < 0 || timestamp > nowMillis + 60_000L) return null
    val current = body.optBoolean("inventoryCurrent") &&
        nowMillis - timestamp <= 30 * 60_000L
    val jsonLots = body.optJSONArray("lots") ?: return null
    if (jsonLots.length() !in 1..250) return null
    val codes = mutableSetOf<String>()
    val lots = mutableListOf<NozhaLot>()
    for (i in 0 until jsonLots.length()) {
        val entry = jsonLots.optJSONObject(i) ?: return null
        val code = entry.optString("code")
        if (!code.matches(Regex("^\\d{1,3}(/([12]))?$")) || !codes.add(code)) return null
        val state = runCatching { NozhaLotStatus.valueOf(entry.optString("status")) }
            .getOrNull() ?: return null
        val forSale = entry.optBoolean("availableForInquiry", false)
        if (forSale && !state.canInquire(current)) return null
        val area = entry.optString("areaSqm").takeIf {
            it.matches(Regex("^\\d{1,12}(\\.\\d{1,3})?$")) &&
                it.toBigDecimalOrNull()?.signum() == 1
        }
        val ask = entry.optJSONObject("asking")
        val amount = if (forSale && state.canInquire(current) && ask != null &&
            ask.optString("currency") == "TND" && ask.optInt("exponent") == 3 &&
            ask.optString("pricingBasis") in setOf("total","per_sqm","starting_from"))
            ask.optString("amountMinor").takeIf {
                it.matches(Regex("^\\d{1,15}$")) &&
                    it.toBigDecimalOrNull()?.signum() == 1
            } else null
        lots += NozhaLot(code, state, area, amount, forSale && state.canInquire(current))
    }
    val counts = NozhaLotStatus.entries.associateWith { target ->
        lots.count { it.state == target }
    }
    val summary = body.optJSONObject("counts") ?: return null
    if (summary.optInt("total", -1) != lots.size ||
        summary.optInt("available", -1) != counts[NozhaLotStatus.AVAILABLE] ||
        summary.optInt("reserved", -1) != counts[NozhaLotStatus.RESERVED] ||
        summary.optInt("sold", -1) != counts[NozhaLotStatus.SOLD] ||
        summary.optInt("held", -1) != counts[NozhaLotStatus.HELD] ||
        summary.optInt("blocked", -1) != counts[NozhaLotStatus.BLOCKED]) return null
    fun claim(key: String) = body.optJSONObject("land")?.optString(key)?.trim()
        ?.takeIf { it.length in 1..96 && it != "unknown" }
    return NozhaProjectCard(refreshedAt, current, lots, counts,
        claim("classification"), claim("waterStatus"), claim("electricityStatus"))
}

/** Full detail: public CRM facts alongside independent unverified Atlas state. */
@Composable
internal fun NozhaProjectDetail(
    data: NozhaProjectCard, language: String,
    onBack: () -> Unit, onLotInquiry: (String) -> Unit
) {
    val ar = language == "ar"
    var selected by remember(data) { mutableStateOf<NozhaLot?>(null) }
    Column(Modifier.fillMaxSize().background(Brand.Pearl)
        .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TextButton(onClick = onBack) {
            Text(if (ar) "رجوع للخريطة" else "Retour à la carte")
        }
        Text("Nozha", style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold, color = Brand.Ink)
        Text(if (ar) "معروض بواسطة CasaImed Group" else "Commercialisé par CasaImed Group",
            color = Brand.Muted)
        Text(if (ar) "مرجع CRM: 561663" else "Référence CRM : 561663", color = Brand.Muted)
        Surface(shape = RoundedCornerShape(14.dp),
            color = if (data.current) Color(0xFFE6F4E8) else Color(0xFFFFEBCF)) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(if (data.current) (if (ar) "مخزون CRM محدث" else "Stock CRM à jour")
                    else (if (ar) "المخزون غير محدث: لا نعتمد السعر والتوفر" else
                    "Stock non actualisé : prix et disponibilités à confirmer"),
                    color = Brand.Ink, fontWeight = FontWeight.SemiBold)
                Text(data.updatedAt, color = Brand.Muted)
            }
        }
        Text(if (ar) "حالة اللوتات" else "Statut des lots",
            style = MaterialTheme.typography.titleLarge, color = Brand.Ink)
        NozhaLotStatus.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { status ->
                    Surface(Modifier.weight(1f), color = Color.White,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Brand.Border)) {
                        Column(Modifier.padding(10.dp)) {
                            Text((data.counts[status] ?: 0).toString(), color = status.color,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold)
                            Text(status.label(ar), color = Brand.Ink,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text(if (ar) "اللوتات · آخر حالة CRM" else "Lots · dernier état CRM",
            style = MaterialTheme.typography.titleMedium, color = Brand.Ink)
        data.lots.sortedBy { it.code.substringBefore('/').toIntOrNull() ?: 0 }
            .chunked(4).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.forEach { lot ->
                        Surface(
                            Modifier.weight(1f).heightIn(min = 62.dp).clickable { selected = lot },
                            color = lot.state.color.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, lot.state.color),
                            shape = RoundedCornerShape(10.dp)) {
                            Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(lot.code, fontWeight = FontWeight.Bold, color = lot.state.color)
                                Text(lot.state.label(ar), color = Brand.Ink,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    repeat(4 - group.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        Surface(color = Color.White, shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Brand.Border)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(if (ar) "ملف العقار والتوثيق" else "Dossier foncier et vérification",
                    style = MaterialTheme.typography.titleLarge, color = Brand.Ink)
                Text(if (ar) "الملكية القانونية: غير موثقة" else
                    "Propriété légale : non vérifiée", color = Brand.Ink)
                Text(if (ar) "رقم الرسم العقاري TF: بانتظار إثبات مستقل" else
                    "Titre foncier : référence officielle non vérifiée", color = Brand.Muted)
                Text(if (ar) "الحدود الجغرافية: لم تُعتمد حدود كادستر للنشر" else
                    "Limites : aucun polygone cadastral approuvé", color = Brand.Muted)
                Text(if (ar) "المخطط المصدر: غير مصرح بعرضه للجمهور" else
                    "Plan source : publication non autorisée", color = Brand.Muted)
                Text(if (ar) "التصنيف من CRM: " + (data.businessLandClassification ?: "غير متاح")
                    else "Classement CRM : " + (data.businessLandClassification ?: "inconnu"),
                    color = Brand.Muted)
                Text(if (ar) "الماء: " + (data.businessWater ?: "غير متاح") +
                    " · الكهرباء: " + (data.businessElectricity ?: "غير متاح")
                    else "Eau : " + (data.businessWater ?: "inconnu") +
                    " · Électricité : " + (data.businessElectricity ?: "inconnue"),
                    color = Brand.Muted)
            }
        }
    }
    selected?.let { lot ->
        AlertDialog(onDismissRequest = { selected = null },
            title = { Text((if (ar) "اللوت " else "Lot ") + lot.code) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(lot.state.label(ar))
                    Text((if (ar) "المساحة: " else "Surface : ") + (lot.area ?: "-") +
                        (if (ar) " م²" else " m²"))
                    if (lot.askingMinor != null && lot.inquiryAllowed && data.current) {
                        Text((if (ar) "السعر: " else "Prix : ") +
                            BigDecimal(lot.askingMinor).movePointLeft(3).toPlainString() + " TND")
                    }
                    Text(if (ar) "الحدود القانونية والملكية تحتاج توثيقاً مستقلاً" else
                        "Titre et limites légales à vérifier séparément")
                }
            },
            confirmButton = {
                if (lot.inquiryAllowed && data.current) Button(onClick = {
                    selected = null
                    onLotInquiry(lot.code)
                }) { Text(if (ar) "استفسر" else "Se renseigner") }
                else TextButton(onClick = { selected = null }) {
                    Text(if (ar) "إغلاق" else "Fermer")
                }
            }
        )
    }
}
