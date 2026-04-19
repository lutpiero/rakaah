package com.lutpiero.rakaah

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * CameraX [ImageAnalysis.Analyzer] that detects prayer movements from each camera frame.
 *
 * A pose must be held for [HOLD_DURATION_MS] before [onPoseChanged] is fired, which
 * prevents transient frames from triggering spurious state advances.
 *
 * @param onPoseChanged called on the calling thread (analysis executor) with the
 *                      newly-stable [PhysicalPose] each time the pose changes.
 */
class MovementAnalyzer(
    context: Context,
    private val onPoseChanged: (PhysicalPose) -> Unit,
    private val onFrameResult: (PhysicalPose, List<NormalizedLandmark>) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector: PoseLandmarker? = try {
        PoseLandmarker.createFromOptions(
            context,
            PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_ASSET_NAME)
                        .build()
                )
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(::handlePoseResult)
                .setErrorListener { error ->
                    Log.e(TAG, "PoseLandmarker error", error)
                    onFrameResult(PhysicalPose.UNKNOWN, emptyList())
                }
                .build()
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize PoseLandmarker", e)
        null
    }

    private val candidatePose = AtomicReference(PhysicalPose.UNKNOWN)
    private val candidateSince = AtomicLong(0L)
    private val currentPose = AtomicReference(PhysicalPose.UNKNOWN)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val detector = detector
        if (detector == null) {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxyToBitmap(imageProxy) ?: run {
                Log.e(TAG, "Failed to convert frame to bitmap")
                return
            }
            val mpImage = BitmapImageBuilder(bitmap).build()
            val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000L
            detector.detectAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            Log.e(TAG, "Frame analysis failed", e)
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        if (imageProxy.format != ImageFormat.YUV_420_888 || imageProxy.planes.size < 3) {
            return null
        }

        val yBuffer = imageProxy.planes[0].buffer
        val uBuffer = imageProxy.planes[1].buffer
        val vBuffer = imageProxy.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun handlePoseResult(
        result: PoseLandmarkerResult,
        @Suppress("UNUSED_PARAMETER") inputImage: com.google.mediapipe.framework.image.MPImage
    ) {
        val normalizedPoseLandmarks = result.landmarks().firstOrNull().orEmpty()
        val worldPoseLandmarks = result.worldLandmarks().firstOrNull().orEmpty()
        val detected = PoseClassifier.classify(worldPoseLandmarks, normalizedPoseLandmarks)
        onFrameResult(detected, normalizedPoseLandmarks)

        val now = System.currentTimeMillis()
        val prev = candidatePose.get()

        if (detected != prev) {
            // New candidate — reset hold timer
            candidatePose.set(detected)
            candidateSince.set(now)
            return
        }

        // Same candidate — check if held long enough
        if (detected == PhysicalPose.UNKNOWN) return
        if (now - candidateSince.get() < HOLD_DURATION_MS) return

        // Stable new pose confirmed
        val current = currentPose.get()
        if (detected != current) {
            currentPose.set(detected)
            onPoseChanged(detected)
        }
    }

    fun close() {
        detector?.close()
    }

    companion object {
        /** Minimum time (ms) a pose must be held before it is considered stable. */
        private const val HOLD_DURATION_MS = 600L
        /** MediaPipe pose model file in app/src/main/assets/. */
        private const val MODEL_ASSET_NAME = "pose_landmarker_lite.task"
        private const val TAG = "MovementAnalyzer"
    }
}
