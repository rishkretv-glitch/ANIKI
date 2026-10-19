package com.pdfarchitect.starter

/**
 * Converts displayed-page coordinates (top left, y down) into PDF user space.
 * Handles /CropBox offset and /Rotate in 90 degree increments. Units are points.
 * Assumes conventional /UserUnit=1. Every edit is stored in normalized [0, 1].
 *
 * Matrix [a,b,c,d,e,f]: x'=a*x+c*y+e; y'=b*x+d*y+f.
 */
data class PageTransform(val widthPt: Float, val heightPt: Float,
                         val a: Float, val b: Float, val c: Float, val d: Float,
                         val e: Float, val f: Float) {
    fun point(x: Float, y: Float): Pair<Float, Float> =
        (a * x + c * y + e) to (b * x + d * y + f)

    companion object {
        fun forPage(cropX: Float, cropY: Float, cropWidth: Float, cropHeight: Float,
                    rotationDegrees: Int): PageTransform {
            require(cropWidth > 0f && cropHeight > 0f)
            val rot = ((rotationDegrees % 360) + 360) % 360
            require(rot % 90 == 0) { "Unsupported PDF rotation: $rotationDegrees" }
            return when (rot) {
                0 -> PageTransform(cropWidth, cropHeight, 1f, 0f, 0f, -1f,
                    cropX, cropY + cropHeight)
                90 -> PageTransform(cropHeight, cropWidth, 0f, 1f, 1f, 0f,
                    cropX, cropY)
                180 -> PageTransform(cropWidth, cropHeight, -1f, 0f, 0f, 1f,
                    cropX + cropWidth, cropY)
                else -> PageTransform(cropHeight, cropWidth, 0f, -1f, -1f, 0f,
                    cropX + cropWidth, cropY + cropHeight)
            }
        }
    }
}
