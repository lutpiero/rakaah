package com.lutpiero.rakaah

import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
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
 * using 3D joint angles from world landmarks.
 */
object PoseClassifier {

    private const val MIN_PRESENCE = 0.4f
    private const val STANDING_MIN_ANGLE = 145f
    private const val SITTING_MAX_ANGLE = 130f
    private const val BOWING_TRUNK_MIN = 45f
    private const val PROSTRATING_TRUNK_MIN = 65f

    fun classify(
        worldLandmarks: List<Landmark>,
        normalizedLandmarks: List<NormalizedLandmark>
    ): PhysicalPose {
        if (worldLandmarks.size < REQUIRED_LANDMARK_COUNT || normalizedLandmarks.size < REQUIRED_LANDMARK_COUNT) {
            return PhysicalPose.UNKNOWN
        }

        val required = listOf(11, 12, 23, 24, 25, 26, 27, 28)
        if (required.any { !isPresent(normalizedLandmarks[it]) }) return PhysicalPose.UNKNOWN

        val shoulderMid = midpoint(worldLandmarks[11], worldLandmarks[12])
        val hipMid = midpoint(worldLandmarks[23], worldLandmarks[24])
        val vertical = Vec3(0f, 1f, 0f)
        val trunkInclination = angleBetween(hipMid - shoulderMid, vertical)

        val hipAngle = average(
            jointAngle(worldLandmarks[11], worldLandmarks[23], worldLandmarks[25]),
            jointAngle(worldLandmarks[12], worldLandmarks[24], worldLandmarks[26])
        )

        val kneeAngle = average(
            jointAngle(worldLandmarks[23], worldLandmarks[25], worldLandmarks[27]),
            jointAngle(worldLandmarks[24], worldLandmarks[26], worldLandmarks[28])
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
        return angleBetween(
            Vec3(a.x() - b.x(), a.y() - b.y(), a.z() - b.z()),
            Vec3(c.x() - b.x(), c.y() - b.y(), c.z() - b.z())
        )
    }

    private fun angleBetween(a: Vec3, b: Vec3): Float {
        val denom = a.magnitude() * b.magnitude()
        if (denom == 0f) return 0f
        val cosine = (a.dot(b) / denom).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    private fun average(a: Float, b: Float): Float = (a + b) / 2f

    private data class Vec3(val x: Float, val y: Float, val z: Float) {
        fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z
        fun magnitude(): Float = sqrt(x * x + y * y + z * z)
        operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)
    }

    private const val REQUIRED_LANDMARK_COUNT = 29
}

/** Maps a [Movement] to a physical camera-detectable pose. */
fun movementToPhysicalPose(movement: Movement): PhysicalPose = when (movement) {
    Movement.QIYAM, Movement.ITIDAL -> PhysicalPose.STANDING
    Movement.RUKU                   -> PhysicalPose.BOWING
    Movement.SUJUD                  -> PhysicalPose.PROSTRATING
    Movement.JALSA                  -> PhysicalPose.SITTING
}
