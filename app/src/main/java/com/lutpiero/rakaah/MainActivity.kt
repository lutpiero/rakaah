package com.lutpiero.rakaah

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val tracker = RakaahTracker()
    private var movementAnalyzer: MovementAnalyzer? = null
    private lateinit var cameraExecutor: ExecutorService

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

        val movementText = findViewById<TextView>(R.id.movementName)
        val rakaahText = findViewById<TextView>(R.id.rakaahCount)
        val nextButton = findViewById<Button>(R.id.nextMovementButton)
        val resetButton = findViewById<Button>(R.id.resetButton)

        renderState(movementText, rakaahText, tracker.currentState())

        nextButton.setOnClickListener {
            renderState(movementText, rakaahText, tracker.nextState())
        }

        resetButton.setOnClickListener {
            renderState(movementText, rakaahText, tracker.reset())
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

            val analyzer = MovementAnalyzer { physicalPose ->
                runOnUiThread { onCameraPoseDetected(physicalPose) }
            }
            movementAnalyzer = analyzer

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis
                )
                showCameraStatus(R.string.camera_active)
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
            val movementText = findViewById<TextView>(R.id.movementName)
            val rakaahText = findViewById<TextView>(R.id.rakaahCount)
            renderState(movementText, rakaahText, tracker.nextState())
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

    private fun showCameraStatus(stringRes: Int) {
        findViewById<TextView>(R.id.cameraStatus).setText(stringRes)
    }

    override fun onDestroy() {
        super.onDestroy()
        movementAnalyzer?.close()
        cameraExecutor.shutdown()
    }
}
