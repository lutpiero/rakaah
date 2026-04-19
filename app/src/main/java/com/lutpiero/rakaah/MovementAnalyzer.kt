package com.lutpiero.rakaah

import android.annotation.SuppressLint
import android.content.Context
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.MediaImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
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

    private val detector = PoseLandmarker.createFromOptions(
        context,
        PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET_NAME)
                    .build()
            )
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(::handlePoseResult)
            .setErrorListener {
                onFrameResult(PhysicalPose.UNKNOWN, emptyList())
            }
            .build()
    )

    private val candidatePose = AtomicReference(PhysicalPose.UNKNOWN)
    private val candidateSince = AtomicLong(0L)
    private val currentPose = AtomicReference(PhysicalPose.UNKNOWN)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            // No image data in this frame; close the proxy to unblock the pipeline.
            imageProxy.close()
            return
        }

        val mpImage = MediaImageBuilder(mediaImage).build()
        val imageProcessingOptions = ImageProcessingOptions.builder()
            .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
            .build()
        val timestampMs = imageProxy.imageInfo.timestamp / 1_000_000L
        detector.detectAsync(mpImage, imageProcessingOptions, timestampMs)
        imageProxy.close()
    }

    private fun handlePoseResult(
        result: PoseLandmarkerResult,
        _: com.google.mediapipe.framework.image.MPImage
    ) {
        val normalizedLandmarks = result.landmarks().firstOrNull().orEmpty()
        val worldLandmarks = result.worldLandmarks().firstOrNull().orEmpty()
        val detected = PoseClassifier.classify(worldLandmarks, normalizedLandmarks)
        onFrameResult(detected, normalizedLandmarks)

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
        detector.close()
    }

    companion object {
        /** Minimum time (ms) a pose must be held before it is considered stable. */
        private const val HOLD_DURATION_MS = 600L
        private const val MODEL_ASSET_NAME = "pose_landmarker_lite.task"
    }
}
