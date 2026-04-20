package com.lutpiero.rakaah

import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

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
 * Classifies a MediaPipe pose into one of the [PhysicalPose] categories
 * for a front-facing camera on the floor using normalized Y position + world Z depth cues.
 */
object PoseClassifier {

    private const val MIN_PRESENCE = 0.4f
    private const val STANDING_MIN_ANGLE = 145f
    private const val SITTING_MAX_ANGLE = 130f
    private const val BOWING_MIN_HIP_ANGLE = 80f
    private const val BOWING_MAX_HIP_ANGLE = 125f
    private const val BOWING_MIN_FORWARD_LEAN = 0.14f
    private const val BOWING_NOSE_ABOVE_HIP_MAX = 0.22f
    private const val PROSTRATING_NOSE_ABOVE_HIP_MAX = 0.05f
    private const val PROSTRATING_SHOULDER_ABOVE_HIP_MAX = 0.03f
    private const val SITTING_MIN_NOSE_ABOVE_HIP = 0.05f
    private const val STANDING_MIN_NOSE_ABOVE_HIP = 0.28f
    private const val STANDING_MIN_SHOULDER_ABOVE_HIP = 0.10f
    private const val STANDING_MIN_UPPER_BODY_RATIO = 0.45f
    // Highest index used by this classifier is RIGHT_ANKLE (28), so 29 landmarks are required.
    private const val REQUIRED_LANDMARK_COUNT = 29
    private const val NOSE = 0
    private const val LEFT_SHOULDER = 11
    private const val RIGHT_SHOULDER = 12
    private const val LEFT_HIP = 23
    private const val RIGHT_HIP = 24
    private const val LEFT_KNEE = 25
    private const val RIGHT_KNEE = 26
    private const val LEFT_ANKLE = 27
    private const val RIGHT_ANKLE = 28

    fun classify(
        worldLandmarks: List<Landmark>,
        normalizedLandmarks: List<NormalizedLandmark>
    ): PhysicalPose {
        if (worldLandmarks.size < REQUIRED_LANDMARK_COUNT || normalizedLandmarks.size < REQUIRED_LANDMARK_COUNT) {
            return PhysicalPose.UNKNOWN
        }

        val required = listOf(
            NOSE, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP,
            LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE
        )
        if (required.any { !isPresent(normalizedLandmarks[it]) }) return PhysicalPose.UNKNOWN

        val noseY = normalizedLandmarks[NOSE].y()
        val shoulderMidY = average(
            normalizedLandmarks[LEFT_SHOULDER].y(),
            normalizedLandmarks[RIGHT_SHOULDER].y()
        )
        val hipMidY = average(
            normalizedLandmarks[LEFT_HIP].y(),
            normalizedLandmarks[RIGHT_HIP].y()
        )
        val kneeMidY = average(
            normalizedLandmarks[LEFT_KNEE].y(),
            normalizedLandmarks[RIGHT_KNEE].y()
        )

        val noseAboveHip = hipMidY - noseY
        val shoulderAboveHip = hipMidY - shoulderMidY
        val upperBodyRatio = noseAboveHip / max(kneeMidY - noseY, 0.01f)

        val noseZ = worldLandmarks[NOSE].z()
        val hipMidZ = average(worldLandmarks[LEFT_HIP].z(), worldLandmarks[RIGHT_HIP].z())
        val forwardLean = hipMidZ - noseZ

        val hipAngle = average(
            jointAngle(worldLandmarks[LEFT_SHOULDER], worldLandmarks[LEFT_HIP], worldLandmarks[LEFT_KNEE]),
            jointAngle(worldLandmarks[RIGHT_SHOULDER], worldLandmarks[RIGHT_HIP], worldLandmarks[RIGHT_KNEE])
        )

        val kneeAngle = average(
            jointAngle(worldLandmarks[LEFT_HIP], worldLandmarks[LEFT_KNEE], worldLandmarks[LEFT_ANKLE]),
            jointAngle(worldLandmarks[RIGHT_HIP], worldLandmarks[RIGHT_KNEE], worldLandmarks[RIGHT_ANKLE])
        )

        return when {
            noseAboveHip <= PROSTRATING_NOSE_ABOVE_HIP_MAX &&
                shoulderAboveHip <= PROSTRATING_SHOULDER_ABOVE_HIP_MAX &&
                kneeAngle < STANDING_MIN_ANGLE ->
                PhysicalPose.PROSTRATING

            forwardLean >= BOWING_MIN_FORWARD_LEAN &&
                noseAboveHip <= BOWING_NOSE_ABOVE_HIP_MAX &&
                hipAngle in BOWING_MIN_HIP_ANGLE..BOWING_MAX_HIP_ANGLE &&
                kneeAngle >= STANDING_MIN_ANGLE ->
                PhysicalPose.BOWING

            kneeAngle < SITTING_MAX_ANGLE &&
                noseAboveHip > SITTING_MIN_NOSE_ABOVE_HIP &&
                noseAboveHip < STANDING_MIN_NOSE_ABOVE_HIP &&
                shoulderAboveHip > PROSTRATING_SHOULDER_ABOVE_HIP_MAX ->
                PhysicalPose.SITTING

            noseAboveHip >= STANDING_MIN_NOSE_ABOVE_HIP &&
                shoulderAboveHip >= STANDING_MIN_SHOULDER_ABOVE_HIP &&
                upperBodyRatio >= STANDING_MIN_UPPER_BODY_RATIO &&
                hipAngle >= STANDING_MIN_ANGLE &&
                kneeAngle >= STANDING_MIN_ANGLE ->
                PhysicalPose.STANDING

            else -> PhysicalPose.UNKNOWN
        }
    }

    private fun isPresent(landmark: NormalizedLandmark): Boolean {
        return landmark.presence().orElse(1f) >= MIN_PRESENCE
    }

    private fun jointAngle(a: Landmark, b: Landmark, c: Landmark): Float {
        val ba = Vec3(a.x() - b.x(), a.y() - b.y(), a.z() - b.z())
        val bc = Vec3(c.x() - b.x(), c.y() - b.y(), c.z() - b.z())
        return angleBetween(ba, bc)
    }

    private fun angleBetween(a: Vec3, b: Vec3): Float {
        val denom = a.magnitude() * b.magnitude()
        if (denom == 0f) return 0f
        val cosine = (a.dot(b) / denom).coerceIn(-1f, 1f)
        return acos(cosine) * (180f / PI.toFloat())
    }

    private fun average(a: Float, b: Float): Float = (a + b) / 2f

    private data class Vec3(val x: Float, val y: Float, val z: Float) {
        fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z
        fun magnitude(): Float = sqrt(x * x + y * y + z * z)
    }

}

/** Maps a [Movement] to a physical camera-detectable pose. */
fun movementToPhysicalPose(movement: Movement): PhysicalPose = when (movement) {
    Movement.QIYAM, Movement.ITIDAL -> PhysicalPose.STANDING
    Movement.RUKU                   -> PhysicalPose.BOWING
    Movement.SUJUD                  -> PhysicalPose.PROSTRATING
    Movement.JALSA                  -> PhysicalPose.SITTING
}
