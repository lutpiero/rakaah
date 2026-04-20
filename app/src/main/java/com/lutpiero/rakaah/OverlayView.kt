package com.lutpiero.rakaah

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.Connection
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.min

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pointPaint = Paint().apply {
        color = Color.CYAN
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val linePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    private var landmarks: List<NormalizedLandmark> = emptyList()
    private var connections: List<Connection> = emptyList()
    private var isMirrored = true
    private var imageWidth = 0
    private var imageHeight = 0
    private var drawScale = 1f
    private var drawOffsetX = 0f
    private var drawOffsetY = 0f
    private var transformDirty = true

    fun updatePose(
        landmarks: List<NormalizedLandmark>,
        connections: Collection<Connection>,
        isMirrored: Boolean,
        imageWidth: Int,
        imageHeight: Int
    ) {
        this.landmarks = landmarks
        this.connections = connections.toList()
        this.isMirrored = isMirrored
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        transformDirty = true
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarks.isEmpty()) return

        ensureTransform()

        val radius = min(width, height) * POINT_RADIUS_RATIO

        connections.forEach { connection ->
            val start = landmarks.getOrNull(connection.start()) ?: return@forEach
            val end = landmarks.getOrNull(connection.end()) ?: return@forEach
            canvas.drawLine(
                mapX(start.x()),
                mapY(start.y()),
                mapX(end.x()),
                mapY(end.y()),
                linePaint
            )
        }

        landmarks.forEach { landmark ->
            canvas.drawCircle(
                mapX(landmark.x()),
                mapY(landmark.y()),
                radius,
                pointPaint
            )
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        transformDirty = true
    }

    private fun ensureTransform() {
        if (!transformDirty) return
        val (scale, offsetX, offsetY) = computeTransform()
        drawScale = scale
        drawOffsetX = offsetX
        drawOffsetY = offsetY
        transformDirty = false
    }

    private fun computeTransform(): Triple<Float, Float, Float> {
        if (imageWidth == 0 || imageHeight == 0 || width == 0 || height == 0) {
            return Triple(1f, 0f, 0f)
        }

        val viewAspect = width.toFloat() / height.toFloat()
        val imageAspect = imageWidth.toFloat() / imageHeight.toFloat()

        return if (imageAspect > viewAspect) {
            val scale = height.toFloat() / imageHeight.toFloat()
            val offsetX = (width - imageWidth * scale) / 2f
            Triple(scale, offsetX, 0f)
        } else {
            val scale = width.toFloat() / imageWidth.toFloat()
            val offsetY = (height - imageHeight * scale) / 2f
            Triple(scale, 0f, offsetY)
        }
    }

    private fun mapX(normalizedX: Float): Float {
        val x = if (isMirrored) 1f - normalizedX else normalizedX
        if (imageWidth == 0) return x * width
        return x * imageWidth * drawScale + drawOffsetX
    }

    private fun mapY(normalizedY: Float): Float {
        if (imageHeight == 0) return normalizedY * height
        return normalizedY * imageHeight * drawScale + drawOffsetY
    }

    companion object {
        private const val POINT_RADIUS_RATIO = 0.008f
    }
}
