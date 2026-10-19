package com.pdfarchitect.starter

import org.junit.Assert.assertEquals
import org.junit.Test

class GeometryUnitTest {
    @Test fun fourPageRotationsRespectCropBox() {
        val expected = listOf(
            Triple(0, 15f to 125f, 215f to 25f),
            Triple(90, 15f to 25f, 215f to 125f),
            Triple(180, 215f to 25f, 15f to 125f),
            Triple(270, 215f to 125f, 15f to 25f),
        )
        expected.forEach { (rotation, topLeft, bottomRight) ->
            val t = PageTransform.forPage(15f, 25f, 200f, 100f, rotation)
            val a = t.point(0f, 0f)
            val b = t.point(t.widthPt, t.heightPt)
            assertEquals(topLeft.first, a.first, .001f)
            assertEquals(topLeft.second, a.second, .001f)
            assertEquals(bottomRight.first, b.first, .001f)
            assertEquals(bottomRight.second, b.second, .001f)
        }
    }

    @Test fun calibratedMeasurementUsesPageUnits() {
        val baseline = Segment(InkPoint(.1f, .1f), InkPoint(.6f, .1f))
        val measured = Segment(InkPoint(.1f, .2f), InkPoint(.3f, .2f))
        val actual = MeasureMath.meters(measured, Calibration(baseline, 5.0), 600, 800)
        assertEquals(2.0, actual ?: 0.0, 1e-6)
    }
}
