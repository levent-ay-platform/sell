package tn.malak.app.ui.parcel

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

internal data class ParcelPoint(val x: Double, val y: Double)

internal data class ParcelFrame(val width: Int, val height: Int) {
    init {
        require(width in 1..100000 && height in 1..100000)
    }

    fun fit(viewWidth: Double, viewHeight: Double): ParcelFit {
        val scale = min(viewWidth / width, viewHeight / height)
        return ParcelFit(this, scale, (viewWidth - width * scale) / 2, (viewHeight - height * scale) / 2)
    }

    fun contains(point: ParcelPoint): Boolean = point.x in 0.0..width.toDouble() &&
        point.y in 0.0..height.toDouble()

    fun validatedRing(input: List<ParcelPoint>): List<ParcelPoint>? {
        val vertices = openParcelRing(input)
        if (vertices.size !in 3..128 || vertices.any { !contains(it) } ||
            vertices.toSet().size != vertices.size) return null
        val ring = vertices + vertices.first()
        var twiceArea = 0.0
        for (index in vertices.indices) {
            val a = ring[index]
            val b = ring[index + 1]
            val previous = vertices[(index - 1 + vertices.size) % vertices.size]
            if (cross(previous, a, b) == 0.0 &&
                (onSegment(previous, a, b) || onSegment(a, b, previous))) return null
            twiceArea += a.x * b.y - b.x * a.y
            for (other in index + 2 until vertices.size) {
                if (index == 0 && other == vertices.lastIndex) continue
                if (intersects(a, b, ring[other], ring[other + 1])) return null
            }
        }
        return ring.takeIf { twiceArea != 0.0 }
    }
}

internal data class ParcelCalibration(val lengthsM: List<Double>, val areaM2: Double) {
    val perimeterM: Double get() = lengthsM.sum()
}

/** Estimates dimensions on a plan from a side measured in metres by its owner. */
internal fun ParcelFrame.calibrateParcel(
    vertices: List<ParcelPoint>, referenceEdge: Int, referenceLength: String
): ParcelCalibration? {
    val metres = parseParcelLength(referenceLength)?.toDouble() ?: return null
    val ring = validatedRing(vertices) ?: return null
    val sides = ring.zipWithNext()
    val sourceLengths = sides.map { (a, b) -> hypot(b.x - a.x, b.y - a.y) }
    val reference = sourceLengths.getOrNull(referenceEdge) ?: return null
    val metresPerUnit = metres / reference
    val twiceArea = sides.sumOf { (a, b) -> a.x * b.y - b.x * a.y }
    return ParcelCalibration(sourceLengths.map { it * metresPerUnit },
        abs(twiceArea) / 2 * metresPerUnit * metresPerUnit)
}

internal data class ParcelFit(
    val frame: ParcelFrame, val scale: Double, val left: Double, val top: Double
) {
    fun toView(point: ParcelPoint) = ParcelPoint(left + point.x * scale, top + point.y * scale)

    fun toSource(point: ParcelPoint): ParcelPoint? {
        if (scale <= 0.0) return null
        return ParcelPoint((point.x - left) / scale, (point.y - top) / scale)
            .takeIf(frame::contains)
    }
}

internal fun openParcelRing(points: List<ParcelPoint>): List<ParcelPoint> =
    if (points.size >= 2 && points.first() == points.last()) points.dropLast(1) else points

private fun cross(a: ParcelPoint, b: ParcelPoint, c: ParcelPoint): Double =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

private fun onSegment(a: ParcelPoint, b: ParcelPoint, point: ParcelPoint): Boolean =
    point.x in minOf(a.x, b.x)..maxOf(a.x, b.x) &&
        point.y in minOf(a.y, b.y)..maxOf(a.y, b.y)

private fun intersects(a: ParcelPoint, b: ParcelPoint, c: ParcelPoint, d: ParcelPoint): Boolean {
    val abc = cross(a, b, c)
    val abd = cross(a, b, d)
    val cda = cross(c, d, a)
    val cdb = cross(c, d, b)
    return (abc == 0.0 && onSegment(a, b, c)) || (abd == 0.0 && onSegment(a, b, d)) ||
        (cda == 0.0 && onSegment(c, d, a)) || (cdb == 0.0 && onSegment(c, d, b)) ||
        (abc * abd < 0 && cda * cdb < 0)
}

private val ParcelLength = Regex("[0-9]{1,7}(?:\\.[0-9]{1,6})?")

internal fun parseParcelLength(input: String): String? {
    val normalized = buildString {
        for (character in input.trim()) {
            append(character.digitToIntOrNull()?.toString() ?: when (character) {
                ',', '٫' -> "."
                else -> character.toString()
            })
        }
    }
    if (!ParcelLength.matches(normalized)) return null
    return normalized.toBigDecimal().takeIf { it.signum() > 0 }?.stripTrailingZeros()?.toPlainString()
}
