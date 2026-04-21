package com.lutpiero.rakaah

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PoseRecordingActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var instructionText: TextView
    private lateinit var statusText: TextView
    private lateinit var overlayView: OverlayView
    private lateinit var poseDataStore: PoseDataStore
    private lateinit var adapter: PoseRecordingAdapter
    private var movementAnalyzer: MovementAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var recordingPose: PoseDataStore.PrayerPose? = null
    private var stableSinceMs: Long = 0L
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
        overlayView = findViewById(R.id.poseRecordingOverlay)

        val recyclerView = findViewById<RecyclerView>(R.id.poseRecordingRecycler)
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
                onPoseChanged = {},
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
            } catch (_: Exception) {
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
        recordingPose = pose
        stableSinceMs = 0L
        instructionText.setText(R.string.pose_recording_instruction)
        statusText.text = getString(R.string.pose_recording_waiting_format, getString(pose.labelResId))
    }

    private fun resetPose(pose: PoseDataStore.PrayerPose) {
        if (poseDataStore.deletePoseSample(pose)) {
            if (recordingPose == pose) {
                recordingPose = null
                stableSinceMs = 0L
            }
            statusText.text = getString(R.string.pose_recording_reset_done_format, getString(pose.labelResId))
            adapter.refreshCustomState()
        } else {
            statusText.text = getString(R.string.pose_recording_reset_failed_format, getString(pose.labelResId))
        }
    }

    private fun maybePersistRecording(
        detectedPose: PhysicalPose,
        normalizedLandmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        worldLandmarks: List<com.google.mediapipe.tasks.components.containers.Landmark>
    ) {
        val target = recordingPose ?: return
        if (detectedPose != target.physicalPose || normalizedLandmarks.isEmpty()) {
            stableSinceMs = 0L
            statusText.text = getString(R.string.pose_recording_unstable_format, getString(target.labelResId))
            return
        }

        val now = System.currentTimeMillis()
        if (stableSinceMs == 0L) stableSinceMs = now
        val elapsed = now - stableSinceMs
        if (elapsed < REQUIRED_STABLE_DURATION_MS) {
            val remaining = ((REQUIRED_STABLE_DURATION_MS - elapsed) / 1000f).coerceAtLeast(0f)
            statusText.text = getString(
                R.string.pose_recording_hold_still_format,
                getString(target.labelResId),
                String.format("%.1f", remaining)
            )
            return
        }

        val saveResult = poseDataStore.savePoseSample(target, normalizedLandmarks, worldLandmarks)
        if (saveResult.isSuccess) {
            statusText.text = getString(
                R.string.pose_recording_saved_format,
                getString(target.labelResId)
            )
            recordingPose = null
            stableSinceMs = 0L
            adapter.refreshCustomState()
        } else {
            statusText.text = getString(
                R.string.pose_recording_save_failed_format,
                getString(target.labelResId)
            )
        }
    }

    private class PoseRecordingAdapter(
        private val poses: List<PoseDataStore.PrayerPose>,
        private val dataStore: PoseDataStore,
        private val onRecordClick: (PoseDataStore.PrayerPose) -> Unit,
        private val onResetClick: (PoseDataStore.PrayerPose) -> Unit
    ) : RecyclerView.Adapter<PoseRecordingViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): PoseRecordingViewHolder {
            val view = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.pose_recording_item, parent, false)
            return PoseRecordingViewHolder(view)
        }

        override fun getItemCount(): Int = poses.size

        override fun onBindViewHolder(holder: PoseRecordingViewHolder, position: Int) {
            val pose = poses[position]
            holder.bind(pose, dataStore.hasCustomPose(pose), onRecordClick, onResetClick)
        }

        fun refreshCustomState() {
            notifyDataSetChanged()
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
            recordButton.setOnClickListener { onRecordClick(pose) }
            resetButton.isEnabled = hasCustomData
            resetButton.setOnClickListener { onResetClick(pose) }
        }
    }

    companion object {
        private const val REQUIRED_STABLE_DURATION_MS = 2_000L
        private const val STATE_RECORDING_POSE_ID = "state_recording_pose_id"
    }
}
