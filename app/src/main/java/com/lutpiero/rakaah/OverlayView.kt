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

    fun updatePose(
        landmarks: List<NormalizedLandmark>,
        connections: Collection<Connection>,
        isMirrored: Boolean
    ) {
        this.landmarks = landmarks
        this.connections = connections.toList()
        this.isMirrored = isMirrored
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (landmarks.isEmpty()) return

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

    private fun mapX(normalizedX: Float): Float {
        val x = if (isMirrored) 1f - normalizedX else normalizedX
        return x * width
    }

    private fun mapY(normalizedY: Float): Float = normalizedY * height

    companion object {
        private const val POINT_RADIUS_RATIO = 0.008f
    }
}
