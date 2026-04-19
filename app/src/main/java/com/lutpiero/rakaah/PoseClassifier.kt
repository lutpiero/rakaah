package com.lutpiero.rakaah

import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.min
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
 * using 3D joint angles from world landmarks.
 */
object PoseClassifier {

    private const val MIN_PRESENCE = 0.4f
    private const val STANDING_MIN_ANGLE = 145f
    private const val SITTING_MAX_ANGLE = 130f
    private const val BOWING_TRUNK_MIN = 45f
    private const val PROSTRATING_TRUNK_MIN = 65f
    // Highest index used by this classifier is RIGHT_ANKLE (28), so 29 landmarks are required.
    private const val REQUIRED_LANDMARK_COUNT = 29
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
            LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP,
            LEFT_KNEE, RIGHT_KNEE, LEFT_ANKLE, RIGHT_ANKLE
        )
        if (required.any { !isPresent(normalizedLandmarks[it]) }) return PhysicalPose.UNKNOWN

        val shoulderMid = midpoint(worldLandmarks[LEFT_SHOULDER], worldLandmarks[RIGHT_SHOULDER])
        val hipMid = midpoint(worldLandmarks[LEFT_HIP], worldLandmarks[RIGHT_HIP])
        val verticalAxis = Vec3(0f, 1f, 0f)
        val rawTrunkAngle = angleBetween(hipMid - shoulderMid, verticalAxis)
        val trunkInclination = min(rawTrunkAngle, 180f - rawTrunkAngle)

        val hipAngle = average(
            jointAngle(worldLandmarks[LEFT_SHOULDER], worldLandmarks[LEFT_HIP], worldLandmarks[LEFT_KNEE]),
            jointAngle(worldLandmarks[RIGHT_SHOULDER], worldLandmarks[RIGHT_HIP], worldLandmarks[RIGHT_KNEE])
        )

        val kneeAngle = average(
            jointAngle(worldLandmarks[LEFT_HIP], worldLandmarks[LEFT_KNEE], worldLandmarks[LEFT_ANKLE]),
            jointAngle(worldLandmarks[RIGHT_HIP], worldLandmarks[RIGHT_KNEE], worldLandmarks[RIGHT_ANKLE])
        )

        return when {
            trunkInclination >= PROSTRATING_TRUNK_MIN && kneeAngle < STANDING_MIN_ANGLE ->
                PhysicalPose.PROSTRATING

            trunkInclination >= BOWING_TRUNK_MIN && kneeAngle >= STANDING_MIN_ANGLE ->
                PhysicalPose.BOWING

            trunkInclination < BOWING_TRUNK_MIN &&
                hipAngle < SITTING_MAX_ANGLE &&
                kneeAngle < SITTING_MAX_ANGLE ->
                PhysicalPose.SITTING

            hipAngle >= STANDING_MIN_ANGLE && kneeAngle >= STANDING_MIN_ANGLE ->
                PhysicalPose.STANDING

            else -> PhysicalPose.UNKNOWN
        }
    }

    private fun isPresent(landmark: NormalizedLandmark): Boolean {
        return landmark.presence().orElse(1f) >= MIN_PRESENCE
    }

    private fun midpoint(a: Landmark, b: Landmark): Vec3 = Vec3(
        x = (a.x() + b.x()) / 2f,
        y = (a.y() + b.y()) / 2f,
        z = (a.z() + b.z()) / 2f
    )

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
        operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    }

}

/** Maps a [Movement] to a physical camera-detectable pose. */
fun movementToPhysicalPose(movement: Movement): PhysicalPose = when (movement) {
    Movement.QIYAM, Movement.ITIDAL -> PhysicalPose.STANDING
    Movement.RUKU                   -> PhysicalPose.BOWING
    Movement.SUJUD                  -> PhysicalPose.PROSTRATING
    Movement.JALSA                  -> PhysicalPose.SITTING
}
