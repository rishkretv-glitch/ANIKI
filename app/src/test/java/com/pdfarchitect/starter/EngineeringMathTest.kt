package com.pdfarchitect.starter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EngineeringMathTest {
    private val calibration = Calibration(
        Segment(InkPoint(0f, 0f), InkPoint(0.5f, 0f)), 5.0
    )

    @Test fun calibratedDistanceUsesPdfGeometryNotScreenPixels() {
        val vertical = Segment(InkPoint(0f, 0f), InkPoint(0f, 0.25f))
        assertEquals(1.25, MeasureMath.meters(vertical, calibration, 1000, 500)!!, 0.00001)
        assertEquals(5.0, MeasureMath.meters(
            Segment(InkPoint(0.1f, 0.1f), InkPoint(0.6f, 0.1f)),
            calibration, 1000, 500)!!, 0.00001)
    }

    @Test fun invalidReferenceDoesNotProduceFakeMeasurement() {
        val area = AreaPolygon(listOf(
            InkPoint(0f, 0f), InkPoint(1f, 0f), InkPoint(0f, 1f)))
        assertNull(MeasureMath.squareMeters(area, null, 1000, 500))
        assertNull(MeasureMath.squareMeters(area,
            Calibration(Segment(InkPoint(0f, 0f), InkPoint(0f, 0f)), 5.0), 1000, 500))
    }

    @Test fun rectangularAreaScalesWithCalibratedRatio() {
        // Calibrated 0.5 * 1000 PDF points = 5m, so 1 page point = 0.01m.
        // Rectangle: width 0.5 * 1000 = 500pt = 5m, height 0.4 * 500 = 200pt = 2m.
        val rectangle = AreaPolygon(listOf(
            InkPoint(0.1f, 0.1f), InkPoint(0.6f, 0.1f),
            InkPoint(0.6f, 0.5f), InkPoint(0.1f, 0.5f)))
        assertEquals(10.0, MeasureMath.squareMeters(rectangle, calibration, 1000, 500)!!, 0.00001)
        assertEquals(10.0, MeasureMath.squareMeters(
            AreaPolygon(rectangle.points.reversed()), calibration, 1000, 500)!!, 0.00001)
    }

    @Test fun anAreaWithoutOtherMarksIsIncludedInPreservingExport() {
        val areaOnly = PageEdits(areas = listOf(AreaPolygon(listOf(
            InkPoint(0f, 0f), InkPoint(1f, 0f), InkPoint(0f, 1f)))))
        org.junit.Assert.assertTrue(areaOnly.hasVisibleMarkup())
        org.junit.Assert.assertFalse(PageEdits().hasVisibleMarkup())
    }

    @Test fun pageTransformsHandleRotationsAndOffsets() {
        val base = PageTransform.forPage(15f, 25f, 200f, 100f, 0)
        assertEquals(15f, base.point(0f, 0f).first, 0.00001f)
        assertEquals(125f, base.point(0f, 0f).second, 0.00001f)
        val rotated = PageTransform.forPage(15f, 25f, 200f, 100f, 90)
        assertEquals(100f, rotated.widthPt, 0.00001f)
        assertEquals(200f, rotated.heightPt, 0.00001f)
        assertEquals(215f, rotated.point(100f, 200f).first, 0.00001f)
        assertEquals(125f, rotated.point(100f, 200f).second, 0.00001f)
    }
}
