package com.lutpiero.rakaah

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.max

/**
 * Maps a prayer movement name to a physical pose category detectable from the camera.
 */
enum class PhysicalPose {
    STANDING,    // Qiyam / I'tidal
    BOWING,      // Ruku
    PROSTRATING, // Sujud
    SITTING,     // Jalsa
    UNKNOWN
}

/**
 * Classifies an ML Kit [Pose] into one of the [PhysicalPose] categories
 * using landmark geometry.
 *
 * Coordinate system: image coordinates where y increases downward.
 */
object PoseClassifier {

    /** Minimum in-frame confidence required for each landmark. */
    private const val MIN_CONFIDENCE = 0.5f

    fun classify(pose: Pose): PhysicalPose {
        val nose = landmark(pose, PoseLandmark.NOSE) ?: return PhysicalPose.UNKNOWN
        val lShoulder = landmark(pose, PoseLandmark.LEFT_SHOULDER) ?: return PhysicalPose.UNKNOWN
        val rShoulder = landmark(pose, PoseLandmark.RIGHT_SHOULDER) ?: return PhysicalPose.UNKNOWN
        val lHip = landmark(pose, PoseLandmark.LEFT_HIP) ?: return PhysicalPose.UNKNOWN
        val rHip = landmark(pose, PoseLandmark.RIGHT_HIP) ?: return PhysicalPose.UNKNOWN
        val lKnee = landmark(pose, PoseLandmark.LEFT_KNEE) ?: return PhysicalPose.UNKNOWN
        val rKnee = landmark(pose, PoseLandmark.RIGHT_KNEE) ?: return PhysicalPose.UNKNOWN

        val shoulderY = (lShoulder.position.y + rShoulder.position.y) / 2f
        val hipY = (lHip.position.y + rHip.position.y) / 2f
        val kneeY = (lKnee.position.y + rKnee.position.y) / 2f
        val noseY = nose.position.y

        // Distances (positive = downward in image)
        val trunkLen = hipY - shoulderY          // large when standing upright
        val legLen = kneeY - hipY                 // distance from hip to knee
        val bodyHeight = max(kneeY - noseY, 1f)  // overall visible body height

        return when {
            // Sujud: nose has descended to near or below hip level (prostration)
            noseY >= hipY - trunkLen * 0.25f -> PhysicalPose.PROSTRATING

            // Ruku: trunk is compressed — shoulders are close to hip height
            trunkLen < bodyHeight * 0.20f -> PhysicalPose.BOWING

            // Jalsa: sitting — thigh is nearly vertical so kneeY ≈ hipY
            abs(legLen) < trunkLen * 0.40f -> PhysicalPose.SITTING

            // Default: upright standing
            else -> PhysicalPose.STANDING
        }
    }

    /** Returns the [PoseLandmark] only if it meets the minimum confidence threshold. */
    private fun landmark(pose: Pose, type: Int): PoseLandmark? {
        val lm = pose.getPoseLandmark(type) ?: return null
        return if (lm.inFrameLikelihood >= MIN_CONFIDENCE) lm else null
    }
}

/** Maps a [Movement] to a physical camera-detectable pose. */
fun movementToPhysicalPose(movement: Movement): PhysicalPose = when (movement) {
    Movement.QIYAM, Movement.ITIDAL -> PhysicalPose.STANDING
    Movement.RUKU                   -> PhysicalPose.BOWING
    Movement.SUJUD                  -> PhysicalPose.PROSTRATING
    Movement.JALSA                  -> PhysicalPose.SITTING
}
