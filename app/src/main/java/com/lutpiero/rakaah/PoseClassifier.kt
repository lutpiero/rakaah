package com.lutpiero.rakaah

import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.PI
import kotlin.math.acos
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

    private const val MIN_PRESENCE = 0.2f
    private const val STANDING_MIN_KNEE_ANGLE = 140f
    private const val SITTING_MAX_KNEE_ANGLE = 120f
    private const val STANDING_MIN_NOSE_ABOVE_HIP = 0.15f
    private const val BOWING_MIN_NOSE_ABOVE_HIP = 0.03f
    private const val BOWING_MAX_NOSE_ABOVE_HIP = 0.15f
    private const val SITTING_MIN_NOSE_ABOVE_HIP = 0.08f
    private const val SITTING_MAX_NOSE_ABOVE_HIP = 0.20f
    private const val PROSTRATING_MAX_NOSE_ABOVE_HIP = 0.03f
    private const val PROSTRATING_MIN_NOSE_Y = 0.80f
    private const val BOWING_MAX_SHOULDER_ABOVE_HIP = 0.10f
    private const val STANDING_MIN_BODY_HEIGHT = 0.45f
    private const val SITTING_MAX_BODY_HEIGHT = 0.42f
    private const val MIN_BODY_SEGMENT_HEIGHT = 0.05f
    private const val MIN_VISIBLE_LANDMARKS_FOR_FULL_CLASSIFICATION = 7
    private const val PROSTRATING_FALLBACK_NOSE_Y = 0.72f
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
        if (normalizedLandmarks.size <= NOSE) {
            return PhysicalPose.UNKNOWN
        }
        if (worldLandmarks.size < REQUIRED_LANDMARK_COUNT || normalizedLandmarks.size < REQUIRED_LANDMARK_COUNT) {
            return classifyByLandmarkCount(normalizedLandmarks)
        }

        val required = listOf(
            NOSE, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP,
            LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE
        )
        if (required.any { !isPresent(normalizedLandmarks[it]) }) {
            return classifyByLandmarkCount(normalizedLandmarks)
        }

        val noseY = normalizedLandmarks[NOSE].y()
        val shoulderMidY = average(
            normalizedLandmarks[LEFT_SHOULDER].y(),
            normalizedLandmarks[RIGHT_SHOULDER].y()
        )
        val hipMidY = average(
            normalizedLandmarks[LEFT_HIP].y(),
            normalizedLandmarks[RIGHT_HIP].y()
        )
        val ankleMidY = average(
            normalizedLandmarks[LEFT_ANKLE].y(),
            normalizedLandmarks[RIGHT_ANKLE].y()
        )

        val noseAboveHip = hipMidY - noseY
        val shoulderAboveHip = hipMidY - shoulderMidY
        // MediaPipe normalized Y grows downward in-frame, so ankle Y is expected to be > nose Y.
        val totalBodyHeight = ankleMidY - noseY
        if (totalBodyHeight <= MIN_BODY_SEGMENT_HEIGHT) return PhysicalPose.UNKNOWN

        val kneeAngle = average(
            jointAngle(worldLandmarks[LEFT_HIP], worldLandmarks[LEFT_KNEE], worldLandmarks[LEFT_ANKLE]),
            jointAngle(worldLandmarks[RIGHT_HIP], worldLandmarks[RIGHT_KNEE], worldLandmarks[RIGHT_ANKLE])
        )

        return when {
            noseY >= PROSTRATING_MIN_NOSE_Y || noseAboveHip <= PROSTRATING_MAX_NOSE_ABOVE_HIP ->
                PhysicalPose.PROSTRATING

            noseAboveHip in BOWING_MIN_NOSE_ABOVE_HIP..BOWING_MAX_NOSE_ABOVE_HIP &&
                shoulderAboveHip <= BOWING_MAX_SHOULDER_ABOVE_HIP &&
                kneeAngle >= STANDING_MIN_KNEE_ANGLE ->
                PhysicalPose.BOWING

            kneeAngle < SITTING_MAX_KNEE_ANGLE &&
                noseAboveHip > SITTING_MIN_NOSE_ABOVE_HIP &&
                noseAboveHip < SITTING_MAX_NOSE_ABOVE_HIP &&
                totalBodyHeight <= SITTING_MAX_BODY_HEIGHT ->
                PhysicalPose.SITTING

            noseAboveHip >= STANDING_MIN_NOSE_ABOVE_HIP &&
                totalBodyHeight >= STANDING_MIN_BODY_HEIGHT &&
                kneeAngle >= STANDING_MIN_KNEE_ANGLE ->
                PhysicalPose.STANDING

            else -> PhysicalPose.UNKNOWN
        }
    }

    private fun classifyByLandmarkCount(normalizedLandmarks: List<NormalizedLandmark>): PhysicalPose {
        val visibleCount = normalizedLandmarks.count { it.presence().orElse(0f) >= MIN_PRESENCE }
        val nose = normalizedLandmarks.getOrNull(NOSE)
        val noseIsVisible = nose?.let(::isPresent) == true
        val noseY = nose?.y() ?: 0f
        return if (visibleCount < MIN_VISIBLE_LANDMARKS_FOR_FULL_CLASSIFICATION &&
            noseIsVisible &&
            noseY >= PROSTRATING_FALLBACK_NOSE_Y
        ) {
            PhysicalPose.PROSTRATING
        } else {
            PhysicalPose.UNKNOWN
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
