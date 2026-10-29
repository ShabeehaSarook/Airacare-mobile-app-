# Project and source map

All Kotlin files currently share the package `com.airacare.animalsafety`.
The groups below are a navigation map, not newly introduced modules. Keeping
package paths stable avoids changing manifest entries, imports and tooling.

## Where to make common changes

| Task | Start here |
| --- | --- |
| Camera or pipeline orchestration | MainActivity, AnimalSafetyAnalyzer |
| Model loading, class mapping, tensor parsing | YoloTfliteAnimalDetector, AiracareModelInfo |
| Crop adjustment and image quality | PetCropper, PetCropAdjustView, MainActivity |
| Background removal/upload/delete HTTP calls | PetCaptureRepository |
| Collection UI | CapturedPetsActivity + item_captured_pet layouts |
| Firestore metadata | CapturedPetRepository, FirestoreDetectionRepository |
| Local detection schema | DetectionDatabaseHelper |
| Risk/warnings | RiskEvaluator, WarningManager |
| Location availability | GpsSpeedProvider |

## Entry points and screens

- [MainActivity.kt](../app/src/main/java/com/airacare/animalsafety/MainActivity.kt)
- [CapturedPetsActivity.kt](../app/src/main/java/com/airacare/animalsafety/CapturedPetsActivity.kt)
- [DetectionHistoryActivity.kt](../app/src/main/java/com/airacare/animalsafety/DetectionHistoryActivity.kt)
- [SafetyMapActivity.kt](../app/src/main/java/com/airacare/animalsafety/SafetyMapActivity.kt)
- [DetectionOverlayView.kt](../app/src/main/java/com/airacare/animalsafety/DetectionOverlayView.kt)

## Detection and camera frames

- [AnimalDetector.kt](../app/src/main/java/com/airacare/animalsafety/AnimalDetector.kt)
- [AnimalDetection.kt](../app/src/main/java/com/airacare/animalsafety/AnimalDetection.kt)
- [AnimalSafetyAnalyzer.kt](../app/src/main/java/com/airacare/animalsafety/AnimalSafetyAnalyzer.kt)
- [YoloTfliteAnimalDetector.kt](../app/src/main/java/com/airacare/animalsafety/YoloTfliteAnimalDetector.kt)
- [AiracareModelInfo.kt](../app/src/main/java/com/airacare/animalsafety/AiracareModelInfo.kt)
- [ImageProxyBitmapConverter.kt](../app/src/main/java/com/airacare/animalsafety/ImageProxyBitmapConverter.kt)
- [SceneVisibilityEvaluator.kt](../app/src/main/java/com/airacare/animalsafety/SceneVisibilityEvaluator.kt)
- [HybridAiracareAnimalDetector.kt](../app/src/main/java/com/airacare/animalsafety/HybridAiracareAnimalDetector.kt)
- [PlaceholderAnimalDetector.kt](../app/src/main/java/com/airacare/animalsafety/PlaceholderAnimalDetector.kt)

## Tracking, distance and safety

- [MultiObjectTracker.kt](../app/src/main/java/com/airacare/animalsafety/MultiObjectTracker.kt)
- [DistanceProvider.kt](../app/src/main/java/com/airacare/animalsafety/DistanceProvider.kt)
- [DistanceEstimator.kt](../app/src/main/java/com/airacare/animalsafety/DistanceEstimator.kt)
- [GeometricDistanceProvider.kt](../app/src/main/java/com/airacare/animalsafety/GeometricDistanceProvider.kt)
- [DepthDistanceProviders.kt](../app/src/main/java/com/airacare/animalsafety/DepthDistanceProviders.kt)
- [DistanceCalibration.kt](../app/src/main/java/com/airacare/animalsafety/DistanceCalibration.kt)
- [DistanceCalibrationStore.kt](../app/src/main/java/com/airacare/animalsafety/DistanceCalibrationStore.kt)
- [CameraMetadataCalibrationFactory.kt](../app/src/main/java/com/airacare/animalsafety/CameraMetadataCalibrationFactory.kt)
- [RelativeMotionAnalyzer.kt](../app/src/main/java/com/airacare/animalsafety/RelativeMotionAnalyzer.kt)
- [PathRelationEstimator.kt](../app/src/main/java/com/airacare/animalsafety/PathRelationEstimator.kt)
- [MovementPathPredictor.kt](../app/src/main/java/com/airacare/animalsafety/MovementPathPredictor.kt)
- [RiskEvaluator.kt](../app/src/main/java/com/airacare/animalsafety/RiskEvaluator.kt)
- [WarningManager.kt](../app/src/main/java/com/airacare/animalsafety/WarningManager.kt)
- [GpsSpeedProvider.kt](../app/src/main/java/com/airacare/animalsafety/GpsSpeedProvider.kt)

## Capture and collection storage

- [PetCropper.kt](../app/src/main/java/com/airacare/animalsafety/PetCropper.kt)
- [PetCropAdjustView.kt](../app/src/main/java/com/airacare/animalsafety/PetCropAdjustView.kt)
- [PetCaptureRepository.kt](../app/src/main/java/com/airacare/animalsafety/PetCaptureRepository.kt)
- [CapturedPetRecord.kt](../app/src/main/java/com/airacare/animalsafety/CapturedPetRecord.kt)
- [CapturedPetRepository.kt](../app/src/main/java/com/airacare/animalsafety/CapturedPetRepository.kt)
- [CapturedPetLocalStore.kt](../app/src/main/java/com/airacare/animalsafety/CapturedPetLocalStore.kt)
- [OwnerIdProvider.kt](../app/src/main/java/com/airacare/animalsafety/OwnerIdProvider.kt)

## Detection history storage

- [DetectionRecord.kt](../app/src/main/java/com/airacare/animalsafety/DetectionRecord.kt)
- [DetectionHistoryItem.kt](../app/src/main/java/com/airacare/animalsafety/DetectionHistoryItem.kt)
- [DetectionDatabaseHelper.kt](../app/src/main/java/com/airacare/animalsafety/DetectionDatabaseHelper.kt)
- [DetectionHistoryRepository.kt](../app/src/main/java/com/airacare/animalsafety/DetectionHistoryRepository.kt)
- [FirestoreDetectionRepository.kt](../app/src/main/java/com/airacare/animalsafety/FirestoreDetectionRepository.kt)

## External video sources

- [DashcamMjpegStreamClient.kt](../app/src/main/java/com/airacare/animalsafety/DashcamMjpegStreamClient.kt)
- [RtspDashcamStreamClient.kt](../app/src/main/java/com/airacare/animalsafety/RtspDashcamStreamClient.kt)
- [RecordedVideoFramePlayer.kt](../app/src/main/java/com/airacare/animalsafety/RecordedVideoFramePlayer.kt)

## Performance and field diagnostics

- [DetectorTimingMonitor.kt](../app/src/main/java/com/airacare/animalsafety/DetectorTimingMonitor.kt)
- [PerformanceMonitor.kt](../app/src/main/java/com/airacare/animalsafety/PerformanceMonitor.kt)
- [RealWorldBenchmarkLogger.kt](../app/src/main/java/com/airacare/animalsafety/RealWorldBenchmarkLogger.kt)

## Optional hazard sharing

- [HazardSharingClient.kt](../app/src/main/java/com/airacare/animalsafety/HazardSharingClient.kt)
- [HazardReportCandidate.kt](../app/src/main/java/com/airacare/animalsafety/HazardReportCandidate.kt)
- [OfflineHazardQueue.kt](../app/src/main/java/com/airacare/animalsafety/OfflineHazardQueue.kt)
- [NearbyHazardNotificationManager.kt](../app/src/main/java/com/airacare/animalsafety/NearbyHazardNotificationManager.kt)

## Android resources and assets

- [Manifest](../app/src/main/AndroidManifest.xml): permissions and activities.
- [Main screen](../app/src/main/res/layout/activity_main.xml).
- [Collection screen](../app/src/main/res/layout/activity_captured_pets.xml).
- [Portrait collection card](../app/src/main/res/layout/item_captured_pet.xml).
- [Wide collection card](../app/src/main/res/layout-w600dp/item_captured_pet.xml): chosen at 600dp available width.
- [PNG preview](../app/src/main/res/layout/dialog_capture_preview.xml).
- [History screen](../app/src/main/res/layout/activity_detection_history.xml).
- `res/drawable/`: icons/backgrounds. `res/color/`: state-dependent colors.
- `res/values/`: strings, colors, theme.
- `assets/`: runtime TFLite models and corresponding label files.

## Other roots

- `frontend/web/`: Firebase-hosted browser client, including ONNX assets in models/.
- `frontend/backend-web/`: separate browser client served by Flask.
- `backend/web_backend.py`: Flask API entry point, PhotoRoom and Cloudinary calls.
- `backend/src/`, `backend/config/`, `backend/models/`: Python runtime dependencies and assets.
- `database/`: SQL reference and Firestore rules, not a database server.
- `models/README.md`: inventory of runtime models and their locations.
- `docs/reference/`: retained technical references.
- `docs/plans/`: historical/future architecture and training plans.
- `docs/testing/`: retained field/production checklists.
- `scripts/`: current structure check; `scripts/legacy/`: historical checks.
- `artifacts/local/`: local test images excluded from version control.
- `model-export/`: reserved workspace, currently no export implementation.

Generated and machine-specific folders (.gradle, .kotlin, .idea, .firebase, build,
app/build) are not source. The backend here is a local handover copy; organizing it
does not modify the existing Render deployment.
