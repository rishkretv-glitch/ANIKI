package com.pdfarchitect.starter

import kotlin.math.abs

fun main() {
    fun point(x: Float, y: Float) = InkPoint(x, y)
    val calibration = Calibration(Segment(point(0f, 0f), point(0.5f, 0f)), 5.0)
    val other = Segment(point(0f, 0f), point(0f, 0.25f))
    // Page is twice as wide as tall: 50% across equals page height in points; 25% down is 125/500 of baseline.
    val expected = 1.25
    val actual = MeasureMath.meters(other, calibration, 1000, 500)!!
    check(abs(actual - expected) < 1e-9) { "$actual != $expected" }
    val doubleSpan = Segment(point(0f, 0f), point(1f, 0f))
    check(abs(MeasureMath.meters(doubleSpan, calibration, 1000, 500)!! - 10.0) < 1e-9)
    check(MeasureMath.meters(other, null, 1000, 500) == null)
    check(MeasureMath.meters(other,
        Calibration(Segment(point(0f, 0f), point(0f, 0f)), 10.0), 1000, 500) == null)
    println("Geometry smoke tests passed")
}
