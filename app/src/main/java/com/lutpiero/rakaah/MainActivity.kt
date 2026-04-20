package com.lutpiero.rakaah

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val tracker = RakaahTracker()
    private var movementAnalyzer: MovementAnalyzer? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var movementText: TextView
    private lateinit var rakaahText: TextView
    private lateinit var poseGuideText: TextView
    private lateinit var cameraStatusText: TextView
    private lateinit var overlayView: OverlayView
    private var lastDetectedPose: PhysicalPose = PhysicalPose.UNKNOWN

    // Permission launcher
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else showCameraStatus(R.string.camera_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraExecutor = Executors.newSingleThreadExecutor()

        movementText = findViewById(R.id.movementName)
        rakaahText = findViewById(R.id.rakaahCount)
        poseGuideText = findViewById(R.id.poseGuide)
        cameraStatusText = findViewById(R.id.cameraStatus)
        overlayView = findViewById(R.id.poseOverlay)
        val nextButton = findViewById<Button>(R.id.nextMovementButton)
        val resetButton = findViewById<Button>(R.id.resetButton)

        renderState(movementText, rakaahText, tracker.currentState())

        nextButton.setOnClickListener {
            renderState(movementText, rakaahText, tracker.nextState())
            renderPoseGuide(lastDetectedPose)
        }

        resetButton.setOnClickListener {
            renderState(movementText, rakaahText, tracker.reset())
            renderPoseGuide(lastDetectedPose)
        }

        requestCameraIfNeeded()
    }

    private fun requestCameraIfNeeded() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(
                    findViewById<PreviewView>(R.id.cameraPreview).surfaceProvider
                )
            }

            val analyzer = try {
                MovementAnalyzer(
                    context = this,
                    onPoseChanged = { physicalPose ->
                        runOnUiThread { onCameraPoseDetected(physicalPose) }
                    },
                    onFrameResult = { physicalPose, landmarks, imageWidth, imageHeight ->
                        runOnUiThread {
                            lastDetectedPose = physicalPose
                            overlayView.updatePose(
                                landmarks = landmarks,
                                connections = PoseLandmarker.POSE_LANDMARKS,
                                isMirrored = true,
                                imageWidth = imageWidth,
                                imageHeight = imageHeight
                            )
                            renderPoseGuide(physicalPose)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create movement analyzer", e)
                showCameraStatus(R.string.camera_error)
                null
            }
            renderPoseGuide(PhysicalPose.UNKNOWN)
            movementAnalyzer = analyzer

            val imageAnalysis = analyzer?.let {
                ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysis -> analysis.setAnalyzer(cameraExecutor, it) }
            }

            try {
                cameraProvider.unbindAll()
                if (imageAnalysis != null) {
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        imageAnalysis
                    )
                    showCameraStatus(R.string.camera_active)
                } else {
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview
                    )
                }
            } catch (e: Exception) {
                showCameraStatus(R.string.camera_error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Called on the main thread each time a stable pose change is detected by the camera.
     * Advances the tracker only when the detected pose matches the expected next movement.
     */
    private fun onCameraPoseDetected(physicalPose: PhysicalPose) {
        val expectedPhysical = movementToPhysicalPose(tracker.peekNextMovement())
        if (physicalPose == expectedPhysical) {
            renderState(movementText, rakaahText, tracker.nextState())
            renderPoseGuide(lastDetectedPose)
        }
    }

    private fun renderState(
        movementText: TextView,
        rakaahText: TextView,
        state: PrayerState
    ) {
        movementText.text = state.movementName
        rakaahText.text = getString(R.string.rakaah_count_format, state.rakaahCount)
    }

    private fun renderPoseGuide(detectedPose: PhysicalPose) {
        val expectedPhysical = movementToPhysicalPose(tracker.peekNextMovement())
        when {
            detectedPose == PhysicalPose.UNKNOWN -> {
                poseGuideText.text = getString(R.string.pose_guide_no_person)
                poseGuideText.setTextColor(
                    ContextCompat.getColor(this, R.color.pose_guide_unknown)
                )
            }
            detectedPose == expectedPhysical -> {
                poseGuideText.text = getString(
                    R.string.pose_guide_correct_format,
                    poseLabel(detectedPose)
                )
                poseGuideText.setTextColor(
                    ContextCompat.getColor(this, R.color.pose_guide_correct)
                )
            }
            else -> {
                poseGuideText.text = getString(
                    R.string.pose_guide_wrong_format,
                    poseLabel(detectedPose),
                    poseLabel(expectedPhysical)
                )
                poseGuideText.setTextColor(
                    ContextCompat.getColor(this, R.color.pose_guide_wrong)
                )
            }
        }
    }

    private fun poseLabel(pose: PhysicalPose): String = when (pose) {
        PhysicalPose.STANDING -> getString(R.string.pose_label_standing)
        PhysicalPose.BOWING -> getString(R.string.pose_label_bowing)
        PhysicalPose.PROSTRATING -> getString(R.string.pose_label_prostrating)
        PhysicalPose.SITTING -> getString(R.string.pose_label_sitting)
        PhysicalPose.UNKNOWN -> getString(R.string.pose_label_unknown)
    }

    private fun showCameraStatus(stringRes: Int) {
        cameraStatusText.setText(stringRes)
    }

    override fun onDestroy() {
        super.onDestroy()
        movementAnalyzer?.close()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
