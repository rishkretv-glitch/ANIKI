package com.pdfarchitect.starter

import kotlin.math.abs

fun main() {
    val scale = Calibration(Segment(InkPoint(0f, 0f), InkPoint(0.5f, 0f)), 5.0)
    val rectangle = AreaPolygon(listOf(
        InkPoint(0.1f, 0.1f), InkPoint(0.6f, 0.1f),
        InkPoint(0.6f, 0.5f), InkPoint(0.1f, 0.5f),
    ))
    val area = MeasureMath.squareMeters(rectangle, scale, 1000, 500)!!
    check(abs(area - 10.0) < 1e-6) { "Area must be 10m2, got $area" }
    val reverse = MeasureMath.squareMeters(AreaPolygon(rectangle.points.reversed()), scale, 1000, 500)!!
    check(abs(reverse - area) < 1e-6)
    check(MeasureMath.squareMeters(rectangle, null, 1000, 500) == null)
    println("Area smoke tests passed: $area m2")
}
