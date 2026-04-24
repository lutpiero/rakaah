package com.lutpiero.rakaah

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.Surface
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlin.math.sqrt

class PoseRecordingActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var instructionText: TextView
    private lateinit var statusText: TextView
    private lateinit var countdownText: TextView
    private lateinit var overlayView: OverlayView
    private lateinit var poseDataStore: PoseDataStore
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PoseRecordingAdapter
    private var movementAnalyzer: MovementAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var recordingPose: PoseDataStore.PrayerPose? = null
    private val recordingFrames = ArrayDeque<RecordingFrame>()
    private var countdownTimer: CountDownTimer? = null
    private var hasCameraPermission = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) startCamera() else statusText.setText(R.string.camera_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pose_recording)

        poseDataStore = PoseDataStore(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        findViewById<MaterialButton>(R.id.closePoseRecordingButton).setOnClickListener { finish() }
        instructionText = findViewById(R.id.poseRecordingInstruction)
        statusText = findViewById(R.id.poseRecordingStatus)
        countdownText = findViewById(R.id.poseRecordingCountdown)
        overlayView = findViewById(R.id.poseRecordingOverlay)

        recyclerView = findViewById(R.id.poseRecordingRecycler)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = PoseRecordingAdapter(
            poses = PoseDataStore.PrayerPose.entries,
            dataStore = poseDataStore,
            onRecordClick = ::startRecordingForPose,
            onResetClick = ::resetPose
        )
        recyclerView.adapter = adapter

        recordingPose = savedInstanceState?.getString(STATE_RECORDING_POSE_ID)
            ?.let(PoseDataStore.PrayerPose::fromId)
        if (recordingPose != null) {
            statusText.text = getString(
                R.string.pose_recording_resume_status_format,
                getString(recordingPose!!.labelResId)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        requestCameraIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        stopRecording(clearSelection = false)
        stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_RECORDING_POSE_ID, recordingPose?.id)
    }

    private fun requestCameraIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            hasCameraPermission = true
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<PreviewView>(R.id.poseRecordingPreview).surfaceProvider)
            }
            val analyzer = MovementAnalyzer(
                context = this,
                // Recording flow reads live frame classifications directly via onLandmarksDetected.
                onPoseChanged = { /* no-op */ },
                onFrameResult = { _, landmarks, imageWidth, imageHeight ->
                    runOnUiThread {
                        overlayView.updatePose(
                            landmarks = landmarks,
                            connections = PoseLandmarker.POSE_LANDMARKS,
                            isMirrored = true,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight
                        )
                    }
                },
                onLandmarksDetected = { pose, normalizedLandmarks, worldLandmarks ->
                    runOnUiThread {
                        maybePersistRecording(pose, normalizedLandmarks, worldLandmarks)
                    }
                }
            )
            movementAnalyzer = analyzer

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(Surface.ROTATION_0)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }

            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)
                statusText.setText(R.string.pose_recording_camera_ready)
            } catch (exception: Exception) {
                Log.e(TAG, "Failed to start pose recording camera", exception)
                statusText.setText(R.string.camera_error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        movementAnalyzer?.close()
        movementAnalyzer = null
        cameraProvider?.unbindAll()
    }

    private fun startRecordingForPose(pose: PoseDataStore.PrayerPose) {
        if (!hasCameraPermission) {
            statusText.setText(R.string.camera_permission_denied)
            return
        }
        stopRecording(clearSelection = true)
        recordingPose = pose
        recordingFrames.clear()
        setListInteractionEnabled(false)
        countdownText.text = RECORDING_DURATION_SECONDS.toString()
        countdownText.visibility = android.view.View.VISIBLE
        instructionText.setText(R.string.pose_recording_instruction)
        statusText.text = getString(R.string.pose_recording_waiting_format, getString(pose.labelResId))
        countdownTimer = object : CountDownTimer(REQUIRED_STABLE_DURATION_MS, ONE_SECOND_MS) {
            override fun onTick(millisUntilFinished: Long) {
                val remainingSeconds = ceil(millisUntilFinished / ONE_SECOND_MS.toDouble())
                    .toInt()
                    .coerceIn(1, RECORDING_DURATION_SECONDS)
                countdownText.text = remainingSeconds.toString()
            }

            override fun onFinish() {
                countdownText.text = "0"
                finalizeRecording()
            }
        }.start()
    }

    private fun resetPose(pose: PoseDataStore.PrayerPose) {
        if (poseDataStore.deletePoseSample(pose)) {
            if (recordingPose == pose) {
                stopRecording(clearSelection = true)
            }
            statusText.text = getString(R.string.pose_recording_reset_done_format, getString(pose.labelResId))
            adapter.refreshCustomState()
        } else {
            statusText.text = getString(R.string.pose_recording_reset_failed_format, getString(pose.labelResId))
        }
    }

    private fun maybePersistRecording(
        detectedPose: PhysicalPose,
        normalizedLandmarks: List<NormalizedLandmark>,
        worldLandmarks: List<Landmark>
    ) {
        val target = recordingPose ?: return
        if (countdownTimer == null) return

        val now = System.currentTimeMillis()
        recordingFrames.addLast(
            RecordingFrame(
                timestampMs = now,
                detectedPose = detectedPose,
                normalizedLandmarks = normalizedLandmarks,
                worldLandmarks = worldLandmarks
            )
        )
        trimFrameBuffer(now)

        if (detectedPose != target.physicalPose) {
            statusText.setText(
                if (normalizedLandmarks.isEmpty()) {
                    R.string.pose_recording_move_into_frame
                } else {
                    R.string.pose_recording_hold_pose_steady
                }
            )
            return
        }
        statusText.text = getString(R.string.pose_recording_hold_pose_steady)
    }

    private fun finalizeRecording() {
        val target = recordingPose ?: return stopRecording(clearSelection = true)
        val now = System.currentTimeMillis()
        trimFrameBuffer(now)
        val stableWindowStart = now - STABILITY_WINDOW_MS
        val stableWindow = recordingFrames.filter { it.timestampMs >= stableWindowStart }
        val targetFrames = stableWindow.filter { it.detectedPose == target.physicalPose && it.normalizedLandmarks.isNotEmpty() }
        val hasAnyLandmarks = stableWindow.any { it.normalizedLandmarks.isNotEmpty() }
        val hasPoseConsistency = stableWindow.isNotEmpty() &&
            stableWindow.count { it.detectedPose == target.physicalPose }.toFloat() / stableWindow.size >= MIN_POSE_MATCH_RATIO
        val hasLowMotion = hasLowMotionStreak(targetFrames)
        val shouldRequireLegs = target.physicalPose == PhysicalPose.STANDING

        val missingGroups = missingRequiredGroups(target.physicalPose, targetFrames)
        val missingLegGroups = missingGroups.intersect(setOf(LandmarkGroup.KNEES, LandmarkGroup.ANKLES))
        val failureMessageRes = when {
            !hasAnyLandmarks -> R.string.pose_recording_move_into_frame
            shouldRequireLegs && missingLegGroups.isNotEmpty() -> R.string.pose_recording_missing_legs
            missingGroups.isNotEmpty() -> R.string.pose_recording_missing_upper_body
            !hasPoseConsistency && !hasLowMotion -> R.string.pose_recording_hold_pose_steady
            else -> null
        }

        if (failureMessageRes != null) {
            statusText.setText(failureMessageRes)
            stopRecording(clearSelection = true)
            return
        }

        if (targetFrames.isEmpty()) {
            statusText.setText(R.string.pose_recording_hold_pose_steady)
            stopRecording(clearSelection = true)
            return
        }

        val representativeFrame = selectRepresentativeFrame(targetFrames)
        val saveResult = poseDataStore.savePoseSample(
            target,
            representativeFrame.normalizedLandmarks,
            representativeFrame.worldLandmarks
        )
        if (saveResult.isSuccess) {
            statusText.text = getString(R.string.pose_recording_saved_format, getString(target.labelResId))
            adapter.refreshCustomState()
            movementAnalyzer?.refreshPersonalizedSamples()
        } else {
            statusText.text = getString(R.string.pose_recording_save_failed_format, getString(target.labelResId))
        }
        stopRecording(clearSelection = true)
    }

    private fun trimFrameBuffer(now: Long) {
        while (recordingFrames.isNotEmpty() && now - recordingFrames.first().timestampMs > FRAME_BUFFER_WINDOW_MS) {
            recordingFrames.removeFirst()
        }
    }

    private fun hasLowMotionStreak(frames: List<RecordingFrame>): Boolean {
        if (frames.size < MIN_STABLE_FRAME_COUNT) return false
        var stableTransitions = 0
        for (index in 1 until frames.size) {
            val previous = frames[index - 1]
            val current = frames[index]
            val delta = averageLandmarkDelta(previous.normalizedLandmarks, current.normalizedLandmarks)
            if (delta <= MAX_AVERAGE_LANDMARK_DELTA) {
                stableTransitions += 1
                if (stableTransitions >= MIN_STABLE_FRAME_COUNT - 1) return true
            } else {
                stableTransitions = 0
            }
        }
        return false
    }

    private fun averageLandmarkDelta(
        previous: List<NormalizedLandmark>,
        current: List<NormalizedLandmark>
    ): Float {
        val count = minOf(previous.size, current.size)
        if (count == 0) return INVALID_DELTA
        var sum = 0f
        var used = 0
        for (index in 0 until count) {
            val prev = previous[index]
            val now = current[index]
            if (!isPresent(prev) || !isPresent(now)) continue
            val dx = now.x() - prev.x()
            val dy = now.y() - prev.y()
            sum += sqrt(dx * dx + dy * dy)
            used += 1
        }
        return if (used == 0) INVALID_DELTA else sum / used.toFloat()
    }

    private fun missingRequiredGroups(
        pose: PhysicalPose,
        frames: List<RecordingFrame>
    ): Set<LandmarkGroup> {
        if (frames.isEmpty()) {
            return requiredGroupsForPose(pose).toSet()
        }
        return requiredGroupsForPose(pose).filterTo(mutableSetOf()) { group ->
            val coverage = frames.count { frame ->
                group.indices.any { index -> isPresent(frame.normalizedLandmarks.getOrNull(index)) }
            }.toFloat() / frames.size
            val minimumCoverage = when {
                group == LandmarkGroup.KNEES || group == LandmarkGroup.ANKLES -> MIN_LEG_GROUP_COVERAGE_RATIO
                else -> MIN_GROUP_COVERAGE_RATIO
            }
            coverage < minimumCoverage
        }
    }

    private fun selectRepresentativeFrame(frames: List<RecordingFrame>): RecordingFrame {
        require(frames.isNotEmpty()) { "No frames available for representative selection" }
        val maxLandmarkIndex = frames.maxOfOrNull { it.normalizedLandmarks.lastIndex } ?: return frames.last()
        if (maxLandmarkIndex < 0) return frames.last()
        val averages = Array<FloatArray?>(maxLandmarkIndex + 1) { null }
        for (landmarkIndex in 0..maxLandmarkIndex) {
            var sumX = 0f
            var sumY = 0f
            var count = 0
            frames.forEach { frame ->
                val landmark = frame.normalizedLandmarks.getOrNull(landmarkIndex)
                if (landmark != null && isPresent(landmark)) {
                    sumX += landmark.x()
                    sumY += landmark.y()
                    count += 1
                }
            }
            if (count > 0) {
                averages[landmarkIndex] = floatArrayOf(sumX / count, sumY / count)
            }
        }

        return frames.minByOrNull { frame ->
            var total = 0f
            var used = 0
            for (landmarkIndex in 0..maxLandmarkIndex) {
                val average = averages[landmarkIndex] ?: continue
                val landmark = frame.normalizedLandmarks.getOrNull(landmarkIndex) ?: continue
                if (!isPresent(landmark)) continue
                val dx = landmark.x() - average[0]
                val dy = landmark.y() - average[1]
                total += (dx * dx) + (dy * dy)
                used += 1
            }
            if (used == 0) INVALID_DELTA else total / used
        } ?: frames.last()
    }

    private fun isPresent(landmark: NormalizedLandmark?): Boolean {
        if (landmark == null) return false
        val presence = landmark.presence().orElse(0f)
        val visibility = landmark.visibility().orElse(0f)
        return maxOf(presence, visibility) >= RECORDING_MIN_CONFIDENCE
    }

    private fun requiredGroupsForPose(pose: PhysicalPose): List<LandmarkGroup> = when (pose) {
        PhysicalPose.STANDING ->
            listOf(LandmarkGroup.HIPS, LandmarkGroup.KNEES, LandmarkGroup.ANKLES)
        PhysicalPose.BOWING ->
            listOf(LandmarkGroup.SHOULDERS, LandmarkGroup.HIPS)
        PhysicalPose.SITTING, PhysicalPose.PROSTRATING ->
            listOf(LandmarkGroup.SHOULDERS, LandmarkGroup.HIPS)
        PhysicalPose.UNKNOWN -> emptyList()
    }

    private fun stopRecording(clearSelection: Boolean) {
        countdownTimer?.cancel()
        countdownTimer = null
        countdownText.visibility = android.view.View.GONE
        recordingFrames.clear()
        setListInteractionEnabled(true)
        if (clearSelection) {
            recordingPose = null
        }
    }

    private fun setListInteractionEnabled(enabled: Boolean) {
        recyclerView.isEnabled = enabled
        adapter.setInteractionEnabled(enabled)
    }

    private class PoseRecordingAdapter(
        private val poses: List<PoseDataStore.PrayerPose>,
        private val dataStore: PoseDataStore,
        private val onRecordClick: (PoseDataStore.PrayerPose) -> Unit,
        private val onResetClick: (PoseDataStore.PrayerPose) -> Unit
    ) : RecyclerView.Adapter<PoseRecordingViewHolder>() {
        private val customStates = poses.associateWith { dataStore.hasCustomPose(it) }.toMutableMap()
        private var interactionsEnabled = true

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PoseRecordingViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.pose_recording_item, parent, false)
            return PoseRecordingViewHolder(view)
        }

        override fun getItemCount(): Int = poses.size

        override fun onBindViewHolder(holder: PoseRecordingViewHolder, position: Int) {
            val pose = poses[position]
            holder.bind(
                pose = pose,
                hasCustomData = customStates[pose] == true,
                interactionsEnabled = interactionsEnabled,
                onRecordClick = onRecordClick,
                onResetClick = onResetClick
            )
        }

        fun refreshCustomState() {
            poses.forEachIndexed { index, pose ->
                val oldState = customStates[pose] == true
                val newState = dataStore.hasCustomPose(pose)
                if (oldState != newState) {
                    customStates[pose] = newState
                    notifyItemChanged(index)
                }
            }
        }

        fun setInteractionEnabled(enabled: Boolean) {
            if (interactionsEnabled == enabled) return
            interactionsEnabled = enabled
            notifyItemRangeChanged(0, itemCount)
        }
    }

    private class PoseRecordingViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        private val label = view.findViewById<TextView>(R.id.poseRecordingItemLabel)
        private val status = view.findViewById<TextView>(R.id.poseRecordingItemStatus)
        private val recordButton = view.findViewById<MaterialButton>(R.id.poseRecordingItemRecordButton)
        private val resetButton = view.findViewById<MaterialButton>(R.id.poseRecordingItemResetButton)
        private val stateIndicator = view.findViewById<android.view.View>(R.id.poseRecordingItemIndicator)

        fun bind(
            pose: PoseDataStore.PrayerPose,
            hasCustomData: Boolean,
            interactionsEnabled: Boolean,
            onRecordClick: (PoseDataStore.PrayerPose) -> Unit,
            onResetClick: (PoseDataStore.PrayerPose) -> Unit
        ) {
            val context = itemView.context
            label.text = context.getString(pose.labelResId)
            status.text = context.getString(
                if (hasCustomData) R.string.pose_recording_item_custom else R.string.pose_recording_item_default
            )
            stateIndicator.setBackgroundColor(
                ContextCompat.getColor(
                    context,
                    if (hasCustomData) R.color.pose_recording_custom else R.color.pose_recording_default
                )
            )
            recordButton.isEnabled = interactionsEnabled
            recordButton.setOnClickListener { onRecordClick(pose) }
            resetButton.isEnabled = interactionsEnabled && hasCustomData
            resetButton.setOnClickListener { onResetClick(pose) }
        }
    }

    private data class RecordingFrame(
        val timestampMs: Long,
        val detectedPose: PhysicalPose,
        val normalizedLandmarks: List<NormalizedLandmark>,
        val worldLandmarks: List<Landmark>
    )

    private enum class LandmarkGroup(val indices: IntArray) {
        SHOULDERS(intArrayOf(LEFT_SHOULDER, RIGHT_SHOULDER)),
        HIPS(intArrayOf(LEFT_HIP, RIGHT_HIP)),
        KNEES(intArrayOf(LEFT_KNEE, RIGHT_KNEE)),
        ANKLES(intArrayOf(LEFT_ANKLE, RIGHT_ANKLE))
    }

    companion object {
        private const val RECORDING_DURATION_SECONDS = 5
        private const val ONE_SECOND_MS = 1_000L
        private const val REQUIRED_STABLE_DURATION_MS = RECORDING_DURATION_SECONDS * ONE_SECOND_MS
        private const val STABILITY_WINDOW_MS = 1_000L
        private const val FRAME_BUFFER_WINDOW_MS = 5_000L
        // Normalized coordinate-space delta threshold (~3% per axis) tolerated as "still".
        private const val MAX_AVERAGE_LANDMARK_DELTA = 0.03f
        private const val INVALID_DELTA = Float.MAX_VALUE
        // Accept majority pose agreement in the recent window to avoid false rejections from jitter.
        private const val MIN_POSE_MATCH_RATIO = 0.7f
        private const val MIN_STABLE_FRAME_COUNT = 4
        // Require landmark groups to be visible in at least 60% of stability-window frames.
        private const val MIN_GROUP_COVERAGE_RATIO = 0.6f
        // Legs are more often partially occluded/noisy in front floor-camera framing, so use looser coverage.
        private const val MIN_LEG_GROUP_COVERAGE_RATIO = 0.45f
        private const val RECORDING_MIN_CONFIDENCE = 0.12f
        private const val LEFT_SHOULDER = 11
        private const val RIGHT_SHOULDER = 12
        private const val LEFT_HIP = 23
        private const val RIGHT_HIP = 24
        private const val LEFT_KNEE = 25
        private const val RIGHT_KNEE = 26
        private const val LEFT_ANKLE = 27
        private const val RIGHT_ANKLE = 28
        private const val STATE_RECORDING_POSE_ID = "state_recording_pose_id"
        private const val TAG = "PoseRecordingActivity"
    }
}
