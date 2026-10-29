package com.airacare.animalsafety

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.text.InputType
import android.util.Log
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var dashcamPreview: ImageView
    private lateinit var detectionOverlay: DetectionOverlayView
    private lateinit var statusText: TextView
    private lateinit var calibrationStatusText: TextView
    private lateinit var warningBannerText: TextView
    private lateinit var drivingModeButton: Button
    private lateinit var dashcamUrlInput: EditText
    private lateinit var dashcamButton: Button
    private lateinit var videoButton: Button
    private lateinit var mapButton: Button
    private lateinit var databaseButton: Button
    private lateinit var benchmarkButton: Button
    private lateinit var falsePositiveButton: Button
    private lateinit var falseNegativeButton: Button
    private lateinit var captureStoreButton: Button
    private lateinit var myCollectionButton: Button
    private lateinit var permissionPanel: View
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var animalDetector: AnimalDetector
    private lateinit var animalSafetyAnalyzer: AnimalSafetyAnalyzer
    private lateinit var multiObjectTracker: MultiObjectTracker
    private lateinit var calibrationStore: DistanceCalibrationStore
    private lateinit var distanceEstimator: DistanceEstimator
    private lateinit var metadataCalibrationFactory: CameraMetadataCalibrationFactory
    private lateinit var relativeMotionAnalyzer: RelativeMotionAnalyzer
    private lateinit var pathRelationEstimator: PathRelationEstimator
    private lateinit var movementPathPredictor: MovementPathPredictor
    private lateinit var riskEvaluator: RiskEvaluator
    private lateinit var warningManager: WarningManager
    private lateinit var gpsSpeedProvider: GpsSpeedProvider
    private lateinit var hazardSharingClient: HazardSharingClient
    private lateinit var nearbyHazardNotificationManager: NearbyHazardNotificationManager
    private lateinit var benchmarkLogger: RealWorldBenchmarkLogger
    private lateinit var performanceMonitor: PerformanceMonitor
    private lateinit var detectionHistoryRepository: DetectionHistoryRepository
    private lateinit var firestoreDetectionRepository: FirestoreDetectionRepository
    private lateinit var petCaptureRepository: PetCaptureRepository
    private lateinit var capturedPetRepository: CapturedPetRepository
    private lateinit var capturedPetLocalStore: CapturedPetLocalStore

    private val latestPetLock = Any()
    private val calibrationLock = Any()
    private var calibrationRequest: CalibrationRequest? = null
    private var activeCalibration: DistanceCalibration? = null
    private var currentCameraSource: String = "back_camera_unknown"
    private var currentDistanceSource: DistanceSource = DistanceSource.UNAVAILABLE
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var dashcamClient: DashcamMjpegStreamClient? = null
    private var dashcamModeActive: Boolean = false
    private var rtspDashcamClient: RtspDashcamStreamClient? = null
    private var recordedVideoPlayer: RecordedVideoFramePlayer? = null
    private var recordedVideoModeActive: Boolean = false
    private var drivingModeActive: Boolean = false
    private var currentVehicleSpeed: VehicleSpeedSnapshot = VehicleSpeedSnapshot.unavailable()
    private var latestDetectionsForReview: List<AnimalDetection> = emptyList()
    private var latestPetDetection: AnimalDetection? = null
    private var latestFrameBitmap: Bitmap? = null
    private var latestPetFrameBitmap: Bitmap? = null
    private var latestPetUpdatedAt: Long = 0L
    private var captureStoreBusy: Boolean = false
    private var captureBackendReady: Boolean = false
    private var captureBackendStatus: String = "Backend not checked"
    private lateinit var captureOwnerId: String
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cameraRecoveryAttempts: Int = 0
    private var cameraRecoveryRunnable: Runnable? = null
    private val lastHazardShareByTrack = mutableMapOf<Int, Long>()
    private val firestoreEventStateByKey = mutableMapOf<String, FirestoreEventState>()
    private val lastFirestoreUploadBySignature = mutableMapOf<String, Long>()
    private val lastFirestoreUploadByLabel = mutableMapOf<String, Long>()
    private var lastNearbyHazardCheckAt: Long = 0L
    private var pipelineFrameIndex: Long = 0L
    private var lastExternalInferenceStartedAt: Long = 0L




    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "Notification permission granted=$granted")
        }
    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            startGpsSpeedIfPermitted()
        }
    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                startRecordedVideo(uri)
            }
        }
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                permissionPanel.visibility = View.GONE
                if (drivingModeActive) {
                    startCamera()
                } else {
                    statusText.text = getString(R.string.status_driving_ready)
                }
            } else {
                permissionPanel.visibility = View.VISIBLE
                statusText.text = getString(R.string.status_permission_denied)
                Toast.makeText(this, R.string.status_permission_denied, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.cameraPreview)
        dashcamPreview = findViewById(R.id.dashcamPreview)
        detectionOverlay = findViewById(R.id.detectionOverlay)
        statusText = findViewById(R.id.statusText)
        calibrationStatusText = findViewById(R.id.calibrationStatusText)
        warningBannerText = findViewById(R.id.warningBannerText)
        drivingModeButton = findViewById(R.id.drivingModeButton)
        dashcamUrlInput = findViewById(R.id.dashcamUrlInput)
        dashcamButton = findViewById(R.id.dashcamButton)
        videoButton = findViewById(R.id.videoButton)
        mapButton = findViewById(R.id.mapButton)
        databaseButton = findViewById(R.id.databaseButton)
        benchmarkButton = findViewById(R.id.benchmarkButton)
        falsePositiveButton = findViewById(R.id.falsePositiveButton)
        falseNegativeButton = findViewById(R.id.falseNegativeButton)
        captureStoreButton = findViewById(R.id.captureStoreButton)
        myCollectionButton = findViewById(R.id.myCollectionButton)
        permissionPanel = findViewById(R.id.permissionPanel)
        cameraExecutor = Executors.newSingleThreadExecutor()
        multiObjectTracker = MultiObjectTracker()
        calibrationStore = DistanceCalibrationStore(this)
        val savedCalibrationExists = calibrationStore.hasSavedCalibration()
        val loadedCalibration = calibrationStore.load()
        activeCalibration = loadedCalibration
        Log.i(TAG, "Calibration saved: $savedCalibrationExists")
        Log.i(TAG, "Calibration loaded: ${loadedCalibration != null}")
        Log.i(TAG, "Calibration valid: ${loadedCalibration != null}")
        if (loadedCalibration != null) {
            Log.i(TAG, "CALIBRATION SOURCE: SAVED storedSource=${loadedCalibration.calibrationSource} camera=${loadedCalibration.cameraSource} focalLengthPx=${loadedCalibration.focalLengthPixels} savedResolution=${loadedCalibration.calibrationWidth}x${loadedCalibration.calibrationHeight}")
        } else {
            Log.i(TAG, "Reason calibration invalid: ${if (savedCalibrationExists) "saved calibration failed validation" else "no saved calibration"}")
        }
        metadataCalibrationFactory = CameraMetadataCalibrationFactory(this)
        distanceEstimator = DistanceEstimator(loadedCalibration)
        relativeMotionAnalyzer = RelativeMotionAnalyzer()
        pathRelationEstimator = PathRelationEstimator()
        movementPathPredictor = MovementPathPredictor()
        riskEvaluator = RiskEvaluator()
        warningManager = WarningManager(this)
        hazardSharingClient = HazardSharingClient(this)
        nearbyHazardNotificationManager = NearbyHazardNotificationManager(this)
        benchmarkLogger = RealWorldBenchmarkLogger(this)
        performanceMonitor = PerformanceMonitor(this)
        detectionHistoryRepository = DetectionHistoryRepository(this)
        firestoreDetectionRepository = FirestoreDetectionRepository()
        petCaptureRepository = PetCaptureRepository()
        capturedPetRepository = CapturedPetRepository()
        capturedPetLocalStore = CapturedPetLocalStore(this)
        captureOwnerId = OwnerIdProvider.get(this)
        Log.i(TAG, "Performance report: ${performanceMonitor.reportPath()}")
        Log.i(TAG, "Airacare model: ${AiracareModelInfo.summary()}")
        gpsSpeedProvider = GpsSpeedProvider(
            context = this,
            onUpdate = { snapshot ->
                currentVehicleSpeed = snapshot
                checkNearbyHazardsForNotification(snapshot)
            },
            onStatus = { message -> Log.i(TAG, message) }
        )
        calibrationStatusText.text = if (loadedCalibration == null) {
            getString(R.string.calibration_missing)
        } else {
            getString(R.string.calibration_ready)
        }

        animalDetector = createDetector()
        animalSafetyAnalyzer = AnimalSafetyAnalyzer(
            detector = animalDetector,
            onDetections = { detections -> handleDetections(detections) },
            onFailure = {
                runOnUiThread { statusText.text = getString(R.string.status_model_error) }
            },
            onPerformanceSample = { source, inferenceMs, totalMs, count, skipped ->
                performanceMonitor.recordFrame(source, inferenceMs, totalMs, count, skipped)
            }
        )

        findViewById<View>(R.id.permissionButton).setOnClickListener {
            requestCameraPermission()
        }
        findViewById<View>(R.id.calibrateButton).setOnClickListener {
            showCalibrationDialog()
        }
        drivingModeButton.setOnClickListener {
            toggleDrivingMode()
        }
        configurePrimaryProductModes()

        dashcamButton.setOnClickListener {
            toggleDashcamStream()
        }
        videoButton.setOnClickListener {
            toggleRecordedVideo()
        }
        mapButton.setOnClickListener {
            startActivity(Intent(this, SafetyMapActivity::class.java))
        }
        databaseButton.setOnClickListener {
            startActivity(Intent(this, DetectionHistoryActivity::class.java))
        }
        benchmarkButton.setOnClickListener {
            toggleBenchmarkLogging()
        }
        falsePositiveButton.setOnClickListener {
            markBenchmarkFailure(RealWorldBenchmarkLogger.FailureType.FALSE_POSITIVE)
        }
        falseNegativeButton.setOnClickListener {
            markBenchmarkFailure(RealWorldBenchmarkLogger.FailureType.FALSE_NEGATIVE)
        }
        captureStoreButton.setOnClickListener {
            captureAndStoreLatestPet()
        }
        myCollectionButton.setOnClickListener {
            startActivity(Intent(this, CapturedPetsActivity::class.java))
        }

        if (hasCameraPermission()) {
            permissionPanel.visibility = View.GONE
            statusText.text = getString(R.string.status_driving_ready)
        } else {
            permissionPanel.visibility = View.VISIBLE
            requestCameraPermission()
        }
        requestLocationPermissionIfNeeded()
        if (NEARBY_HAZARD_ALERTS_ENABLED) requestNotificationPermissionIfNeeded()
        checkCaptureBackendHealth(showToast = false)
    }


    private fun handleDetections(detections: List<AnimalDetection>) {
        if (!drivingModeActive) return
        val pipelineFrame = ++pipelineFrameIndex
        val logDiagnostics = pipelineFrame <= PIPELINE_DEBUG_FRAMES || pipelineFrame % PIPELINE_LOG_INTERVAL_FRAMES == 0L
        if (logDiagnostics) {
            Log.d(TAG, "Pipeline counts detector=${detections.size} Sent to tracker=${detections.size}")
            detections.forEach { detection ->
                Log.i(TAG, "VALID AIRACARE TARGET ENTERED PIPELINE class=${detection.label} confidence=${String.format("%.3f", detection.confidence)} bbox=${detection.boundingBox} timestamp=${android.os.SystemClock.elapsedRealtime()}")
            }
        }
        val trackedDetections = multiObjectTracker.update(detections)
        if (logDiagnostics) {
            Log.d(TAG, "Pipeline counts After tracker=${trackedDetections.size}")
        }
        val removedTrackIds = multiObjectTracker.consumeRemovedTrackIds()
        relativeMotionAnalyzer.removeTracks(removedTrackIds)
        movementPathPredictor.removeTracks(removedTrackIds)
        riskEvaluator.removeTracks(removedTrackIds)
        warningManager.removeTracks(removedTrackIds)
        removeFirestoreEventStates(removedTrackIds)
        processCalibrationCandidate(trackedDetections)
        val detectionsWithDistance = distanceEstimator.apply(trackedDetections)
        val speed = stableVehicleSpeedSnapshot()
        val detectionsWithVehicleSpeed = detectionsWithDistance.map { detection ->
            detection.copy(
                vehicleSpeedMetersPerSecond = speed.speedMetersPerSecond,
                vehicleSpeedKmh = speed.speedKmh,
                vehicleSpeedSource = speed.source,
                gpsLatitude = speed.latitude,
                gpsLongitude = speed.longitude,
                gpsBearingDegrees = speed.bearingDegrees,
                gpsHorizontalAccuracyMeters = speed.horizontalAccuracyMeters,
                gpsBearingAccuracyDegrees = speed.bearingAccuracyDegrees
            )
        }
        val detectionsWithMotion = relativeMotionAnalyzer.apply(detectionsWithVehicleSpeed)
        val detectionsWithPathRelation = pathRelationEstimator.apply(detectionsWithMotion)
        val detectionsWithPrediction = movementPathPredictor.apply(detectionsWithPathRelation)
        val detectionsWithRisk = riskEvaluator.apply(detectionsWithPrediction)
        val warningState = warningManager.update(detectionsWithRisk)
        benchmarkLogger.logFrame(currentCameraSource, detectionsWithRisk, warningState)
        saveDetectionHistory(detectionsWithRisk, warningState, pipelineFrame)
        latestDetectionsForReview = detectionsWithRisk
        updateLatestPetDetection(detectionsWithRisk)
        if (HAZARD_SHARING_ENABLED) shareReportableHazards(detectionsWithRisk)
        if (logDiagnostics) {
            detectionsWithRisk.forEach { detection -> logPipelineDiagnostic(detection, warningState) }
        }
        runOnUiThread {
            if (logDiagnostics) {
                Log.d(TAG, "Pipeline counts Sent to overlay=${detectionsWithRisk.size}")
            }
            detectionOverlay.setDetections(detectionsWithRisk)
            updateWarningBanner(warningState)
            statusText.text = if (dashcamModeActive) {
                "Dashcam: ${globalStatusText(detectionsWithRisk)}"
            } else {
                globalStatusText(detectionsWithRisk)
            }
        }
    }

    private fun updateLatestPetDetection(detections: List<AnimalDetection>) {
        val petDetection = detections
            .filter { detection -> isCapturePetLabel(detection.label) }
            .maxByOrNull { detection -> detection.confidence }
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(latestPetLock) {
            if (petDetection != null) {
                Log.i(TAG, "CaptureStore eligible pet label=${petDetection.label} confidence=${String.format("%.2f", petDetection.confidence)} track=${petDetection.trackId} box=${petDetection.boundingBox} image=${petDetection.imageWidth}x${petDetection.imageHeight}")
                val frameSnapshot = latestFrameBitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
                if (frameSnapshot == null) {
                    Log.w(TAG, "CaptureStore eligible pet found but frame snapshot unavailable")
                    latestPetDetection = null
                    latestPetUpdatedAt = 0L
                    latestPetFrameBitmap?.recycle()
                    latestPetFrameBitmap = null
                } else {
                    latestPetDetection = petDetection
                    latestPetUpdatedAt = now
                    latestPetFrameBitmap?.recycle()
                    latestPetFrameBitmap = frameSnapshot
                }
            } else if (latestPetUpdatedAt > 0L && now - latestPetUpdatedAt > PET_DETECTION_STALE_MILLIS) {
                Log.i(TAG, "CaptureStore clearing stale pet; detections=${detections.joinToString { it.label }}")
                latestPetDetection = null
                latestPetUpdatedAt = 0L
                latestPetFrameBitmap?.recycle()
                latestPetFrameBitmap = null
            } else if (detections.isNotEmpty()) {
                Log.d(TAG, "CaptureStore no dog/cat in frame labels=${detections.joinToString { "${it.label}:${String.format("%.2f", it.confidence)}" }}")
            }
        }
        runOnUiThread { updateCaptureStoreButton() }
    }

    private fun updateCaptureStoreButton() {
        if (captureStoreBusy) {
            captureStoreButton.visibility = View.VISIBLE
            captureStoreButton.isEnabled = false
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val pet = synchronized(latestPetLock) {
            latestPetDetection?.takeIf { latestPetUpdatedAt > 0L && now - latestPetUpdatedAt <= PET_DETECTION_STALE_MILLIS }
        }
        captureStoreButton.visibility = if (pet != null) View.VISIBLE else View.GONE
        captureStoreButton.isEnabled = pet != null
    }

    private fun storeLatestFrame(bitmap: Bitmap) {
        synchronized(latestPetLock) {
            latestFrameBitmap?.recycle()
            latestFrameBitmap = bitmap
        }
    }

    private fun storeLatestExternalFrame(bitmap: Bitmap) {
        storeLatestFrame(bitmap.copy(Bitmap.Config.ARGB_8888, false))
    }

    private fun clearLatestPetState() {
        synchronized(latestPetLock) {
            latestPetDetection = null
            latestPetUpdatedAt = 0L
            latestFrameBitmap?.recycle()
            latestFrameBitmap = null
            latestPetFrameBitmap?.recycle()
            latestPetFrameBitmap = null
        }
        runOnUiThread { updateCaptureStoreButton() }
    }

    private fun isCapturePetLabel(label: String): Boolean {
        return label.equals("dog", ignoreCase = true) || label.equals("cat", ignoreCase = true)
    }

    private fun captureAndStoreLatestPet() {
        if (captureStoreBusy) return
        if (!captureBackendReady) {
            Log.w(TAG, "CaptureStore backend not confirmed ready status=$captureBackendStatus; retrying health check before capture")
            checkCaptureBackendHealth(showToast = true)
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val snapshot = synchronized(latestPetLock) {
            val pet = latestPetDetection
            val frame = latestPetFrameBitmap?.takeIf { !it.isRecycled }?.copy(Bitmap.Config.ARGB_8888, false)
            Triple(pet, frame, latestPetUpdatedAt)
        }
        val detection = snapshot.first
        val frame = snapshot.second
        val updatedAt = snapshot.third
        when {
            detection == null || !isCapturePetLabel(detection.label) || now - updatedAt > PET_DETECTION_STALE_MILLIS -> {
                Log.w(TAG, "CaptureStore rejected no fresh dog/cat detection=${detection?.label} updatedAge=${if (updatedAt == 0L) "none" else now - updatedAt}")
                Toast.makeText(this, "No dog/cat selected", Toast.LENGTH_SHORT).show()
                updateCaptureStoreButton()
                return
            }
            frame == null -> {
                Log.w(TAG, "CaptureStore rejected frame unavailable label=${detection.label}")
                Toast.makeText(this, "Camera frame unavailable", Toast.LENGTH_SHORT).show()
                return
            }
        }

        setCaptureStoreLoading("Taking high-res photo...")
        if (!dashcamModeActive && !recordedVideoModeActive && imageCapture != null) {
            captureHighResolutionFrame { result ->
                result.onSuccess { highResFrame ->
                    frame.recycle()
                    processCaptureStoreFrame(detection, highResFrame, "image_capture")
                }.onFailure { exception ->
                    frame.recycle()
                    Log.e(TAG, "CaptureStore high-resolution capture failed", exception)
                    clearCaptureStoreLoading()
                    Toast.makeText(this, exception.message ?: "High-resolution capture failed", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            Log.i(TAG, "CaptureStore using latest analysis/external frame source=${currentCameraSource} frame=${frame.width}x${frame.height}")
            processCaptureStoreFrame(detection, frame, "analysis_or_external_frame")
        }
    }

    private fun processCaptureStoreFrame(detection: AnimalDetection, frame: Bitmap, frameSource: String) {
        setCaptureStoreLoading("Adjust crop")
        val cropBox = PetCropper.defaultCropRect(frame, detection)
        val initialRect = cropBox.rect
        Log.i(TAG, "CaptureStore crop adjust initial=${cropBox.reason} source=$frameSource label=${detection.label} frame=${frame.width}x${frame.height} detectionImage=${detection.imageWidth}x${detection.imageHeight} box=${detection.boundingBox}")
        if (initialRect == null) {
            frame.recycle()
            clearCaptureStoreLoading()
            Toast.makeText(this, "Could not prepare crop: ${cropBox.reason}", Toast.LENGTH_LONG).show()
            return
        }
        showPetCropAdjustDialog(detection, frame, frameSource, initialRect)
    }

    private fun showPetCropAdjustDialog(detection: AnimalDetection, frame: Bitmap, frameSource: String, initialRect: android.graphics.Rect) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 0)
        }
        val instruction = TextView(this).apply {
            text = "Move or resize the box so the full ${detection.label.lowercase(Locale.US)} is inside."
            setTextColor(Color.DKGRAY)
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }
        val cropView = PetCropAdjustView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (420 * resources.displayMetrics.density).roundToInt()
            )
            setImageAndCrop(frame, initialRect)
        }
        container.addView(instruction)
        container.addView(cropView)

        AlertDialog.Builder(this)
            .setTitle("Adjust Crop")
            .setView(container)
            .setNegativeButton(getString(R.string.cancel_button)) { _, _ ->
                frame.recycle()
                clearCaptureStoreLoading()
            }
            .setPositiveButton("Remove Background") { _, _ ->
                val selectedRect = cropView.selectedCropRect()
                val cropResult = PetCropper.cropRectWithReason(frame, selectedRect)
                Log.i(TAG, "CaptureStore crop result=${cropResult.reason} source=$frameSource label=${detection.label} frame=${frame.width}x${frame.height} selected=$selectedRect")
                frame.recycle()
                val cropped = cropResult.bitmap
                if (cropped == null) {
                    clearCaptureStoreLoading()
                    Toast.makeText(this, "Could not crop pet: ${cropResult.reason}", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                removeBackgroundForCrop(detection, cropped)
            }
            .setOnCancelListener {
                frame.recycle()
                clearCaptureStoreLoading()
            }
            .show()
    }

    private fun removeBackgroundForCrop(detection: AnimalDetection, cropped: Bitmap) {
        setCaptureStoreLoading("Removing background...")
        statusText.text = "Removing background..."
        petCaptureRepository.removeBackground(detection.label.lowercase(Locale.US), cropped) { result ->
            cropped.recycle()
            result.onSuccess { removal ->
                logPngDiagnostics("PhotoRoom output", removal.pngBytes)
                Log.i(TAG, "CaptureStore background removal success pngBytes=${removal.pngBytes.size} method=${removal.method}")
                showCapturePreviewDialog(detection, removal)
            }.onFailure { exception ->
                Log.e(TAG, "CaptureStore background removal failed", exception)
                clearCaptureStoreLoading()
                Toast.makeText(this, exception.message ?: "Background removal failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun captureHighResolutionFrame(onResult: (Result<Bitmap>) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onResult(Result.failure(IllegalStateException("High-resolution camera capture unavailable")))
            return
        }
        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val bitmap = imageProxyToUprightBitmap(image)
                        Log.i(TAG, "CaptureStore high-res frame captured raw=${image.width}x${image.height} rotation=${image.imageInfo.rotationDegrees} upright=${bitmap.width}x${bitmap.height} format=${image.format}")
                        runOnUiThread { onResult(Result.success(bitmap)) }
                    } catch (exception: Exception) {
                        Log.e(TAG, "CaptureStore high-res image conversion failed", exception)
                        runOnUiThread { onResult(Result.failure(exception)) }
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "CaptureStore ImageCapture failed code=${exception.imageCaptureError}", exception)
                    runOnUiThread { onResult(Result.failure(exception)) }
                }
            }
        )
    }

    private fun imageProxyToUprightBitmap(image: ImageProxy): Bitmap {
        val source = if (image.format == ImageFormat.JPEG && image.planes.isNotEmpty()) {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalStateException("Could not decode captured JPEG")
        } else {
            ImageProxyBitmapConverter.toBitmap(image).copy(Bitmap.Config.ARGB_8888, false)
        }
        return rotateCaptureBitmap(source, image.imageInfo.rotationDegrees)
    }

    private fun rotateCaptureBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    private fun showCapturePreviewDialog(detection: AnimalDetection, removal: BackgroundRemovalResult) {
        val preview = layoutInflater.inflate(R.layout.dialog_capture_preview, null)
        val image = preview.findViewById<ImageView>(R.id.capturePreviewImage)
        val title = preview.findViewById<TextView>(R.id.capturePreviewTitleText)
        val detail = preview.findViewById<TextView>(R.id.capturePreviewDetailText)
        val stickerBitmap = BitmapFactory.decodeByteArray(removal.pngBytes, 0, removal.pngBytes.size)
        Log.i(TAG, "CaptureStore preview image=${stickerBitmap?.width ?: "N/A"}x${stickerBitmap?.height ?: "N/A"} hasAlpha=${stickerBitmap?.hasAlpha() ?: false}")
        image.setImageBitmap(stickerBitmap)
        title.text = "${detection.label.uppercase(Locale.US)}  ${(detection.confidence * 100f).format(1)}%"
        detail.text = "Captured: ${System.currentTimeMillis().asDateTime()}\nBackground: ${if (removal.backgroundRemoved) "removed" else "unchanged"}\nMethod: ${removal.method}"

        AlertDialog.Builder(this)
            .setTitle("Capture Preview")
            .setView(preview)
            .setNegativeButton(getString(R.string.cancel_button)) { _, _ ->
                clearCaptureStoreLoading()
            }
            .setPositiveButton(getString(R.string.save_to_collection)) { _, _ ->
                uploadCapturedPet(detection, removal)
            }
            .setOnCancelListener { clearCaptureStoreLoading() }
            .show()
    }

    private fun uploadCapturedPet(detection: AnimalDetection, removal: BackgroundRemovalResult) {
        statusText.text = "Saving to collection..."
        logPngDiagnostics("Cloudinary upload input", removal.pngBytes)
        petCaptureRepository.uploadCapturedPet(
            label = detection.label.lowercase(Locale.US),
            ownerId = captureOwnerId,
            pngBytes = removal.pngBytes
        ) { uploadResult ->
            uploadResult.onSuccess { upload ->
                Log.i(TAG, "CaptureStore upload success imagePublicId=${upload.imagePublicId} imageUrl=${upload.imageUrl}")
                val record = CapturedPetRecord(
                    ownerId = captureOwnerId,
                    label = detection.label.lowercase(Locale.US),
                    confidence = detection.confidence,
                    imageUrl = upload.imageUrl,
                    imagePublicId = upload.imagePublicId,
                    storageProvider = upload.storageProvider,
                    capturedAtEpochMillis = System.currentTimeMillis(),
                    boundingBox = detection.boundingBox,
                    distanceMeters = detection.distanceMeters,
                    riskLevel = detection.riskLevel,
                    cameraSource = currentCameraSource,
                    backgroundRemoved = removal.backgroundRemoved,
                    backgroundRemovalMethod = removal.method,
                    trackId = detection.trackId
                )
                capturedPetRepository.save(record) { ok, message ->
                    clearCaptureStoreLoading()
                    if (ok) {
                        capturedPetLocalStore.save(record)
                        Log.i(TAG, "CaptureStore Firestore save success doc=$message")
                        statusText.text = "Saved"
                        Toast.makeText(this, "Saved to My Collection", Toast.LENGTH_SHORT).show()
                    } else {
                        Log.e(TAG, "CaptureStore Firestore save failed message=$message")
                        capturedPetLocalStore.save(record)
                        statusText.text = "Saved locally"
                        Toast.makeText(this, "Saved locally. Firebase failed: $message", Toast.LENGTH_LONG).show()
                    }
                }
            }.onFailure { exception ->
                Log.e(TAG, "CaptureStore upload failed", exception)
                clearCaptureStoreLoading()
                Toast.makeText(this, exception.message ?: "Upload failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun logPngDiagnostics(stage: String, pngBytes: ByteArray) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size, options)
        val bitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
        Log.i(
            TAG,
            "CaptureStore $stage pngBytes=${pngBytes.size} image=${options.outWidth}x${options.outHeight} mime=${options.outMimeType} hasAlpha=${bitmap?.hasAlpha() ?: false}"
        )
        bitmap?.recycle()
    }

    private fun setCaptureStoreLoading(message: String) {
        captureStoreBusy = true
        captureStoreButton.visibility = View.VISIBLE
        captureStoreButton.isEnabled = false
        captureStoreButton.text = message
        statusText.text = message
    }

    private fun clearCaptureStoreLoading() {
        captureStoreBusy = false
        captureStoreButton.text = getString(R.string.capture_store_button)
        updateCaptureStoreButton()
    }

    private fun checkCaptureBackendHealth(showToast: Boolean) {
        Log.i(TAG, "CaptureStore backend health check start")
        petCaptureRepository.health { ok, message ->
            captureBackendReady = ok
            captureBackendStatus = message
            Log.i(TAG, "CaptureStore backend health ok=$ok message=$message")
            if (showToast || !ok) {
                Toast.makeText(this, "Capture backend: $message", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveDetectionHistory(
        detections: List<AnimalDetection>,
        warningState: WarningState,
        pipelineFrame: Long
    ) {
        if (detections.isEmpty()) return
        val nowEpochMillis = System.currentTimeMillis()
        val nowElapsedMillis = android.os.SystemClock.elapsedRealtime()
        val records = detections.map { detection ->
            DetectionRecord.fromDetection(
                detection = detection,
                cameraSource = currentCameraSource,
                pipelineFrame = pipelineFrame,
                warningState = warningState,
                nowEpochMillis = nowEpochMillis,
                nowElapsedMillis = nowElapsedMillis
            )
        }
        detectionHistoryRepository.saveDetections(records)
        val firestoreRecords = records.filter { record -> shouldUploadFirestoreEvent(record, nowElapsedMillis) }
        if (firestoreRecords.isNotEmpty()) {
            firestoreDetectionRepository.saveDetections(firestoreRecords)
        }
    }

    private fun shouldUploadFirestoreEvent(record: DetectionRecord, nowElapsedMillis: Long): Boolean {
        val key = firestoreEventKey(record)
        val signature = firestoreEventSignature(record)
        val previous = firestoreEventStateByKey[key]
        val hasLocation = record.latitude != null && record.longitude != null
        val hasBearing = record.bearingDegrees != null
        val recentSignatureUploadAt = lastFirestoreUploadBySignature[signature]
        val labelKey = record.label.lowercase()
        val recentLabelUploadAt = lastFirestoreUploadByLabel[labelKey]
        val reason = when {
            previous == null && recentSignatureUploadAt != null && nowElapsedMillis - recentSignatureUploadAt < FIRESTORE_NEW_TRACK_SUPPRESSION_MILLIS -> null
            previous == null && recentLabelUploadAt != null && nowElapsedMillis - recentLabelUploadAt < FIRESTORE_NEW_LABEL_SUPPRESSION_MILLIS -> null
            previous == null -> "new_detection"
            previous.riskLevel != record.riskLevel && recentLabelUploadAt != null && nowElapsedMillis - recentLabelUploadAt < FIRESTORE_RISK_CHANGE_MIN_INTERVAL_MILLIS -> null
            previous.riskLevel != record.riskLevel -> "risk_changed_${previous.riskLevel}_${record.riskLevel}"
            !previous.warningTriggered && record.warningTriggered -> "warning_triggered"
            !previous.hadLocation && hasLocation -> "location_became_available"
            !previous.hadBearing && hasBearing -> "bearing_became_available"
            nowElapsedMillis - previous.savedAtElapsedMillis >= FIRESTORE_EVENT_COOLDOWN_MILLIS -> "cooldown_refresh"
            else -> null
        }
        if (reason == null) {
            Log.d(
                TAG,
                "FirestoreDetection skip duplicate key=$key signature=$signature label=${record.label} track=${record.trackId} risk=${record.riskLevel} frame=${record.pipelineFrame}"
            )
            return false
        }
        firestoreEventStateByKey[key] = FirestoreEventState(
            savedAtElapsedMillis = nowElapsedMillis,
            riskLevel = record.riskLevel,
            warningTriggered = record.warningTriggered,
            hadLocation = hasLocation,
            hadBearing = hasBearing
        )
        lastFirestoreUploadBySignature[signature] = nowElapsedMillis
        lastFirestoreUploadByLabel[labelKey] = nowElapsedMillis
        Log.i(
            TAG,
            "FirestoreDetection event=$reason key=$key signature=$signature label=${record.label} confidence=${String.format("%.2f", record.confidence)} risk=${record.riskLevel} distance=${record.distanceMeters ?: "N/A"} lat=${record.latitude ?: "N/A"} lng=${record.longitude ?: "N/A"} bearing=${record.bearingDegrees ?: "N/A"}"
        )
        return true
    }

    private fun firestoreEventKey(record: DetectionRecord): String {
        val trackId = record.trackId
        if (trackId != null) return "track:$trackId"
        val centerX = ((record.boxLeft + record.boxRight) / 2f / record.imageWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
        val centerY = ((record.boxTop + record.boxBottom) / 2f / record.imageHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
        val bucketX = (centerX * FIRESTORE_UNTRACKED_POSITION_BUCKETS).toInt()
        val bucketY = (centerY * FIRESTORE_UNTRACKED_POSITION_BUCKETS).toInt()
        return "untracked:${record.label}:$bucketX:$bucketY"
    }

    private fun firestoreEventSignature(record: DetectionRecord): String {
        val centerX = ((record.boxLeft + record.boxRight) / 2f / record.imageWidth.coerceAtLeast(1)).coerceIn(0f, 1f)
        val centerY = ((record.boxTop + record.boxBottom) / 2f / record.imageHeight.coerceAtLeast(1)).coerceIn(0f, 1f)
        val bucketX = (centerX * FIRESTORE_TRACKED_POSITION_BUCKETS).toInt()
        val bucketY = (centerY * FIRESTORE_TRACKED_POSITION_BUCKETS).toInt()
        return "${record.label.lowercase()}:$bucketX:$bucketY"
    }

    private fun removeFirestoreEventStates(trackIds: Set<Int>) {
        if (trackIds.isEmpty()) return
        trackIds.forEach { trackId -> firestoreEventStateByKey.remove("track:$trackId") }
    }

    private fun toggleBenchmarkLogging() {
        if (benchmarkLogger.isActive) {
            val dir = benchmarkLogger.stop()
            benchmarkButton.text = getString(R.string.benchmark_start)
            Toast.makeText(this, "Benchmark saved: ${dir?.absolutePath ?: "unknown"}", Toast.LENGTH_LONG).show()
        } else {
            val dir = benchmarkLogger.start()
            benchmarkButton.text = getString(R.string.benchmark_stop)
            Toast.makeText(this, "Benchmark started: ${dir.absolutePath}", Toast.LENGTH_LONG).show()
        }
    }

    private fun markBenchmarkFailure(type: RealWorldBenchmarkLogger.FailureType) {
        benchmarkLogger.markFailure(type, currentCameraSource, latestDetectionsForReview)
        Toast.makeText(this, "Marked ${type.name.replace('_', ' ')}", Toast.LENGTH_SHORT).show()
    }


    private fun shareReportableHazards(detections: List<AnimalDetection>) {
        if (!drivingModeActive || !HAZARD_SHARING_ENABLED) return
        val now = android.os.SystemClock.elapsedRealtime()
        detections
            .filter { detection -> detection.riskLevel == RiskLevel.HIGH && detection.gpsLatitude != null && detection.gpsLongitude != null }
            .forEach { detection ->
                val key = detection.trackId ?: detection.hashCode()
                val lastSharedAt = lastHazardShareByTrack[key] ?: 0L
                if (now - lastSharedAt < HAZARD_SHARE_COOLDOWN_MILLIS) return@forEach
                lastHazardShareByTrack[key] = now
                val candidate = HazardReportCandidate.fromDetection(detection)
                hazardSharingClient.submitHazard(candidate) { ok, message ->
                    Log.i(TAG, "Hazard sharing result ok=$ok message=$message class=${detection.label} track=${detection.trackId}")
                }
            }
    }
    private fun createDetector(): AnimalDetector {
        return try {
            YoloTfliteAnimalDetector(
                this,
                YoloTfliteAnimalDetector.ModelPreference.PRETRAINED_COCO,
                onUprightFrame = { bitmap -> storeLatestFrame(bitmap) }
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Model loading failed", exception)
            statusText.text = getString(R.string.status_model_error)
            PlaceholderAnimalDetector()
        }
    }

    private fun showCalibrationDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 20, 48, 0)
        }
        val distanceInput = EditText(this).apply {
            hint = "Known camera distance in metres"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("3.0")
        }
        val heightInput = EditText(this).apply {
            hint = "Person height optional, default 1.70 m"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("")
        }
        container.addView(distanceInput)
        container.addView(heightInput)

        AlertDialog.Builder(this)
            .setTitle("Calibrate distance")
            .setMessage("Measure the camera distance to a fully visible person. Height is optional; Airacare uses 1.70 m if left blank.")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Start") { _, _ ->
                val distance = distanceInput.text.toString().toFloatOrNull()
                val heightText = heightInput.text.toString().trim()
                val height = if (heightText.isBlank()) DEFAULT_PERSON_HEIGHT_METERS else heightText.toFloatOrNull()
                if (distance == null || distance <= 0f || height == null || height <= 0f) {
                    Log.w(TAG, "Invalid calibration input height=$height distance=$distance defaultHeight=$DEFAULT_PERSON_HEIGHT_METERS")
                    Toast.makeText(this, "Enter a positive measured distance. Height is optional.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Log.i(TAG, "Calibration dialog accepted height=$height distance=$distance heightWasDefault=${heightText.isBlank()}")
                startCalibrationCapture(height, distance)
            }
            .show()
    }

    private fun startCalibrationCapture(knownHeightMeters: Float, knownDistanceMeters: Float) {
        if (knownHeightMeters <= 0f || knownDistanceMeters <= 0f) {
            Log.w(TAG, "Calibration rejected invalid input height=$knownHeightMeters distance=$knownDistanceMeters")
            Toast.makeText(this, "Enter positive height and distance values.", Toast.LENGTH_LONG).show()
            return
        }
        synchronized(calibrationLock) {
            calibrationRequest = CalibrationRequest(
                knownHeightMeters = knownHeightMeters,
                knownDistanceMeters = knownDistanceMeters,
                startedAtMillis = android.os.SystemClock.elapsedRealtime()
            )
        }
        Log.i(
            TAG,
            "Calibration started object=person enteredHeight=$knownHeightMeters enteredDistance=$knownDistanceMeters camera=$currentCameraSource"
        )
        calibrationStatusText.text = getString(R.string.calibration_collecting)
        statusText.text = getString(R.string.calibration_collecting)
    }

    private fun processCalibrationCandidate(detections: List<AnimalDetection>) {
        val request = synchronized(calibrationLock) { calibrationRequest } ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - request.startedAtMillis > CALIBRATION_TIMEOUT_MILLIS) {
            failCalibration("Calibration timeout: ${request.lastRejectReason ?: "no stable PERSON samples collected"}")
            return
        }

        Log.d(TAG, "Calibration frame detections=${detections.size} stable=${request.samples.size}/$STABLE_CALIBRATION_FRAMES lastReject=${request.lastRejectReason ?: "none"}")
        detections.forEach { detection ->
            val box = detection.boundingBox
            Log.d(
                TAG,
                "Calibration detection class=${detection.label} conf=${String.format("%.2f", detection.confidence)} track=${detection.trackId} box=[${String.format("%.1f", box.left)},${String.format("%.1f", box.top)},${String.format("%.1f", box.right)},${String.format("%.1f", box.bottom)}] width=${String.format("%.1f", box.width)} height=${String.format("%.1f", box.height)} image=${detection.imageWidth}x${detection.imageHeight}"
            )
        }

        val personCandidates = detections
            .filter { it.label.equals("person", ignoreCase = true) }
            .map { detection -> detection to validateCalibrationPerson(detection) }

        Log.d(TAG, "Calibration PERSON candidates=${personCandidates.size} totalDetections=${detections.size}")
        personCandidates.forEach { (candidate, validation) ->
            val box = candidate.boundingBox
            Log.d(
                TAG,
                "Calibration PERSON candidate track=${candidate.trackId} confidence=${String.format("%.2f", candidate.confidence)} box=[${String.format("%.1f", box.left)},${String.format("%.1f", box.top)},${String.format("%.1f", box.right)},${String.format("%.1f", box.bottom)}] width=${String.format("%.1f", box.width)} height=${String.format("%.1f", box.height)} pixelHeight=${String.format("%.1f", box.height)} fullBody=${validation.accepted} reason=${validation.reason} edgeTouch=${validation.edgeTouch}"
            )
        }

        val selected = selectCalibrationPerson(personCandidates, request)
        if (selected == null) {
            request.samples.clear()
            request.selectedTrackId = null
            request.rejectedFrames++
            request.lastRejectReason = when {
                personCandidates.isEmpty() -> "no PERSON detected"
                personCandidates.any { it.second.reason == "confidence_too_low" } -> "PERSON confidence too low"
                personCandidates.any { it.second.reason == "bbox_too_small" } -> "PERSON bbox too small"
                else -> personCandidates.joinToString { it.second.reason }
            }
            Log.d(TAG, "Calibration frame rejected reason=${request.lastRejectReason} rejectedFrames=${request.rejectedFrames}")
            runOnUiThread {
                calibrationStatusText.text = "PERSON not ready: ${request.lastRejectReason}"
            }
            return
        }

        val box = selected.boundingBox
        val pixelHeight = box.height
        val edgeTouch = touchesFrameEdge(selected)
        Log.d(
            TAG,
            "Calibration selected PERSON track=${selected.trackId} confidence=${String.format("%.2f", selected.confidence)} box=[${String.format("%.1f", box.left)},${String.format("%.1f", box.top)},${String.format("%.1f", box.right)},${String.format("%.1f", box.bottom)}] pixelHeight=${String.format("%.1f", pixelHeight)} image=${selected.imageWidth}x${selected.imageHeight} edgeTouch=$edgeTouch coordinateSpace=upright_analysis_image"
        )

        if (request.selectedTrackId == null && selected.trackId != null) {
            request.selectedTrackId = selected.trackId
            Log.d(TAG, "Calibration selected track=${request.selectedTrackId}")
        }
        if (request.selectedTrackId != null && selected.trackId != request.selectedTrackId) {
            request.samples.clear()
            request.selectedTrackId = selected.trackId
            Log.d(TAG, "Calibration switched selected track=${request.selectedTrackId}; samples reset")
        }

        val medianBefore = medianOrNull(request.samples.map { it.pixelHeight })
        if (medianBefore != null && abs(pixelHeight - medianBefore) > medianBefore * MAX_SAMPLE_OUTLIER_RATIO) {
            request.rejectedFrames++
            request.lastRejectReason = "height_outlier"
            Log.d(
                TAG,
                "Calibration frame rejected reason=height_outlier pixelHeight=${String.format("%.1f", pixelHeight)} median=${String.format("%.1f", medianBefore)} allowedRatio=$MAX_SAMPLE_OUTLIER_RATIO stable=${request.samples.size}/$STABLE_CALIBRATION_FRAMES"
            )
            runOnUiThread {
                calibrationStatusText.text = "PERSON detected - stabilizing ${request.samples.size}/$STABLE_CALIBRATION_FRAMES"
            }
            return
        }

        request.samples.add(CalibrationSample(pixelHeight, selected.confidence, selected.trackId))
        while (request.samples.size > STABLE_CALIBRATION_FRAMES) {
            request.samples.removeAt(0)
        }

        val heights = request.samples.map { it.pixelHeight }
        val minHeight = heights.minOrNull() ?: 0f
        val maxHeight = heights.maxOrNull() ?: 0f
        val medianHeight = medianOrNull(heights) ?: pixelHeight
        val variation = maxHeight - minHeight
        val tolerance = max(STABLE_PIXEL_TOLERANCE, medianHeight * STABLE_PIXEL_TOLERANCE_RATIO)
        request.lastRejectReason = null
        Log.d(
            TAG,
            "Calibration stable=${request.samples.size}/$STABLE_CALIBRATION_FRAMES pixelHeight=${String.format("%.1f", pixelHeight)} median=${String.format("%.1f", medianHeight)} variation=${String.format("%.1f", variation)} tolerance=${String.format("%.1f", tolerance)} rejectedFrames=${request.rejectedFrames} edgeTouch=$edgeTouch"
        )
        runOnUiThread {
            calibrationStatusText.text = "PERSON detected - Stable frames: ${request.samples.size}/$STABLE_CALIBRATION_FRAMES"
        }

        if (request.samples.size < STABLE_CALIBRATION_FRAMES) {
            Log.d(TAG, "Calibration waiting reason=insufficient_stable_samples")
            return
        }
        if (variation > tolerance) {
            request.samples.removeAt(0)
            request.lastRejectReason = "unstable bbox height"
            Log.d(TAG, "Calibration waiting reason=height_variation_too_high variation=${String.format("%.1f", variation)} tolerance=${String.format("%.1f", tolerance)}")
            runOnUiThread {
                calibrationStatusText.text = "PERSON detected - bbox unstable ${request.samples.size}/$STABLE_CALIBRATION_FRAMES"
            }
            return
        }

        val focalLength = (medianHeight * request.knownDistanceMeters) / request.knownHeightMeters
        Log.i(
            TAG,
            "Calibration calculated focalLength=$focalLength pixelHeight=$medianHeight knownHeight=${request.knownHeightMeters} knownDistance=${request.knownDistanceMeters} coordinateSpace=upright_analysis_image"
        )
        val calibration = DistanceCalibration(
            focalLengthPixels = focalLength,
            cameraName = currentCameraSource,
            cameraSource = currentCameraSource,
            calibrationWidth = selected.imageWidth,
            calibrationHeight = selected.imageHeight,
            calibrationObject = "person",
            knownObjectHeightMeters = request.knownHeightMeters,
            knownDistanceMeters = request.knownDistanceMeters,
            pixelHeight = medianHeight,
            createdAtMillis = System.currentTimeMillis(),
            calibrationSource = DistanceCalibration.SOURCE_MANUAL
        )

        try {
            calibration.validate()
            calibrationStore.save(calibration)
            activeCalibration = calibration
            distanceEstimator.setCalibration(calibration)
            currentDistanceSource = DistanceSource.MANUAL_CALIBRATION
            synchronized(calibrationLock) { calibrationRequest = null }
            Log.i(TAG, "Calibration saved focalLength=$focalLength camera=$currentCameraSource resolution=${selected.imageWidth}x${selected.imageHeight} pixelHeight=$medianHeight")
            runOnUiThread {
                calibrationStatusText.text = getString(R.string.calibration_ready)
                statusText.text = globalStatusText(emptyList())
                Toast.makeText(this, "Distance calibration saved.", Toast.LENGTH_LONG).show()
            }
        } catch (exception: Exception) {
            Log.e(TAG, "Calibration failure reason=validation_or_save_failed", exception)
            failCalibration("Calibration invalid; try again with a fully visible person.")
        }
    }

    private fun selectCalibrationPerson(candidates: List<Pair<AnimalDetection, CalibrationValidation>>, request: CalibrationRequest): AnimalDetection? {
        val validCandidates = candidates.filter { it.second.accepted }.map { it.first }
        val sameTrack = request.selectedTrackId?.let { trackId ->
            validCandidates.filter { it.trackId == trackId }.maxWithOrNull(compareBy<AnimalDetection> { it.confidence }.thenBy { it.boundingBox.area })
        }
        return sameTrack ?: validCandidates
            .maxWithOrNull(compareBy<AnimalDetection> { it.confidence }.thenBy { it.boundingBox.area })
    }

    private fun validateCalibrationPerson(detection: AnimalDetection): CalibrationValidation {
        val box = detection.boundingBox
        val minHeight = max(MIN_CALIBRATION_PERSON_HEIGHT_PIXELS, detection.imageHeight * MIN_CALIBRATION_PERSON_HEIGHT_RATIO)
        val minWidth = max(MIN_CALIBRATION_PERSON_WIDTH_PIXELS, detection.imageWidth * MIN_CALIBRATION_PERSON_WIDTH_RATIO)
        val edgeTouch = touchesFrameEdge(detection)
        return when {
            detection.confidence < MIN_CALIBRATION_CONFIDENCE -> CalibrationValidation(false, "confidence_too_low", edgeTouch)
            box.height < minHeight || box.width < minWidth -> CalibrationValidation(false, "bbox_too_small", edgeTouch)
            box.height <= 0f || box.width <= 0f -> CalibrationValidation(false, "invalid_bbox", edgeTouch)
            else -> CalibrationValidation(true, "accepted", edgeTouch)
        }
    }

    private fun touchesFrameEdge(detection: AnimalDetection): Boolean {
        val box = detection.boundingBox
        val marginX = detection.imageWidth * CALIBRATION_EDGE_MARGIN_RATIO
        val marginY = detection.imageHeight * CALIBRATION_EDGE_MARGIN_RATIO
        return box.left <= marginX || box.top <= marginY ||
            box.right >= detection.imageWidth - marginX || box.bottom >= detection.imageHeight - marginY
    }

    private data class CalibrationValidation(
        val accepted: Boolean,
        val reason: String,
        val edgeTouch: Boolean
    )

    private fun medianOrNull(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2f
    }

    private fun failCalibration(message: String) {
        synchronized(calibrationLock) { calibrationRequest = null }
        Log.w(TAG, "Calibration failure reason=$message")
        runOnUiThread {
            calibrationStatusText.text = getString(R.string.calibration_missing)
            statusText.text = message
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }



    private fun globalStatusText(detections: List<AnimalDetection>): String {
        val detectionStatus = detectionDecisionStatus(detections)
        val sceneRisk = sceneRisk(detections)
        val source = detections.firstOrNull { it.distanceSource != DistanceSource.UNAVAILABLE }?.distanceSource
            ?: currentDistanceSource
        val visibility = SceneVisibilityMonitor.current
        val enhancement = if (visibility.enhancementEnabled) "AUTO" else "OFF"
        val speedText = currentVehicleSpeed.speedKmh?.let { "${String.format("%.0f", it)} km/h" } ?: "N/A"
        val bearingText = currentVehicleSpeed.bearingDegrees?.let { "${String.format("%.0f", it)} deg" } ?: "N/A"
        val locationText = if (currentVehicleSpeed.latitude != null && currentVehicleSpeed.longitude != null) "LOCK" else "N/A"
        val perfText = if (::performanceMonitor.isInitialized) performanceMonitor.snapshot().compactText() else "PERF:N/A"
        val timingText = DetectorTimingMonitor.current().compactText()
        val visionText = if (visibility.state == VisibilityState.LOW_LIGHT || visibility.state == VisibilityState.NIGHT || visibility.state == VisibilityState.POOR_VISIBILITY) "VISION DEGRADED" else "VISION OK"
        val productMode = when {
            dashcamModeActive -> "LIVE DASHCAM PIPELINE"
            recordedVideoModeActive -> "RECORDED DASHCAM PIPELINE"
            else -> "PHONE CAMERA MINIMUM SYSTEM"
        }
        return "$detectionStatus\n$productMode   SCENE RISK: ${sceneRisk.name}   VISIBILITY: ${visibility.state.name}   $visionText\nDIST SOURCE: ${sourceLabel(source)}   SPEED: $speedText   BEARING: $bearingText   GPS: $locationText   DETECTED: ${detections.size}   ENHANCEMENT: $enhancement\n$perfText\n$timingText"
    }

    private fun detectionDecisionStatus(detections: List<AnimalDetection>): String {
        if (!drivingModeActive && !dashcamModeActive && !recordedVideoModeActive) {
            return "Ready"
        }
        if (detections.isEmpty()) {
            return "Checking road..."
        }
        val primary = detections.maxWithOrNull(
            compareBy<AnimalDetection> { it.riskLevel.order }
                .thenBy { it.confidence }
        ) ?: return "Checking road..."
        if (isCheckingDetection(primary)) {
            return "Checking object..."
        }
        val confidence = "${String.format("%.0f", primary.confidence * 100f)}%"
        return when (primary.label.lowercase(Locale.US)) {
            "person" -> if (isAhead(primary)) {
                "Person ahead - $confidence"
            } else {
                "Person detected - $confidence"
            }
            "dog" -> "Dog detected - Capture available - $confidence"
            "cat" -> "Cat detected - Capture available - $confidence"
            "horse" -> "Horse detected - $confidence"
            "cow" -> "Cow detected - $confidence"
            else -> "${primary.label.replaceFirstChar { it.uppercase(Locale.US) }} detected - $confidence"
        }
    }

    private fun isAhead(detection: AnimalDetection): Boolean {
        if (detection.pathZone == PathZone.IN_PATH || detection.pathZone == PathZone.NEAR_PATH) return true
        val centerX = detection.boundingBox.centerX
        val frameCenterX = detection.imageWidth / 2f
        return abs(centerX - frameCenterX) < detection.imageWidth * 0.25f
    }

    private fun isCheckingDetection(detection: AnimalDetection): Boolean {
        return detection.trackId != null && detection.trackAgeFrames in 1 until CHECKING_TRACK_FRAMES
    }

    private fun sceneRisk(detections: List<AnimalDetection>): RiskLevel {
        return detections
            .map { it.riskLevel }
            .filter { it != RiskLevel.UNKNOWN }
            .maxByOrNull { it.order }
            ?: RiskLevel.LOW
    }

    private fun sourceLabel(source: DistanceSource): String {
        return when (source) {
            DistanceSource.MANUAL_CALIBRATION -> "MANUAL"
            DistanceSource.SAVED_VALIDATED_CALIBRATION -> "SAVED"
            DistanceSource.CAMERA_METADATA -> "CAMERA_METADATA"
            DistanceSource.MONOCULAR_DEPTH -> "DEPTH"
            DistanceSource.ARCORE_DEPTH -> "ARCORE"
            DistanceSource.UNAVAILABLE -> "NONE"
        }
    }

    private fun configurePrimaryProductModes() {
        dashcamUrlInput.visibility = if (LIVE_DASHCAM_STREAMS_ENABLED) View.VISIBLE else View.GONE
        dashcamButton.visibility = if (LIVE_DASHCAM_STREAMS_ENABLED) View.VISIBLE else View.GONE
        videoButton.visibility = if (RECORDED_DASHCAM_MODE_ENABLED) View.VISIBLE else View.GONE
        mapButton.visibility = View.GONE
        Log.i(TAG, "Product modes: phone-camera, recorded dashcam, and live dashcam pipelines. Map/backend/sensor-fusion features disabled.")
    }

    private fun stableVehicleSpeedSnapshot(): VehicleSpeedSnapshot {
        val snapshot = currentVehicleSpeed
        if (!snapshot.available || snapshot.timestampMillis <= 0L) return snapshot
        val ageMillis = System.currentTimeMillis() - snapshot.timestampMillis
        return if (ageMillis <= MAX_GPS_SPEED_AGE_MILLIS) {
            snapshot
        } else {
            snapshot.copy(
                speedMetersPerSecond = null,
                speedKmh = null,
                bearingDegrees = null,
                bearingAccuracyDegrees = null,
                source = "${snapshot.source}_stale",
                available = false,
                reason = "stale_${ageMillis}ms"
            )
        }
    }

    private fun updateWarningBanner(warningState: WarningState) {
        when (warningState.level) {
            WarningLevel.NONE -> {
                warningBannerText.visibility = View.GONE
                warningBannerText.text = ""
                warningBannerText.setBackgroundColor(Color.TRANSPARENT)
            }
            WarningLevel.CAUTION -> {
                warningBannerText.visibility = View.VISIBLE
                warningBannerText.text = "CAUTION: ${warningState.message}"
                warningBannerText.setBackgroundColor(Color.rgb(172, 112, 0))
            }
            WarningLevel.DANGER -> {
                warningBannerText.visibility = View.VISIBLE
                warningBannerText.text = "WARNING: ${warningState.message}"
                warningBannerText.setBackgroundColor(Color.rgb(185, 28, 28))
            }
        }
    }


    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun checkNearbyHazardsForNotification(snapshot: VehicleSpeedSnapshot) {
        if (!drivingModeActive || !NEARBY_HAZARD_ALERTS_ENABLED) return
        val latitude = snapshot.latitude ?: return
        val longitude = snapshot.longitude ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNearbyHazardCheckAt < NEARBY_HAZARD_CHECK_INTERVAL_MILLIS) return
        lastNearbyHazardCheckAt = now
        hazardSharingClient.fetchNearbyHazards(
            latitude = latitude,
            longitude = longitude,
            radiusMeters = NEARBY_HAZARD_NOTIFICATION_RADIUS_METERS
        ) { ok, hazards, message ->
            if (ok) {
                nearbyHazardNotificationManager.notifyNearbyHazards(hazards)
            } else {
                Log.w(TAG, "Nearby hazard notification check failed: $message")
            }
        }
    }
    private fun requestLocationPermissionIfNeeded() {
        if (hasLocationPermission()) {
            Log.i(TAG, "GPS permission already granted; starting location provider")
            startGpsSpeedIfPermitted()
        } else {
            Log.w(TAG, "GPS permission missing; requesting fine/coarse location")
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun startGpsSpeedIfPermitted() {
        if (hasLocationPermission()) {
            Log.i(TAG, "GPS start requested; permission granted")
            gpsSpeedProvider.start()
        } else {
            currentVehicleSpeed = VehicleSpeedSnapshot.unavailable("permission_missing")
            Log.w(TAG, "GPS speed unavailable: location permission denied")
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }



    private fun toggleDrivingMode() {
        if (drivingModeActive) {
            stopDrivingMode()
        } else {
            startDrivingMode()
        }
    }

    private fun startDrivingMode() {
        if (!hasCameraPermission()) {
            drivingModeActive = true
            requestCameraPermission()
            return
        }
        drivingModeActive = true
        warningManager.setEnabled(true)
        firestoreEventStateByKey.clear()
        lastFirestoreUploadBySignature.clear()
        lastFirestoreUploadByLabel.clear()
        startGpsSpeedIfPermitted()
        drivingModeButton.text = getString(R.string.driving_mode_stop)
        permissionPanel.visibility = View.GONE
        startCamera()
    }

    private fun stopDrivingMode() {
        drivingModeActive = false
        clearLatestPetState()
        warningManager.setEnabled(false)
        firestoreEventStateByKey.clear()
        lastFirestoreUploadBySignature.clear()
        lastFirestoreUploadByLabel.clear()
        stopDashcamStream(restartPhoneCamera = false)
        stopRecordedVideo(restartPhoneCamera = false)
        cameraProvider?.unbindAll()
        imageCapture = null
        detectionOverlay.setDetections(emptyList())
        updateWarningBanner(WarningState())
        previewView.visibility = View.VISIBLE
        dashcamPreview.visibility = View.GONE
        drivingModeButton.text = getString(R.string.driving_mode_start)
        statusText.text = getString(R.string.status_driving_stopped)
    }

    private fun activateDrivingModeForExternalSource() {
        if (!drivingModeActive) {
            firestoreEventStateByKey.clear()
            lastFirestoreUploadBySignature.clear()
            lastFirestoreUploadByLabel.clear()
        }
        drivingModeActive = true
        warningManager.setEnabled(true)
        startGpsSpeedIfPermitted()
        drivingModeButton.text = getString(R.string.driving_mode_stop)
        permissionPanel.visibility = View.GONE
    }
    private fun toggleDashcamStream() {
        if (!LIVE_DASHCAM_STREAMS_ENABLED) {
            Toast.makeText(this, "Live dashcam streams are disabled for this phase", Toast.LENGTH_SHORT).show()
            return
        }
        if (dashcamModeActive) {
            stopDashcamStream(restartPhoneCamera = true)
        } else {
            startDashcamStream()
        }
    }


    private fun toggleRecordedVideo() {
        if (!RECORDED_DASHCAM_MODE_ENABLED) {
            Toast.makeText(this, "Recorded dashcam mode is disabled", Toast.LENGTH_SHORT).show()
            return
        }
        if (recordedVideoModeActive) {
            stopRecordedVideo(restartPhoneCamera = true)
        } else {
            videoPickerLauncher.launch("video/*")
        }
    }
    private fun startDashcamStream() {
        if (!LIVE_DASHCAM_STREAMS_ENABLED) return
        val url = dashcamUrlInput.text.toString().trim()
        if (url.startsWith("rtsp://", ignoreCase = true)) {
            startRtspDashcamStream(url)
            return
        }
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            Toast.makeText(this, "Enter an HTTP MJPEG or RTSP stream URL", Toast.LENGTH_SHORT).show()
            return
        }

        activateDrivingModeForExternalSource()
        rtspDashcamClient?.stop()
        rtspDashcamClient = null
        dashcamModeActive = true
        cameraProvider?.unbindAll()
        imageCapture = null
        previewView.visibility = View.GONE
        dashcamPreview.visibility = View.VISIBLE
        dashcamButton.text = getString(R.string.dashcam_stop)
        statusText.text = "Connecting dashcam"
        currentCameraSource = "external_mjpeg_dashcam"
        detectionOverlay.setDetections(emptyList())

        dashcamClient?.stop()
        dashcamClient = DashcamMjpegStreamClient(
            streamUrl = url,
            onFrame = frameHandler@ { bitmap ->
                val startedAt = android.os.SystemClock.elapsedRealtime()
                runOnUiThread { dashcamPreview.setImageBitmap(bitmap) }
                if (!shouldRunExternalInference(startedAt)) return@frameHandler
                storeLatestExternalFrame(bitmap)
                val inferenceStartedAt = android.os.SystemClock.elapsedRealtime()
                val detections = animalDetector.analyze(bitmap)
                val inferenceMs = android.os.SystemClock.elapsedRealtime() - inferenceStartedAt
                handleDetections(detections)
                performanceMonitor.recordFrame(currentCameraSource, inferenceMs, android.os.SystemClock.elapsedRealtime() - startedAt, detections.size)
            },
            onStatus = { message ->
                runOnUiThread { statusText.text = message }
            },
            onError = { exception ->
                runOnUiThread {
                    statusText.text = getString(R.string.status_dashcam_error)
                    Toast.makeText(this, exception.message ?: getString(R.string.status_dashcam_error), Toast.LENGTH_SHORT).show()
                    stopDashcamStream(restartPhoneCamera = true)
                }
            }
        ).also { client -> client.start() }
    }



    private fun startRtspDashcamStream(url: String) {
        if (!LIVE_DASHCAM_STREAMS_ENABLED) return
        activateDrivingModeForExternalSource()
        dashcamClient?.stop()
        dashcamClient = null
        dashcamModeActive = true
        cameraProvider?.unbindAll()
        imageCapture = null
        previewView.visibility = View.GONE
        dashcamPreview.visibility = View.VISIBLE
        dashcamButton.text = getString(R.string.dashcam_stop)
        statusText.text = "Connecting RTSP dashcam"
        currentCameraSource = "external_rtsp_dashcam"
        detectionOverlay.setDetections(emptyList())

        rtspDashcamClient?.stop()
        rtspDashcamClient = RtspDashcamStreamClient(
            context = this,
            streamUrl = url,
            onFrame = frameHandler@ { bitmap ->
                val startedAt = android.os.SystemClock.elapsedRealtime()
                runOnUiThread { dashcamPreview.setImageBitmap(bitmap) }
                if (!shouldRunExternalInference(startedAt)) return@frameHandler
                storeLatestExternalFrame(bitmap)
                val inferenceStartedAt = android.os.SystemClock.elapsedRealtime()
                val detections = animalDetector.analyze(bitmap)
                val inferenceMs = android.os.SystemClock.elapsedRealtime() - inferenceStartedAt
                handleDetections(detections)
                performanceMonitor.recordFrame(currentCameraSource, inferenceMs, android.os.SystemClock.elapsedRealtime() - startedAt, detections.size)
            },
            onStatus = { message ->
                runOnUiThread { statusText.text = message }
            },
            onError = { exception ->
                runOnUiThread {
                    statusText.text = getString(R.string.status_dashcam_error)
                    Toast.makeText(this, exception.message ?: getString(R.string.status_dashcam_error), Toast.LENGTH_SHORT).show()
                    stopDashcamStream(restartPhoneCamera = true)
                }
            }
        ).also { client -> client.start() }
    }
    private fun startRecordedVideo(uri: Uri) {
        if (!RECORDED_DASHCAM_MODE_ENABLED) return
        activateDrivingModeForExternalSource()
        stopDashcamStream(restartPhoneCamera = false)
        recordedVideoModeActive = true
        cameraProvider?.unbindAll()
        imageCapture = null
        previewView.visibility = View.GONE
        dashcamPreview.visibility = View.VISIBLE
        videoButton.text = getString(R.string.video_stop)
        statusText.text = "Opening recorded video"
        currentCameraSource = "recorded_dashcam_video"
        detectionOverlay.setDetections(emptyList())

        recordedVideoPlayer?.stop()
        recordedVideoPlayer = RecordedVideoFramePlayer(
            context = this,
            videoUri = uri,
            onFrame = frameHandler@ { bitmap ->
                val startedAt = android.os.SystemClock.elapsedRealtime()
                runOnUiThread { dashcamPreview.setImageBitmap(bitmap) }
                if (!shouldRunExternalInference(startedAt)) return@frameHandler
                storeLatestExternalFrame(bitmap)
                val inferenceStartedAt = android.os.SystemClock.elapsedRealtime()
                val detections = animalDetector.analyze(bitmap)
                val inferenceMs = android.os.SystemClock.elapsedRealtime() - inferenceStartedAt
                handleDetections(detections)
                performanceMonitor.recordFrame(currentCameraSource, inferenceMs, android.os.SystemClock.elapsedRealtime() - startedAt, detections.size)
            },
            onStatus = { message ->
                runOnUiThread { statusText.text = message }
            },
            onError = { exception ->
                runOnUiThread {
                    statusText.text = getString(R.string.status_video_error)
                    Toast.makeText(this, exception.message ?: getString(R.string.status_video_error), Toast.LENGTH_SHORT).show()
                    stopRecordedVideo(restartPhoneCamera = true)
                }
            },
            onComplete = {
                runOnUiThread {
                    statusText.text = getString(R.string.status_video_complete)
                    stopRecordedVideo(restartPhoneCamera = true)
                }
            }
        ).also { player -> player.start() }
    }

    private fun shouldRunExternalInference(nowMillis: Long): Boolean {
        if (nowMillis - lastExternalInferenceStartedAt < EXTERNAL_INFERENCE_MIN_INTERVAL_MILLIS) {
            return false
        }
        lastExternalInferenceStartedAt = nowMillis
        return true
    }

    private fun stopRecordedVideo(restartPhoneCamera: Boolean) {
        clearLatestPetState()
        recordedVideoPlayer?.stop()
        recordedVideoPlayer = null
        recordedVideoModeActive = false
        videoButton.text = getString(R.string.video_pick)
        if (!dashcamModeActive) {
            dashcamPreview.setImageDrawable(null)
            dashcamPreview.visibility = View.GONE
            previewView.visibility = View.VISIBLE
            detectionOverlay.setDetections(emptyList())
        }
        if (restartPhoneCamera && drivingModeActive && hasCameraPermission()) {
            startCamera()
        }
    }
    private fun stopDashcamStream(restartPhoneCamera: Boolean) {
        clearLatestPetState()
        dashcamClient?.stop()
        dashcamClient = null
        rtspDashcamClient?.stop()
        rtspDashcamClient = null
        dashcamModeActive = false
        dashcamPreview.setImageDrawable(null)
        dashcamPreview.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        dashcamButton.text = getString(R.string.dashcam_start)
        detectionOverlay.setDetections(emptyList())
        if (restartPhoneCamera && drivingModeActive && hasCameraPermission()) {
            startCamera()
        }
    }
    private fun startCamera() {
        if (!drivingModeActive || dashcamModeActive || recordedVideoModeActive) return
        previewView.visibility = View.VISIBLE
        dashcamPreview.visibility = View.GONE
        statusText.text = getString(R.string.status_starting_camera)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(
            {
                val cameraProvider = cameraProviderFuture.get()
                this.cameraProvider = cameraProvider

                val preview = Preview.Builder()
                    .build()
                    .also { previewUseCase ->
                        previewUseCase.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(ANALYSIS_TARGET_WIDTH, ANALYSIS_TARGET_HEIGHT),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { analysisUseCase ->
                        analysisUseCase.setAnalyzer(cameraExecutor, animalSafetyAnalyzer)
                    }

                val imageCaptureUseCase = ImageCapture.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(CAPTURE_TARGET_WIDTH, CAPTURE_TARGET_HEIGHT),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(CAPTURE_JPEG_QUALITY)
                    .build()

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                        imageCaptureUseCase
                    )
                    imageCapture = imageCaptureUseCase
                    currentCameraSource = cameraSourceLabel(camera)
                    Log.i(TAG, "Camera bound for calibration source=$currentCameraSource captureTarget=${CAPTURE_TARGET_WIDTH}x${CAPTURE_TARGET_HEIGHT}")
                    val uprightResolution = uprightAnalysisResolution(imageAnalysis)
                    selectStartupDistanceProvider(camera, uprightResolution.width, uprightResolution.height)
                    statusText.text = globalStatusText(emptyList())
                } catch (exception: Exception) {
                    imageCapture = null
                    Log.e(TAG, "CameraX use case binding failed", exception)
                    handleCameraInterruption(exception)
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun handleCameraInterruption(exception: Exception? = null) {
        if (!drivingModeActive || dashcamModeActive || recordedVideoModeActive) {
            statusText.text = getString(R.string.status_camera_error)
            return
        }
        clearLatestPetState()
        cameraProvider?.unbindAll()
        imageCapture = null
        detectionOverlay.setDetections(emptyList())
        updateWarningBanner(WarningState())
        warningManager.silence()
        statusText.text = getString(R.string.status_camera_recovering)
        scheduleCameraRecovery(exception)
    }

    private fun scheduleCameraRecovery(exception: Exception? = null) {
        if (!drivingModeActive || dashcamModeActive || recordedVideoModeActive) return
        if (cameraRecoveryAttempts >= MAX_CAMERA_RECOVERY_ATTEMPTS) {
            Log.e(TAG, "Camera recovery stopped after $cameraRecoveryAttempts attempts", exception)
            statusText.text = getString(R.string.status_camera_recovery_failed)
            return
        }
        cameraRecoveryAttempts++
        cameraRecoveryRunnable?.let { mainHandler.removeCallbacks(it) }
        val delay = CAMERA_RECOVERY_BASE_DELAY_MILLIS * cameraRecoveryAttempts
        val retry = Runnable {
            if (drivingModeActive && !dashcamModeActive && !recordedVideoModeActive && hasCameraPermission()) {
                Log.i(TAG, "Camera recovery attempt=$cameraRecoveryAttempts")
                startCamera()
            }
        }
        cameraRecoveryRunnable = retry
        mainHandler.postDelayed(retry, delay)
    }

    private fun cancelCameraRecovery() {
        cameraRecoveryRunnable?.let { mainHandler.removeCallbacks(it) }
        cameraRecoveryRunnable = null
        cameraRecoveryAttempts = 0
    }


    private fun selectDrivingCamera(cameraProvider: ProcessCameraProvider): CameraSelector {
        if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            return CameraSelector.DEFAULT_BACK_CAMERA
        }
        val external = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_EXTERNAL)
            .build()
        if (cameraProvider.hasCamera(external)) {
            Log.i(TAG, "Using external CameraX camera")
            return external
        }
        Log.w(TAG, "Back/external camera unavailable; falling back to default camera selector")
        return CameraSelector.DEFAULT_BACK_CAMERA
    }
    private fun uprightAnalysisResolution(imageAnalysis: ImageAnalysis): Size {
        val resolutionInfo = imageAnalysis.resolutionInfo
        val rawResolution = resolutionInfo?.resolution ?: Size(ANALYSIS_TARGET_WIDTH, ANALYSIS_TARGET_HEIGHT)
        val rotationDegrees = resolutionInfo?.rotationDegrees ?: DEFAULT_ANALYSIS_ROTATION_DEGREES
        val swapAxes = rotationDegrees == 90 || rotationDegrees == 270
        val uprightResolution = if (swapAxes) Size(rawResolution.height, rawResolution.width) else rawResolution
        Log.i(TAG, "Analysis resolution raw=${rawResolution.width}x${rawResolution.height} rotation=$rotationDegrees upright=${uprightResolution.width}x${uprightResolution.height}")
        return uprightResolution
    }

    private fun selectStartupDistanceProvider(camera: Camera, analysisWidth: Int, analysisHeight: Int) {
        val savedCalibration = activeCalibration
        val savedCalibrationExists = calibrationStore.hasSavedCalibration()
        Log.i(TAG, "Startup distance-source check saved=$savedCalibrationExists loaded=${savedCalibration != null} cameraId=$currentCameraSource currentResolution=${analysisWidth}x$analysisHeight")
        Log.i(TAG, "DIST SOURCE PRIORITY: SAVED_VALIDATED_CALIBRATION -> ARCORE_DEPTH(unavailable) -> MONOCULAR_DEPTH(asset_missing) -> CAMERA_METADATA -> MANUAL_CALIBRATION(optional) -> UNAVAILABLE")

        if (savedCalibration != null && calibrationMatchesActiveCamera(savedCalibration, currentCameraSource)) {
            Log.i(TAG, "Calibration loaded: true")
            Log.i(TAG, "Calibration valid: true")
            Log.i(TAG, "Camera ID: $currentCameraSource")
            Log.i(TAG, "Focal length px: ${savedCalibration.focalLengthPixels}")
            Log.i(TAG, "Saved resolution: ${savedCalibration.calibrationWidth}x${savedCalibration.calibrationHeight}")
            Log.i(TAG, "Current resolution: ${analysisWidth}x$analysisHeight")
            distanceEstimator.setDistanceProvider(DistanceEstimator.calibrationToProvider(savedCalibration))
            currentDistanceSource = if (savedCalibration.calibrationSource == DistanceCalibration.SOURCE_MANUAL) DistanceSource.MANUAL_CALIBRATION else DistanceSource.CAMERA_METADATA
            calibrationStatusText.text = getString(R.string.calibration_ready)
            Log.i(TAG, "DIST SOURCE: ${if (savedCalibration.calibrationSource == DistanceCalibration.SOURCE_MANUAL) "MANUAL" else savedCalibration.calibrationSource}")
            return
        }

        if (savedCalibration != null) {
            Log.w(TAG, "Calibration valid: false")
            Log.w(TAG, "Reason calibration invalid: camera_mismatch saved=${savedCalibration.cameraSource} active=$currentCameraSource")
            activeCalibration = null
        } else {
            Log.i(TAG, "Calibration loaded: false")
            Log.i(TAG, "Calibration valid: false")
            Log.i(TAG, "Reason calibration invalid: ${if (savedCalibrationExists) "saved calibration failed validation" else "no saved calibration"}")
        }

        Log.i(TAG, "ARCore Depth provider available=false reason=not_integrated_optional_provider")
        Log.i(TAG, "Monocular depth provider available=false reason=no_validated_depth_model_asset")
        val metadataCalibration = metadataCalibrationFactory.create(camera, analysisWidth, analysisHeight)
        if (metadataCalibration != null) {
            activeCalibration = metadataCalibration
            distanceEstimator.setDistanceProvider(DistanceEstimator.calibrationToProvider(metadataCalibration))
            currentDistanceSource = DistanceSource.CAMERA_METADATA
            calibrationStatusText.text = getString(R.string.calibration_ready)
            try {
                calibrationStore.save(metadataCalibration)
            } catch (exception: Exception) {
                Log.w(TAG, "Camera metadata calibration active but not saved", exception)
            }
            Log.i(TAG, "DIST SOURCE: CAMERA_METADATA")
            return
        }

        distanceEstimator.setDistanceProvider(null)
        currentDistanceSource = DistanceSource.UNAVAILABLE
        calibrationStatusText.text = getString(R.string.calibration_missing)
        Log.w(TAG, "DIST SOURCE: NONE reason=no_valid_saved_manual_or_camera_metadata")
    }
    private fun calibrationMatchesActiveCamera(calibration: DistanceCalibration, activeCameraSource: String): Boolean {
        val savedCameraId = cameraIdToken(calibration.cameraSource)
        val activeCameraId = cameraIdToken(activeCameraSource)
        val matches = when {
            savedCameraId != null && activeCameraId != null -> savedCameraId == activeCameraId
            calibration.cameraSource.contains(activeCameraSource, ignoreCase = true) -> true
            activeCameraSource.contains(calibration.cameraSource, ignoreCase = true) -> true
            calibration.cameraSource.contains("back_camera", ignoreCase = true) && activeCameraSource.contains("back_camera", ignoreCase = true) -> true
            else -> false
        }
        Log.i(TAG, "Calibration camera match=$matches savedCamera=${calibration.cameraSource} activeCamera=$activeCameraSource savedId=$savedCameraId activeId=$activeCameraId source=${calibration.calibrationSource}")
        return matches
    }

    private fun cameraIdToken(value: String): String? {
        return Regex("camera_id_([^\\s]+)").find(value)?.groupValues?.getOrNull(1)
    }

    private fun logPipelineDiagnostic(detection: AnimalDetection, warningState: WarningState) {
        val distance = distanceEstimator.diagnosticValues(detection)
        val warningLevel = if (warningState.trackId == detection.trackId) warningState.level else WarningLevel.NONE
        Log.d(
            TAG,
            "PIPELINE AUDIT track=${detection.trackId ?: "N/A"} class=${detection.label} confidence=${String.format("%.2f", detection.confidence)} bboxPixelHeight=${String.format("%.1f", distance.bboxPixelHeight)} objectPhysicalHeight=${distance.objectPhysicalHeightMeters ?: "N/A"} focalLengthPx=${distance.focalLengthPixels ?: "N/A"} calibrationSource=${distance.calibrationSource} calibrationCamera=${distance.calibrationCamera} distance=${detection.distanceMeters ?: "N/A"} distanceSource=${detection.distanceSource} distanceQuality=${detection.distanceQuality ?: "N/A"} distanceHistory=${distance.historyCount} motionHistory=${relativeMotionAnalyzer.historyCount(detection.trackId)} closing=${detection.closingSpeedMetersPerSecond ?: "N/A"} ttc=${detection.ttcSeconds ?: "N/A"} pathZone=${detection.pathZone} movement=${detection.movementDirection} predictedCrossing=${detection.predictedPathCrossing} predictedCrossingSeconds=${detection.predictedPathCrossingSeconds ?: "N/A"} predictionReason=${detection.predictedPathReason} vehicleSpeed=${detection.vehicleSpeedKmh ?: "N/A"}kmh speedSource=${detection.vehicleSpeedSource} gps=${detection.gpsLatitude ?: "N/A"},${detection.gpsLongitude ?: "N/A"} bearing=${detection.gpsBearingDegrees ?: "N/A"} gpsAccuracy=${detection.gpsHorizontalAccuracyMeters ?: "N/A"} risk=${detection.riskLevel} riskReason=${detection.riskReason} warning=$warningLevel warningMessage=${warningState.message}"
        )
    }

    private fun Long.asDateTime(): String {
        return CAPTURE_DATE_FORMAT.format(Date(this))
    }

    private fun Float.format(decimals: Int): String {
        return "%.${decimals}f".format(Locale.US, this)
    }

    private fun cameraSourceLabel(camera: Camera): String {
        return try {
            val cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
            "${Build.MANUFACTURER} ${Build.MODEL} back_camera_id_$cameraId"
        } catch (exception: Exception) {
            Log.w(TAG, "Could not resolve Camera2 camera ID for calibration", exception)
            "${Build.MANUFACTURER} ${Build.MODEL} back_camera"
        }
    }
    override fun onPause() {
        super.onPause()
        warningManager.silence()
        if (drivingModeActive && !dashcamModeActive && !recordedVideoModeActive) {
            cameraProvider?.unbindAll()
            statusText.text = getString(R.string.status_camera_interrupted)
        }
    }

    override fun onResume() {
        super.onResume()
        if (drivingModeActive && !dashcamModeActive && !recordedVideoModeActive && hasCameraPermission()) {
            statusText.text = getString(R.string.status_camera_recovering)
            scheduleCameraRecovery()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        cancelCameraRecovery()
        stopDashcamStream(restartPhoneCamera = false)
        stopRecordedVideo(restartPhoneCamera = false)
        animalSafetyAnalyzer.close()
        gpsSpeedProvider.stop()
        hazardSharingClient.close()
        if (benchmarkLogger.isActive) benchmarkLogger.stop()
        warningManager.release()
        detectionHistoryRepository.close()
        cameraExecutor.shutdown()
    }

    private data class CalibrationRequest(
        val knownHeightMeters: Float,
        val knownDistanceMeters: Float,
        val startedAtMillis: Long,
        val samples: MutableList<CalibrationSample> = mutableListOf(),
        var selectedTrackId: Int? = null,
        var rejectedFrames: Int = 0,
        var lastRejectReason: String? = null
    )

    private data class CalibrationSample(
        val pixelHeight: Float,
        val confidence: Float,
        val trackId: Int?
    )

    private data class FirestoreEventState(
        val savedAtElapsedMillis: Long,
        val riskLevel: RiskLevel,
        val warningTriggered: Boolean,
        val hadLocation: Boolean,
        val hadBearing: Boolean
    )

    companion object {
        private const val TAG = "AiracareCamera"
        private const val RECORDED_DASHCAM_MODE_ENABLED = true
        private const val LIVE_DASHCAM_STREAMS_ENABLED = true
        private const val HAZARD_SHARING_ENABLED = false
        private const val NEARBY_HAZARD_ALERTS_ENABLED = false
        private const val ANALYSIS_TARGET_WIDTH = 480
        private const val ANALYSIS_TARGET_HEIGHT = 360
        private const val CAPTURE_TARGET_WIDTH = 1280
        private const val CAPTURE_TARGET_HEIGHT = 960
        private const val CAPTURE_JPEG_QUALITY = 95
        private const val PIPELINE_DEBUG_FRAMES = 5L
        private const val PIPELINE_LOG_INTERVAL_FRAMES = 10L
        private const val DEFAULT_ANALYSIS_ROTATION_DEGREES = 90
        private const val STABLE_CALIBRATION_FRAMES = 7
        private const val STABLE_PIXEL_TOLERANCE = 32.0f
        private const val STABLE_PIXEL_TOLERANCE_RATIO = 0.12f
        private const val MAX_SAMPLE_OUTLIER_RATIO = 0.35f
        private const val MIN_CALIBRATION_CONFIDENCE = 0.25f
        private const val HAZARD_SHARE_COOLDOWN_MILLIS = 60_000L
        private const val NEARBY_HAZARD_CHECK_INTERVAL_MILLIS = 30_000L
        private const val NEARBY_HAZARD_NOTIFICATION_RADIUS_METERS = 3000f
        private const val MAX_GPS_SPEED_AGE_MILLIS = 5_000L
        private const val FIRESTORE_EVENT_COOLDOWN_MILLIS = 30_000L
        private const val FIRESTORE_NEW_TRACK_SUPPRESSION_MILLIS = 8_000L
        private const val FIRESTORE_NEW_LABEL_SUPPRESSION_MILLIS = 8_000L
        private const val FIRESTORE_RISK_CHANGE_MIN_INTERVAL_MILLIS = 5_000L
        private const val FIRESTORE_UNTRACKED_POSITION_BUCKETS = 8
        private const val FIRESTORE_TRACKED_POSITION_BUCKETS = 6
        private const val EXTERNAL_INFERENCE_MIN_INTERVAL_MILLIS = 350L
        private const val PET_DETECTION_STALE_MILLIS = 4_000L
        private const val CHECKING_TRACK_FRAMES = 3
        private const val MAX_CAMERA_RECOVERY_ATTEMPTS = 5
        private const val CAMERA_RECOVERY_BASE_DELAY_MILLIS = 1_000L
        private const val MIN_CALIBRATION_PERSON_HEIGHT_PIXELS = 40.0f
        private const val MIN_CALIBRATION_PERSON_WIDTH_PIXELS = 12.0f
        private const val MIN_CALIBRATION_PERSON_HEIGHT_RATIO = 0.18f
        private const val MIN_CALIBRATION_PERSON_WIDTH_RATIO = 0.025f
        private const val CALIBRATION_EDGE_MARGIN_RATIO = 0.02f
        private const val CALIBRATION_TIMEOUT_MILLIS = 30_000L
        private const val DEFAULT_PERSON_HEIGHT_METERS = 1.70f
        private val CAPTURE_DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }
}






















