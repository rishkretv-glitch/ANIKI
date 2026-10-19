package com.pdfarchitect.starter

import kotlin.math.abs

private fun eq(a: Float, b: Float) { check(abs(a - b) < 0.00001f) { "$a != $b" } }
private fun corner(t: PageTransform, x: Float, y: Float, px: Float, py: Float) {
    val (a, b) = t.point(x, y); eq(a, px); eq(b, py)
}
fun main() {
    val x = 15f; val y = 25f; val w = 200f; val h = 100f
    val t0 = PageTransform.forPage(x,y,w,h,0)
    corner(t0, 0f,0f,15f,125f); corner(t0,w,h,215f,25f)
    val t90 = PageTransform.forPage(x,y,w,h,90)
    eq(t90.widthPt, h); eq(t90.heightPt, w)
    corner(t90,0f,0f,15f,25f); corner(t90,h,w,215f,125f)
    val t180 = PageTransform.forPage(x,y,w,h,180)
    corner(t180,0f,0f,215f,25f); corner(t180,w,h,15f,125f)
    val t270 = PageTransform.forPage(x,y,w,h,270)
    corner(t270,0f,0f,215f,125f); corner(t270,h,w,15f,25f)
    println("PageSpace: 4 rotations, crop-offset corners OK")
}
