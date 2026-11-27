package tn.malak.app.ui.parcel

import org.junit.Assert.*
import org.junit.Test

class ParcelGeometryTest {
    private val frame = ParcelFrame(1000, 600)
    private val square = listOf(ParcelPoint(100.0, 100.0), ParcelPoint(800.0, 100.0),
        ParcelPoint(800.0, 500.0), ParcelPoint(100.0, 500.0))

    @Test fun closesAValidOpenRingWithoutChangingDimensions() {
        assertEquals(square + square.first(), frame.validatedRing(square))
        assertEquals(square + square.first(), frame.validatedRing(square + square.first()))
    }

    @Test fun rejectsCrossingsCollinearReversalAndOutOfRangeCoordinates() {
        assertNull(frame.validatedRing(listOf(square[0], square[2], square[1], square[3])))
        assertNull(frame.validatedRing(listOf(square[0], square[1], ParcelPoint(200.0, 100.0),
            square[2], square[3])))
        assertNull(frame.validatedRing(square.dropLast(1) + ParcelPoint(-2.0, 500.0)))
        assertNull(frame.validatedRing(square.dropLast(1) + ParcelPoint(Double.NaN, 500.0)))
    }

    @Test fun mapsTouchBackToTheSourceRasterWithoutUsingScreenScaleAsMeters() {
        val fit = frame.fit(400.0, 400.0)
        assertEquals(ParcelPoint(250.0, 300.0), fit.toSource(ParcelPoint(100.0, 200.0)))
        assertNull(fit.toSource(ParcelPoint(100.0, 20.0)))
    }

    @Test fun normalizesArabicAndFrenchDecimalsWithoutRounding() {
        assertEquals("25.125", parseParcelLength("٢٥٫١٢٥"))
        assertEquals("0.025", parseParcelLength("0,025"))
        assertNull(parseParcelLength("0"))
        assertNull(parseParcelLength("-1"))
        assertNull(parseParcelLength("1.0000001"))
    }
    @Test fun calibrationUsesOneExplicitSideAndScalesAreaQuadratically() {
        val calibrated = checkNotNull(frame.calibrateParcel(square, 0, "٣٥"))
        assertEquals(700.0, calibrated.areaM2, 0.0001)
        assertEquals(110.0, calibrated.perimeterM, 0.0001)
        assertEquals(listOf(35.0, 20.0, 35.0, 20.0), calibrated.lengthsM)
        assertEquals(calibrated, frame.calibrateParcel(square + square.first(), 3, "20"))
    }

    @Test fun calibrationRejectsAnInvalidRingOrMissingReference() {
        assertNull(frame.calibrateParcel(square, 0, ""))
        assertNull(frame.calibrateParcel(square, 4, "35"))
        assertNull(frame.calibrateParcel(listOf(square[0], square[2], square[1], square[3]), 0, "35"))
    }
}
