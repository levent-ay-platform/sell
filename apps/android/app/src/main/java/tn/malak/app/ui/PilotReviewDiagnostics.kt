package tn.malak.app.ui

internal data class PilotReviewDiagnostics(val reasons:List<String>,val expectedPageCount:Int?)

/** Validate untrusted optional pilot diagnostics; keep old servers compatible. */
internal fun pilotReviewDiagnostics(rawReasons:List<String>?,missingPages:List<Int>?,
                                   receivedPages:Int):PilotReviewDiagnostics {
    require(receivedPages in 1..8)
    val reasons=rawReasons?:listOf("OFFICIAL_REGISTRY_NOT_CHECKED")
    require(reasons.size<=32&&reasons.all{Regex("^[A-Z0-9_]{3,80}$").matches(it)})
    val missing=missingPages?:emptyList()
    require(missing.size<=8&&missing.all{it in 1..8}&&missing.distinct().size==missing.size)
    return PilotReviewDiagnostics(reasons.distinct(),
        missing.maxOrNull()?.let{maxOf(it,receivedPages)})
}
