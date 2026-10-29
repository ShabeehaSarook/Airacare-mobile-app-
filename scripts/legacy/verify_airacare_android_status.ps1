$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
# Historical V4 checks only. Use scripts/check-project.ps1 for current structure checks.
$Src = Join-Path $Root "app\src\main\java\com\airacare\animalsafety"
$Assets = Join-Path $Root "app\src\main\assets"
$Manifest = Join-Path $Root "app\src\main\AndroidManifest.xml"

function Test-File($label, $path) {
    $ok = Test-Path $path -PathType Leaf
    Write-Host ("{0}: {1} - {2}" -f ($(if ($ok) { "PASS" } else { "FAIL" }), $label, $path))
    return $ok
}

function Test-Text($label, $path, $pattern) {
    if (!(Test-Path $path)) {
        Write-Host "FAIL: $label - missing file $path"
        return $false
    }
    $text = Get-Content $path -Raw
    $ok = $text -match $pattern
    Write-Host ("{0}: {1}" -f ($(if ($ok) { "PASS" } else { "FAIL" }), $label))
    return $ok
}

$checks = [ordered]@{}

$checks["Custom TFLite model asset"] = Test-File "Custom TFLite model asset" (Join-Path $Assets "airacare_custom.tflite")
$checks["Custom labels asset"] = Test-File "Custom labels asset" (Join-Path $Assets "airacare_custom_labels.txt")
$checks["SafetyMapActivity manifest"] = Test-Text "SafetyMapActivity registered" $Manifest "SafetyMapActivity"
$checks["Camera permission"] = Test-Text "Camera permission" $Manifest "android.permission.CAMERA"
$checks["Internet permission"] = Test-Text "Internet permission" $Manifest "android.permission.INTERNET"
$checks["Location permission"] = Test-Text "Location permission" $Manifest "ACCESS_FINE_LOCATION"
$checks["Notification permission"] = Test-Text "Notification permission" $Manifest "POST_NOTIFICATIONS"
$checks["Vibration permission"] = Test-Text "Vibration permission" $Manifest "android.permission.VIBRATE"
$checks["Model metadata"] = Test-Text "Model metadata uses custom model" (Join-Path $Src "AiracareModelInfo.kt") "airacare_custom\.tflite"
$checks["Frozen V4 dataset"] = Test-Text "Model metadata keeps frozen V4 dataset" (Join-Path $Src "AiracareModelInfo.kt") "airacare_v4_road_safety_final"
$checks["Frozen 15 epoch run"] = Test-Text "Model metadata keeps 15-epoch custom run" (Join-Path $Src "AiracareModelInfo.kt") "airacare_yolo_v4_15epoch_finetune"
$checks["No retrain freeze marker"] = Test-Text "Model metadata marks no retrain/no rebuild" (Join-Path $Src "AiracareModelInfo.kt") "frozen_no_retrain_no_v4_rebuild"
$checks["V5 failure gate marker"] = Test-Text "Model metadata gates V5 on reviewed real-world failures" (Join-Path $Src "AiracareModelInfo.kt") "v5_only_from_human_reviewed_real_world_failures"
$checks["V5 replacement rule"] = Test-Text "Model metadata keeps V4 unless V5 is clearly better" (Join-Path $Src "AiracareModelInfo.kt") "replace_v4_only_if_v5_clearly_better"
$checks["Sensor fusion separate extension"] = Test-Text "Model metadata keeps sensor fusion separate" (Join-Path $Src "AiracareModelInfo.kt") "sensor_fusion_extension_no_yolo_retrain_required"
$checks["Sensor event dataset marker"] = Test-Text "Model metadata separates sensor event dataset" (Join-Path $Src "AiracareModelInfo.kt") "separate_sensor_event_dataset"
$checks["8 class labels"] = Test-Text "8 class label order includes elephant" (Join-Path $Src "AiracareModelInfo.kt") "person,dog,cat,horse,cow,deer,goat,elephant"
$checks["CPU XNNPACK runtime"] = Test-Text "Detector defaults to CPU XNNPACK" (Join-Path $Src "YoloTfliteAnimalDetector.kt") "CPU_XNNPACK"
$checks["Class-aware NMS"] = Test-Text "NMS suppresses only same class" (Join-Path $Src "YoloTfliteAnimalDetector.kt") "existing\.classIndex == candidate\.classIndex"
$checks["Phone inference pacing"] = Test-Text "Camera analyzer has paced inference" (Join-Path $Src "AnimalSafetyAnalyzer.kt") "MIN_ANALYSIS_INTERVAL_MILLIS"
$checks["External inference pacing"] = Test-Text "External sources have paced inference" (Join-Path $Src "MainActivity.kt") "EXTERNAL_INFERENCE_MIN_INTERVAL_MILLIS"
$checks["Vehicle speed before TTC"] = Test-Text "Pipeline applies vehicle speed before motion/TTC" (Join-Path $Src "MainActivity.kt") "relativeMotionAnalyzer\.apply\(detectionsWithVehicleSpeed\)"
$checks["TTC vehicle-speed fallback"] = Test-Text "Relative motion has vehicle-speed TTC fallback" (Join-Path $Src "RelativeMotionAnalyzer.kt") "vehicle_speed_fallback"
$checks["GPS stale filtering"] = Test-Text "GPS provider filters stale locations" (Join-Path $Src "GpsSpeedProvider.kt") "MAX_LOCATION_AGE_MILLIS"
$checks["Warning confirmation"] = Test-Text "Warnings require confirmation" (Join-Path $Src "WarningManager.kt") "WEAK_EVIDENCE_CONFIRMATION_FRAMES"
$checks["Track age confirmation"] = Test-Text "Risk waits for confirmed tracks" (Join-Path $Src "RiskEvaluator.kt") "MIN_CONFIRMED_TRACK_FRAMES"
$checks["Visibility pacing"] = Test-Text "Detector paces visibility evaluation" (Join-Path $Src "YoloTfliteAnimalDetector.kt") "VISIBILITY_EVAL_INTERVAL_FRAMES"
$checks["Battery read pacing"] = Test-Text "Performance monitor caches battery reads" (Join-Path $Src "PerformanceMonitor.kt") "BATTERY_READ_INTERVAL_MILLIS"
$checks["Recorded dashcam mode enabled"] = Test-Text "Recorded dashcam mode enabled" (Join-Path $Src "MainActivity.kt") "RECORDED_DASHCAM_MODE_ENABLED = true"
$checks["Live dashcam enabled"] = Test-Text "Live dashcam streams enabled" (Join-Path $Src "MainActivity.kt") "LIVE_DASHCAM_STREAMS_ENABLED = true"
$checks["Hazard sharing disabled"] = Test-Text "Backend hazard sharing disabled by default" (Join-Path $Src "MainActivity.kt") "HAZARD_SHARING_ENABLED = false"
$checks["Nearby hazard alerts disabled"] = Test-Text "Nearby hazard alerts disabled by default" (Join-Path $Src "MainActivity.kt") "NEARBY_HAZARD_ALERTS_ENABLED = false"
$checks["Path relation stage"] = Test-File "Path relation estimator" (Join-Path $Src "PathRelationEstimator.kt")
$checks["Path before risk"] = Test-Text "Pipeline applies path relation before risk" (Join-Path $Src "MainActivity.kt") "riskEvaluator\.apply\(detectionsWithPathRelation\)"
$checks["Movement prediction stage"] = Test-File "Movement path predictor" (Join-Path $Src "MovementPathPredictor.kt")
$checks["Prediction before risk"] = Test-Text "Pipeline applies movement prediction before risk" (Join-Path $Src "MainActivity.kt") "riskEvaluator\.apply\(detectionsWithPrediction\)"
$checks["Predicted crossing fields"] = Test-Text "Detections carry predicted path crossing fields" (Join-Path $Src "AnimalDetection.kt") "predictedPathCrossing"
$checks["Predicted crossing risk"] = Test-Text "Risk evaluator uses predicted path crossing" (Join-Path $Src "RiskEvaluator.kt") "predicted path crossing"
$checks["Predicted crossing warning"] = Test-Text "Warning message reports movement toward lane" (Join-Path $Src "WarningManager.kt") "MOVING TOWARD LANE"
$checks["V5 plan"] = Test-File "Failure-driven V5 model plan" (Join-Path $Root "docs/plans/AIRACARE_V5_FAILURE_DRIVEN_MODEL_PLAN.md")
$checks["V5 human review"] = Test-Text "V5 plan requires human review" (Join-Path $Root "docs/plans/AIRACARE_V5_FAILURE_DRIVEN_MODEL_PLAN.md") "human-reviewed"
$checks["V5 hard negatives"] = Test-Text "V5 plan includes hard negatives" (Join-Path $Root "docs/plans/AIRACARE_V5_FAILURE_DRIVEN_MODEL_PLAN.md") "hard negatives"
$checks["V4 vs V5 comparison"] = Test-Text "V5 plan requires V4 vs V5 comparison" (Join-Path $Root "docs/plans/AIRACARE_V5_FAILURE_DRIVEN_MODEL_PLAN.md") "Compare V4 vs V5"
$checks["Sensor fusion plan"] = Test-File "Sensor fusion extension plan" (Join-Path $Root "docs/plans/AIRACARE_SENSOR_FUSION_EXTENSION_PLAN.md")
$checks["Sensor fusion last"] = Test-Text "Sensor fusion plan keeps fusion last" (Join-Path $Root "docs/plans/AIRACARE_SENSOR_FUSION_EXTENSION_PLAN.md") "Sensor fusion is a later extension"
$checks["No YOLO retrain for sensors"] = Test-Text "Sensor fusion plan says no YOLO retrain for sensors alone" (Join-Path $Root "docs/plans/AIRACARE_SENSOR_FUSION_EXTENSION_PLAN.md") "Do not retrain YOLO just because"
$checks["Separate sensor event dataset"] = Test-Text "Sensor fusion plan uses separate event dataset" (Join-Path $Root "docs/plans/AIRACARE_SENSOR_FUSION_EXTENSION_PLAN.md") "separate event dataset"
$checks["System architecture"] = Test-File "System architecture document" (Join-Path $Root "docs/plans/AIRACARE_SYSTEM_ARCHITECTURE.md")
$checks["Architecture input stack"] = Test-Text "Architecture includes phone camera and dashcam inputs" (Join-Path $Root "docs/plans/AIRACARE_SYSTEM_ARCHITECTURE.md") "Phone camera[\s\S]*Dashcam"
$checks["Architecture future sensors"] = Test-Text "Architecture includes future radar and thermal" (Join-Path $Root "docs/plans/AIRACARE_SYSTEM_ARCHITECTURE.md") "Future radar[\s\S]*Future thermal"
$checks["Architecture output stack"] = Test-Text "Architecture includes visual audio vibration and hazard sharing outputs" (Join-Path $Root "docs/plans/AIRACARE_SYSTEM_ARCHITECTURE.md") "visual alert[\s\S]*audio alert[\s\S]*vibration[\s\S]*hazard sharing"
$checks["Vibration alert player"] = Test-Text "Warning manager supports vibration alerts" (Join-Path $Src "WarningManager.kt") "VibrationAlertPlayer"
$checks["Vibration warning state"] = Test-Text "Warning state tracks vibration output" (Join-Path $Src "WarningManager.kt") "vibrationTriggered"
$checks["Field validation checklist"] = Test-File "Field validation checklist" (Join-Path $Root "docs/testing/AIRACARE_FIELD_VALIDATION_CHECKLIST.md")
$checks["Field FPS target"] = Test-Text "Field validation has FPS target" (Join-Path $Root "docs/testing/AIRACARE_FIELD_VALIDATION_CHECKLIST.md") "at least 2 FPS"
$checks["Field inference target"] = Test-Text "Field validation has inference target" (Join-Path $Root "docs/testing/AIRACARE_FIELD_VALIDATION_CHECKLIST.md") "below 1000 ms"
$checks["Field warning target"] = Test-Text "Field validation covers visual audio vibration warnings" (Join-Path $Root "docs/testing/AIRACARE_FIELD_VALIDATION_CHECKLIST.md") "Visual/audio/vibration warnings do not trigger reliably"
$checks["Phone camera product status"] = Test-Text "Status names phone camera minimum system" (Join-Path $Src "MainActivity.kt") "PHONE CAMERA MINIMUM SYSTEM"
$checks["Recorded product status"] = Test-Text "Status names recorded dashcam pipeline" (Join-Path $Src "MainActivity.kt") "RECORDED DASHCAM PIPELINE"
$checks["Live dashcam product status"] = Test-Text "Status names live dashcam pipeline" (Join-Path $Src "MainActivity.kt") "LIVE DASHCAM PIPELINE"
$checks["Recorded uses shared pipeline"] = Test-Text "Recorded video frames use shared Airacare pipeline" (Join-Path $Src "MainActivity.kt") "handleDetections\(detections\)"
$checks["MJPEG uses shared pipeline"] = Test-Text "MJPEG dashcam frames use shared Airacare pipeline" (Join-Path $Src "MainActivity.kt") "external_mjpeg_dashcam"
$checks["RTSP uses shared pipeline"] = Test-Text "RTSP dashcam frames use shared Airacare pipeline" (Join-Path $Src "MainActivity.kt") "external_rtsp_dashcam"
$checks["YOLO detector"] = Test-File "YOLO detector" (Join-Path $Src "YoloTfliteAnimalDetector.kt")
$checks["External NMS/parser"] = Test-Text "Detector has Airacare requested labels" (Join-Path $Src "YoloTfliteAnimalDetector.kt") "AIRACARE_REQUESTED_LABELS"
$checks["Recorded video mode"] = Test-File "Recorded video player" (Join-Path $Src "RecordedVideoFramePlayer.kt")
$checks["MJPEG dashcam"] = Test-File "MJPEG dashcam client" (Join-Path $Src "DashcamMjpegStreamClient.kt")
$checks["RTSP dashcam"] = Test-File "RTSP dashcam client" (Join-Path $Src "RtspDashcamStreamClient.kt")
$checks["GPS speed/location"] = Test-File "GPS speed provider" (Join-Path $Src "GpsSpeedProvider.kt")
$checks["Tracking"] = Test-File "Multi-object tracker" (Join-Path $Src "MultiObjectTracker.kt")
$checks["Distance estimation"] = Test-Text "Distance estimator includes elephant" (Join-Path $Src "DistanceEstimator.kt") "elephant"
$checks["Risk engine"] = Test-File "Risk evaluator" (Join-Path $Src "RiskEvaluator.kt")
$checks["Warnings"] = Test-File "Warning manager" (Join-Path $Src "WarningManager.kt")
$checks["Hazard sharing client"] = Test-File "Hazard sharing client" (Join-Path $Src "HazardSharingClient.kt")
$checks["Offline hazard queue"] = Test-File "Offline hazard queue" (Join-Path $Src "OfflineHazardQueue.kt")
$checks["Safety map screen"] = Test-File "Safety map activity" (Join-Path $Src "SafetyMapActivity.kt")
$checks["Nearby notifications"] = Test-File "Nearby notification manager" (Join-Path $Src "NearbyHazardNotificationManager.kt")
$checks["Benchmark logger"] = Test-File "Real-world benchmark logger" (Join-Path $Src "RealWorldBenchmarkLogger.kt")
$checks["Performance monitor"] = Test-File "Performance monitor" (Join-Path $Src "PerformanceMonitor.kt")

$failed = @($checks.GetEnumerator() | Where-Object { -not $_.Value })
""
"AIRACARE ANDROID SOFTWARE STATUS"
"Implemented checks: {0}" -f ($checks.Count - $failed.Count)
"Failed checks: {0}" -f $failed.Count
"OVERALL: {0}" -f ($(if ($failed.Count -eq 0) { "PASS" } else { "NOT READY" }))
""
"NOTE: Historical source-pattern checks only; this script does not compile the app. It does not prove production safety; phone/dashcam/GPS/audio/FPS must still be tested on real devices."
exit $(if ($failed.Count -eq 0) { 0 } else { 1 })
