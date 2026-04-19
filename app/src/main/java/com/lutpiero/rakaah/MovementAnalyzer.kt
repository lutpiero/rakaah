package com.lutpiero.rakaah

import android.annotation.SuppressLint
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
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
    private val onPoseChanged: (PhysicalPose) -> Unit
) : ImageAnalysis.Analyzer {

    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
    )

    private val candidatePose = AtomicReference(PhysicalPose.UNKNOWN)
    private val candidateSince = AtomicLong(0L)
    private val currentPose = AtomicReference(PhysicalPose.UNKNOWN)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run { imageProxy.close(); return }
        val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

        detector.process(inputImage)
            .addOnSuccessListener { pose -> handlePose(pose) }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun handlePose(pose: Pose) {
        val detected = PoseClassifier.classify(pose)

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
    }
}
