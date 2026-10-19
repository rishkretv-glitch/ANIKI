package com.pdfarchitect.starter

fun main() {
    val areaOnly = PageEdits(areas = listOf(AreaPolygon(listOf(
        InkPoint(0f, 0f), InkPoint(0.5f, 0f), InkPoint(0f, 0.5f)))))
    check(areaOnly.hasVisibleMarkup()) { "Areas must be preserved in PDF export" }
    check(!PageEdits().hasVisibleMarkup())
    println("Export readiness: area-only page included; blank page skipped")
}
