package com.pdfarchitect.starter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One PDF page: non-destructive overlay, stylus strokes, touch pan/pinch and measurement drag. */
class InkPageView(context: Context) : View(context) {
    var image: Bitmap? = null
        set(value) { field = value; layoutPage(); invalidate() }
    var pageWidthPt: Int = 1
    var pageHeightPt: Int = 1
    var edits: PageEdits = PageEdits()
        set(value) { field = value; invalidate() }
    var tool: EditorTool = EditorTool.PEN
        set(value) {
            if (field != value) {
                areaDraft.clear()
                invalidate()
            }
            field = value
        }
    var stylusOnly: Boolean = true
    var inkColor: Int = Color.rgb(35, 112, 237)
    var onStroke: (InkStroke) -> Unit = {}
    var onEraseStroke: (Int) -> Unit = {}
    var onTextTap: (InkPoint) -> Unit = {}
    var onImageTap: (InkPoint) -> Unit = {}
    var resolveImage: (String) -> Bitmap? = { null }
    var onSegment: (Segment, Boolean) -> Unit = {}
    var onArea: (AreaPolygon) -> Unit = {}
    var onZoomChanged: (Float) -> Unit = {}

    private var currentZoom: Float = 1f
    var zoom: Float
        get() = currentZoom
        set(value) { setZoomAt(value, width / 2f, height / 2f) }

    private val pageRect = RectF()
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 112, 237); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private var panX = 0f
    private var panY = 0f
    private var inProgress: MutableList<InkPoint>? = null
    private var segmentStart: InkPoint? = null
    private val areaDraft = mutableListOf<InkPoint>()
    private var segmentEnd: InkPoint? = null
    private var eraseGesture = false
    private var touching = false
    private var movingPage = false
    private var lastX = 0f
    private var lastY = 0f
    private var hoverX = Float.NaN
    private var hoverY = Float.NaN

    private val scaler = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoomAt(zoom * detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutPage()
    }

    private fun layoutPage() {
        val b = image
        if (b == null || width <= 0 || height <= 0) { pageRect.setEmpty(); return }
        val base = min(width.toFloat() / b.width, height.toFloat() / b.height)
        val rw = b.width * base * zoom
        val rh = b.height * base * zoom
        panX = panX.coerceIn(-max(0f, (rw - width) / 2f), max(0f, (rw - width) / 2f))
        panY = panY.coerceIn(-max(0f, (rh - height) / 2f), max(0f, (rh - height) / 2f))
        val cx = width / 2f + panX
        val cy = height / 2f + panY
        pageRect.set(cx - rw / 2f, cy - rh / 2f, cx + rw / 2f, cy + rh / 2f)
    }

    private fun setZoomAt(value: Float, x: Float, y: Float) {
        val next = value.coerceIn(1f, 5f)
        if (abs(zoom - next) < 0.001f || pageRect.isEmpty) return
        val fractionX = (x - pageRect.left) / pageRect.width()
        val fractionY = (y - pageRect.top) / pageRect.height()
        currentZoom = next
        val bmp = image ?: return
        val base = min(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
        val rw = bmp.width * base * next
        val rh = bmp.height * base * next
        panX = x - fractionX * rw - width / 2f + rw / 2f
        panY = y - fractionY * rh - height / 2f + rh / 2f
        layoutPage()
        onZoomChanged(next)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(13, 14, 17))
        val bitmap = image ?: return
        if (pageRect.isEmpty) return
        canvas.drawBitmap(bitmap, null, pageRect, imagePaint)
        canvas.save()
        canvas.clipRect(pageRect)
        val temporary = inProgress
        if (temporary != null) {
            val highlighter = tool == EditorTool.HIGHLIGHTER
            AnnotationPainter.draw(canvas, pageRect,
                edits.copy(strokes = edits.strokes + InkStroke(
                    points = temporary,
                    color = if (highlighter) Color.YELLOW else inkColor,
                    widthPt = if (highlighter) 12f else 2.4f,
                    opacity = if (highlighter) 0.32f else 1f,
                )), pageWidthPt, pageHeightPt, resolveImage)
        } else {
            AnnotationPainter.draw(canvas, pageRect, edits, pageWidthPt, pageHeightPt, resolveImage)
        }
        if (segmentStart != null && segmentEnd != null) {
            val a = segmentStart!!
            val b = segmentEnd!!
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(22, 160, 135); strokeWidth = 3f
                strokeCap = Paint.Cap.ROUND
            }
            canvas.drawLine(pageRect.left + a.x * pageRect.width(),
                pageRect.top + a.y * pageRect.height(),
                pageRect.left + b.x * pageRect.width(),
                pageRect.top + b.y * pageRect.height(), paint)
        }
        if (tool == EditorTool.AREA && areaDraft.isNotEmpty()) {
            val draftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(9, 152, 134)
                strokeWidth = max(2f, 2f * resources.displayMetrics.density)
                style = Paint.Style.STROKE
            }
            val vertices = areaDraft.map { point ->
                Pair(pageRect.left + point.x * pageRect.width(),
                    pageRect.top + point.y * pageRect.height())
            }
            vertices.zipWithNext().forEach { (a, b) ->
                canvas.drawLine(a.first, a.second, b.first, b.second, draftPaint)
            }
            vertices.forEachIndexed { i, vertex ->
                canvas.drawCircle(vertex.first, vertex.second, if (i == 0) 9f else 5f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.rgb(9, 152, 134)
                        style = Paint.Style.FILL
                    })
            }
        }
        canvas.restore()
        if (!hoverX.isNaN() && !hoverY.isNaN()) canvas.drawCircle(hoverX, hoverY, 7f, hoverPaint)
    }

    private fun normalized(x: Float, y: Float, pressure: Float): InkPoint = InkPoint(
        ((x - pageRect.left) / pageRect.width()).coerceIn(0f, 1f),
        ((y - pageRect.top) / pageRect.height()).coerceIn(0f, 1f),
        pressure.coerceIn(0f, 2f),
    )

    private fun eventPoint(event: MotionEvent, history: Int? = null): InkPoint = normalized(
        if (history == null) event.getX(0) else event.getHistoricalX(0, history),
        if (history == null) event.getY(0) else event.getHistoricalY(0, history),
        if (history == null) event.getPressure(0) else event.getHistoricalPressure(0, history),
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageRect.isEmpty) return false
        val stylus = event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS ||
            event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        if (!stylus) scaler.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                touching = true
                movingPage = tool == EditorTool.PAN || (stylusOnly && !stylus)
                lastX = event.x; lastY = event.y
                segmentStart = null; segmentEnd = null; inProgress = null; eraseGesture = false
                if (!movingPage && pageRect.contains(event.x, event.y)) {
                    val erase = tool == EditorTool.ERASER ||
                        event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                        (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
                    eraseGesture = erase
                    when {
                        erase -> eraseNear(event.x, event.y)
                        tool == EditorTool.PEN || tool == EditorTool.HIGHLIGHTER -> {
                            inProgress = mutableListOf(eventPoint(event))
                        }
                        tool == EditorTool.MEASURE || tool == EditorTool.CALIBRATE -> {
                            segmentStart = eventPoint(event)
                            segmentEnd = segmentStart
                        }
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!stylus) {
                    movingPage = true; inProgress = null
                    segmentStart = null; segmentEnd = null
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touching) return false
                if (!stylus && event.pointerCount > 1) return true // pinch handled by scaler
                if (movingPage) {
                    panX += event.x - lastX; panY += event.y - lastY
                    lastX = event.x; lastY = event.y
                    layoutPage(); invalidate()
                    return true
                }
                inProgress?.let { pts ->
                    for (i in 0 until event.historySize) pts.add(eventPoint(event, i))
                    pts.add(eventPoint(event))
                }
                if (segmentStart != null) segmentEnd = eventPoint(event)
                invalidate()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                if (remainingIndex < event.pointerCount) {
                    lastX = event.getX(remainingIndex)
                    lastY = event.getY(remainingIndex)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!movingPage && !eraseGesture && event.pointerCount == 1) {
                    inProgress?.let { pts ->
                        pts.add(eventPoint(event))
                        val highlighter = tool == EditorTool.HIGHLIGHTER
                        onStroke(InkStroke(pts.toList(),
                            if (highlighter) Color.YELLOW else inkColor,
                            if (highlighter) 12f else 2.4f,
                            if (highlighter) 0.32f else 1f))
                    }
                    val a = segmentStart
                    if (a != null && (tool == EditorTool.MEASURE || tool == EditorTool.CALIBRATE)) {
                        val b = eventPoint(event)
                        val physicalLength = hypot(
                            (b.x - a.x) * pageWidthPt, (b.y - a.y) * pageHeightPt
                        )
                        if (physicalLength >= 2f) onSegment(Segment(a, b), tool == EditorTool.CALIBRATE)
                    }
                    if (tool == EditorTool.AREA && pageRect.contains(event.x, event.y)) {
                        val point = eventPoint(event)
                        val first = areaDraft.firstOrNull()
                        val closeEnough = first != null && areaDraft.size >= 3 &&
                            hypot((first.x - point.x) * pageRect.width(),
                                (first.y - point.y) * pageRect.height()) <
                                26f * resources.displayMetrics.density
                        if (closeEnough) {
                            onArea(AreaPolygon(areaDraft.toList()))
                            areaDraft.clear()
                        } else if (areaDraft.size < 2000) {
                            areaDraft.add(point)
                        }
                        invalidate()
                    }
                    if (tool == EditorTool.TEXT && pageRect.contains(event.x, event.y)) {
                        onTextTap(eventPoint(event))
                    }
                    if (tool == EditorTool.IMAGE && pageRect.contains(event.x, event.y)) {
                        onImageTap(eventPoint(event))
                    }
                }
                releaseGesture()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                releaseGesture()
                return true
            }
        }
        return true
    }

    private fun releaseGesture() {
        touching = false; movingPage = false; eraseGesture = false
        inProgress = null; segmentStart = null; segmentEnd = null
        parent?.requestDisallowInterceptTouchEvent(false)
        invalidate()
    }

    private fun eraseNear(x: Float, y: Float) {
        val tolerance = 18f * resources.displayMetrics.density
        val index = edits.strokes.indices.reversed().firstOrNull { i ->
            val pts = edits.strokes[i].points
            if (pts.size == 1) {
                val px = pageRect.left + pts[0].x * pageRect.width()
                val py = pageRect.top + pts[0].y * pageRect.height()
                hypot(px - x, py - y) < tolerance
            } else pts.zipWithNext().any { (a, b) ->
                val ax = pageRect.left + a.x * pageRect.width()
                val ay = pageRect.top + a.y * pageRect.height()
                val bx = pageRect.left + b.x * pageRect.width()
                val by = pageRect.top + b.y * pageRect.height()
                val dx = bx - ax; val dy = by - ay
                val len = dx * dx + dy * dy
                val t = if (len <= 0f) 0f else
                    (((x - ax) * dx + (y - ay) * dy) / len).coerceIn(0f, 1f)
                hypot(x - (ax + t * dx), y - (ay + t * dy)) < tolerance
            }
        }
        if (index != null) onEraseStroke(index)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return super.onHoverEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT ||
            !pageRect.contains(event.x, event.y)) {
            hoverX = Float.NaN; hoverY = Float.NaN
        } else { hoverX = event.x; hoverY = event.y }
        invalidate()
        return true
    }
}
