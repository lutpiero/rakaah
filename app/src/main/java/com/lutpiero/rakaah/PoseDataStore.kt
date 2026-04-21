package com.lutpiero.rakaah

import android.content.Context
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.math.sqrt

class PoseDataStore(context: Context) {

    enum class PrayerPose(
        val id: String,
        val labelResId: Int,
        val physicalPose: PhysicalPose
    ) {
        QIYAM_START("qiyam_start", R.string.prayer_pose_qiyam_start, PhysicalPose.STANDING),
        TAKBIR("takbir", R.string.prayer_pose_takbir, PhysicalPose.STANDING),
        QIYAM_AFTER_TAKBIR("qiyam_after_takbir", R.string.prayer_pose_qiyam_after_takbir, PhysicalPose.STANDING),
        RUKU("ruku", R.string.prayer_pose_ruku, PhysicalPose.BOWING),
        ITIDAL("itidal", R.string.prayer_pose_itidal, PhysicalPose.STANDING),
        SUJUD_FIRST("sujud_first", R.string.prayer_pose_sujud_first, PhysicalPose.PROSTRATING),
        JALSA("jalsa", R.string.prayer_pose_jalsa, PhysicalPose.SITTING),
        SUJUD_SECOND("sujud_second", R.string.prayer_pose_sujud_second, PhysicalPose.PROSTRATING),
        TASHAHHUD("tashahhud", R.string.prayer_pose_tashahhud, PhysicalPose.SITTING),
        SALAM("salam", R.string.prayer_pose_salam, PhysicalPose.SITTING);

        companion object {
            fun fromId(id: String): PrayerPose? = entries.firstOrNull { it.id == id }
        }
    }

    data class LandmarkSample(
        val x: Float,
        val y: Float,
        val z: Float,
        val visibility: Float? = null,
        val presence: Float? = null
    )

    data class StoredPoseSample(
        val poseId: String,
        val physicalPose: PhysicalPose,
        val capturedAtMs: Long,
        val normalizedLandmarks: List<LandmarkSample>,
        val worldLandmarks: List<LandmarkSample>
    )

    private val poseDir = File(context.filesDir, "$PERSONALIZED_POSE_DIR/$LOCAL_PROFILE_ID").apply {
        if (!exists()) mkdirs()
    }

    fun savePoseSample(
        pose: PrayerPose,
        normalizedLandmarks: List<NormalizedLandmark>,
        worldLandmarks: List<Landmark>
    ): Result<Unit> = runCatching {
        val normalizedSamples = normalizedLandmarks.map {
            LandmarkSample(
                x = it.x(),
                y = it.y(),
                z = it.z(),
                visibility = it.visibility().orElse(null),
                presence = it.presence().orElse(null)
            )
        }
        val worldSamples = worldLandmarks.map {
            LandmarkSample(
                x = it.x(),
                y = it.y(),
                z = it.z(),
                visibility = it.visibility().orElse(null),
                presence = it.presence().orElse(null)
            )
        }

        val json = JSONObject()
            .put("poseId", pose.id)
            .put("physicalPose", pose.physicalPose.name)
            .put("capturedAtMs", System.currentTimeMillis())
            .put("normalizedLandmarks", normalizedSamples.toJsonArray())
            .put("worldLandmarks", worldSamples.toJsonArray())

        val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_FILE_SIZE_BYTES) {
            "Pose sample is too large (${bytes.size} bytes)"
        }

        val target = poseFile(pose)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            error("Failed to persist pose sample")
        }
    }

    fun deletePoseSample(pose: PrayerPose): Boolean {
        val file = poseFile(pose)
        return !file.exists() || file.delete()
    }

    fun hasCustomPose(pose: PrayerPose): Boolean {
        val file = poseFile(pose)
        return file.exists() && file.length() in 1..MAX_FILE_SIZE_BYTES
    }

    fun loadAllSamples(): List<StoredPoseSample> {
        return PrayerPose.entries.mapNotNull { loadPoseSample(it) }
    }

    private fun loadPoseSample(pose: PrayerPose): StoredPoseSample? {
        val file = poseFile(pose)
        if (!file.exists() || file.length() <= 0L || file.length() > MAX_FILE_SIZE_BYTES) {
            return null
        }
        return runCatching {
            val json = JSONObject(file.readText())
            val physicalPose = runCatching {
                PhysicalPose.valueOf(json.optString("physicalPose", pose.physicalPose.name))
            }.getOrDefault(pose.physicalPose)
            StoredPoseSample(
                poseId = json.optString("poseId", pose.id),
                physicalPose = physicalPose,
                capturedAtMs = json.optLong("capturedAtMs", 0L),
                normalizedLandmarks = parseLandmarks(json.optJSONArray("normalizedLandmarks")),
                worldLandmarks = parseLandmarks(json.optJSONArray("worldLandmarks"))
            )
        }.getOrNull()
    }

    private fun parseLandmarks(array: JSONArray?): List<LandmarkSample> {
        if (array == null) return emptyList()
        val result = ArrayList<LandmarkSample>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            result += LandmarkSample(
                x = item.optDouble("x", 0.0).toFloat(),
                y = item.optDouble("y", 0.0).toFloat(),
                z = item.optDouble("z", 0.0).toFloat(),
                visibility = item.optDouble("visibility", Double.NaN).takeIf { !it.isNaN() }?.toFloat(),
                presence = item.optDouble("presence", Double.NaN).takeIf { !it.isNaN() }?.toFloat()
            )
        }
        return result
    }

    private fun List<LandmarkSample>.toJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { landmark ->
                array.put(
                    JSONObject()
                        .put("x", landmark.x)
                        .put("y", landmark.y)
                        .put("z", landmark.z)
                        .apply {
                            landmark.visibility?.let { put("visibility", it) }
                            landmark.presence?.let { put("presence", it) }
                        }
                )
            }
        }
    }

    private fun poseFile(pose: PrayerPose): File = File(poseDir, "pose_${pose.id}.json")

    companion object {
        private const val PERSONALIZED_POSE_DIR = "personalized_poses"
        private const val LOCAL_PROFILE_ID = "default_local_profile"
        internal const val MAX_FILE_SIZE_BYTES = 256 * 1024L
    }
}

class PersonalizedPoseMatcher(samples: List<PoseDataStore.StoredPoseSample>) {
    private val samplesByPose = samples.filter { it.normalizedLandmarks.isNotEmpty() }

    fun classify(normalizedLandmarks: List<NormalizedLandmark>): PhysicalPose? {
        if (normalizedLandmarks.isEmpty() || samplesByPose.isEmpty()) return null
        var bestPose: PhysicalPose? = null
        var bestDistance = Float.MAX_VALUE

        samplesByPose.forEach { sample ->
            val comparedCount = minOf(sample.normalizedLandmarks.size, normalizedLandmarks.size)
            if (comparedCount < MIN_LANDMARK_COUNT) return@forEach
            var sumSquaredDistance = 0f
            for (index in 0 until comparedCount) {
                val sampleLandmark = sample.normalizedLandmarks[index]
                val liveLandmark = normalizedLandmarks[index]
                val deltaX = sampleLandmark.x - liveLandmark.x()
                val deltaY = sampleLandmark.y - liveLandmark.y()
                sumSquaredDistance += (deltaX * deltaX) + (deltaY * deltaY)
            }
            val rmsDistance = sqrt(sumSquaredDistance / comparedCount)
            if (rmsDistance < bestDistance) {
                bestDistance = rmsDistance
                bestPose = sample.physicalPose
            }
        }

        return if (bestDistance <= MATCH_THRESHOLD) bestPose else null
    }

    companion object {
        private const val MIN_LANDMARK_COUNT = 12
        private const val MATCH_THRESHOLD = 0.12f
    }
}
