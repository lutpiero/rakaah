package com.lutpiero.rakaah

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
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
import java.util.concurrent.atomic.AtomicInteger
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
    private val onFrameResult: (PhysicalPose, List<NormalizedLandmark>, Int, Int) -> Unit
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
                    onFrameResult(PhysicalPose.UNKNOWN, emptyList(), 0, 0)
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
    private val sujudCandidateSince = AtomicLong(0L)
    private val latestImageWidth = AtomicInteger(0)
    private val latestImageHeight = AtomicInteger(0)

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
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees.toFloat()
            val shouldRotate = rotationDegrees != 0f
            val rotatedBitmap = if (shouldRotate) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }
            if (shouldRotate) {
                bitmap.recycle()
            }
            latestImageWidth.set(rotatedBitmap.width)
            latestImageHeight.set(rotatedBitmap.height)
            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
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

        val width = imageProxy.width
        val height = imageProxy.height
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yRowStride = yPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride

        val nv21 = ByteArray(width * height * NV21_SIZE_NUMERATOR / NV21_SIZE_DENOMINATOR)

        var pos = 0
        if (yRowStride == width) {
            yBuffer.position(0)
            yBuffer.get(nv21, 0, width * height)
            pos = width * height
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }

        val uvHeight = height / 2
        val uvWidth = width / 2
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val vIndex = row * vRowStride + col * vPixelStride
                val uIndex = row * uRowStride + col * uPixelStride
                vBuffer.position(vIndex)
                nv21[pos++] = vBuffer.get()
                uBuffer.position(uIndex)
                nv21[pos++] = uBuffer.get()
            }
        }

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val bytes = ByteArrayOutputStream().use { out ->
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
            out.toByteArray()
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun handlePoseResult(
        result: PoseLandmarkerResult,
        @Suppress("UNUSED_PARAMETER") inputImage: com.google.mediapipe.framework.image.MPImage
    ) {
        val now = System.currentTimeMillis()
        val normalizedPoseLandmarks = result.landmarks().firstOrNull().orEmpty()
        val worldPoseLandmarks = result.worldLandmarks().firstOrNull().orEmpty()
        val detected = PoseClassifier.classify(worldPoseLandmarks, normalizedPoseLandmarks)
        val effectiveDetected = maybePromoteUnknownToProstrating(detected, normalizedPoseLandmarks, now)
        onFrameResult(
            effectiveDetected,
            normalizedPoseLandmarks,
            latestImageWidth.get(),
            latestImageHeight.get()
        )

        val prev = candidatePose.get()

        if (effectiveDetected != prev) {
            // New candidate — reset hold timer
            candidatePose.set(effectiveDetected)
            candidateSince.set(now)
            return
        }

        // Same candidate — check if held long enough
        if (effectiveDetected == PhysicalPose.UNKNOWN) return
        if (now - candidateSince.get() < HOLD_DURATION_MS) return

        // Stable new pose confirmed
        val current = currentPose.get()
        if (effectiveDetected != current) {
            currentPose.set(effectiveDetected)
            onPoseChanged(effectiveDetected)
        }
    }

    private fun maybePromoteUnknownToProstrating(
        detected: PhysicalPose,
        normalizedPoseLandmarks: List<NormalizedLandmark>,
        now: Long
    ): PhysicalPose {
        if (detected != PhysicalPose.UNKNOWN) {
            sujudCandidateSince.set(0L)
            return detected
        }

        if (!hasInsufficientLandmarks(normalizedPoseLandmarks)) {
            sujudCandidateSince.set(0L)
            return PhysicalPose.UNKNOWN
        }

        val previousStablePose = currentPose.get()
        if (previousStablePose != PhysicalPose.BOWING && previousStablePose != PhysicalPose.SITTING) {
            sujudCandidateSince.set(0L)
            return PhysicalPose.UNKNOWN
        }

        val candidateStart = sujudCandidateSince.get()
        if (candidateStart == 0L) {
            sujudCandidateSince.set(now)
            return PhysicalPose.UNKNOWN
        }

        return if (now - candidateStart >= SUJUD_UNKNOWN_TIMEOUT_MS) {
            PhysicalPose.PROSTRATING
        } else {
            PhysicalPose.UNKNOWN
        }
    }

    private fun hasInsufficientLandmarks(landmarks: List<NormalizedLandmark>): Boolean {
        val visibleLandmarks = landmarks.count { it.presence().orElse(0f) >= MIN_LANDMARK_PRESENCE }
        return visibleLandmarks < REQUIRED_LANDMARK_COUNT
    }

    fun close() {
        detector?.close()
    }

    companion object {
        /** Minimum time (ms) a pose must be held before it is considered stable. */
        private const val HOLD_DURATION_MS = 600L
        private const val SUJUD_UNKNOWN_TIMEOUT_MS = 400L
        private const val REQUIRED_LANDMARK_COUNT = 29
        private const val MIN_LANDMARK_PRESENCE = 0.2f
        private const val NV21_SIZE_NUMERATOR = 3
        private const val NV21_SIZE_DENOMINATOR = 2
        /** MediaPipe pose model file in app/src/main/assets/. */
        private const val MODEL_ASSET_NAME = "pose_landmarker_lite.task"
        private const val TAG = "MovementAnalyzer"
    }
}
