# Airacare Production Software Validation Checklist

This checklist tracks the software work after the Airacare V4 model integration.

> Historical reference/planning document. Start with [the current project guide](../README.md).
> Paths shown as code are relative to the repository root; plans are not runtime verification.

## Phase 1 model freeze

- Keep dataset: `airacare_v4_road_safety_final`
- Keep model: 15-epoch custom model, `airacare_yolo_v4_15epoch_finetune`
- Do not retrain yet.
- Do not rebuild V4 yet.
- Reason: the Android app already has a working bundled model; reopening dataset work now slows the completion path.

## Phase 2 Android runtime stabilization

- Focus only on Android runtime until phone testing is reasonably stable.
- Current blockers: low FPS, multi-second inference, false positives, false negatives, inconsistent GPS, incomplete closing speed/TTC.
- Runtime guardrails now tracked: CPU/XNNPACK default, paced inference, class-aware NMS, vehicle-speed TTC fallback, GPS quality/staleness filtering, warning confirmation.
- Do not move to dataset/model rebuild work until real-device FPS, inference latency, detection quality, GPS speed/bearing, TTC, and warning confirmation are validated.

## Phase 3 Minimum Complete Airacare System

- Product mode: phone camera only.
- Required pipeline: phone camera -> YOLO detection -> tracking -> distance -> closing speed -> TTC -> path relation -> LOW/MEDIUM/HIGH -> audio + visual warning.
- Advanced source/sensor-fusion work is disabled by default: live dashcam streams, safety map, backend sharing, and nearby hazard notifications. Recorded dashcam mode is enabled only as the safe Phase 4 test path.
- Do not finish sensor fusion until the phone-camera pipeline is stable on the real phone.

## Phase 4 Recorded dashcam mode

- Product mode: recorded dashcam video.
- Required pipeline: recorded video frame -> YOLO detection -> tracking -> distance -> closing speed -> TTC -> path relation -> LOW/MEDIUM/HIGH -> audio + visual warning.
- Purpose: safely test real roads while debugging without driving.
- Required clip set: day, night, rain, animals crossing, roadside animals, far animals, empty roads.
- Phase 4 gate: validate phone-camera and recorded-video pipelines before enabling live dashcam streams.

## Phase 5 Live dashcam mode

- Product mode: MJPEG / RTSP / HTTP dashcam.
- Required pipeline: live dashcam frame -> YOLO detection -> tracking -> distance -> closing speed -> TTC -> path relation -> LOW/MEDIUM/HIGH -> audio + visual warning.
- Do not build separate AI logic for dashcam. Live dashcam frames must reuse the same Airacare pipeline as phone camera and recorded video.
- Keep backend hazard sharing, safety map, and broader sensor fusion disabled until the live dashcam input is stable.

## Phase 6 Movement/path-crossing prediction

- Required pipeline addition: tracking history -> movement direction -> predicted animal path -> predicted vehicle path -> intersection check.
- Risk behavior: a cow/deer beside the road and moving toward the lane while the vehicle is approaching should raise MEDIUM/HIGH risk before it fully enters the road.
- Prediction must reuse tracked detections from the same Airacare pipeline; do not add separate dashcam AI logic.
- Validate with recorded clips first, then phone camera/live dashcam.

## Phase 7 Failure-driven V5 gate

- Do not start V5 until the Android runtime works well enough to produce trustworthy failure evidence.
- V5 inputs: missed people, missed animals, false CAT, false DOG, false HORSE, false ELEPHANT, night misses, far-object misses, side-crossing misses, hard negatives.
- Every V5 input must be human-reviewed.
- Build V5 as: V4 + verified real-world failures.
- Train V5 once.
- Compare V4 vs V5 on the same reviewed validation set.
- Replace V4 only if V5 is clearly better and does not regress Android runtime performance.

## Phase 8 Sensor fusion extension

- Sensor fusion is last and remains a separate extension.
- Current project remains the base: Camera YOLO -> tracking -> distance -> motion/TTC -> path prediction -> risk -> warning.
- Later inputs: Camera YOLO + GPS + phone IMU + radar + thermal.
- Fusion output: better distance -> better motion -> better TTC -> better risk.
- Do not retrain YOLO just because radar, GPS, IMU, or thermal data is added.
- Sensor data must go into a separate event dataset.
- Keep V5 image data limited to human-reviewed detection failures and hard negatives.

## Implemented prototype features

- Custom Airacare TFLite detector: `airacare_custom.tflite`
- Classes: person, dog, cat, horse, cow, deer, goat, elephant
- Phone CameraX detection
- MJPEG Wi-Fi dashcam input
- RTSP dashcam input path
- Recorded video testing mode
- GPS speed, location, and bearing capture
- Distance, relative motion, TTC, path-zone, and LOW/MEDIUM/HIGH risk pipeline
- Audio and visual warnings
- Vibration warnings
- Start/Stop Driving Mode
- Camera interruption recovery attempts
- Backend hazard sharing API
- Safety map screen
- Nearby hazard polling notifications
- Offline hazard queue for failed reports
- Real-world benchmark logging
- False-positive and false-negative marking
- Performance monitoring: FPS, latency, memory, battery, temperature
- Model version tracking via `AiracareModelInfo`

## Hardware validation still required

- Complete `AIRACARE_FIELD_VALIDATION_CHECKLIST.md`.
- Test on the real Android phone used for demonstration.
- Test phone CameraX for at least 30 minutes.
- Test MJPEG dashcam URL.
- Test RTSP dashcam URL. Android RTSP support depends on the dashcam stream codec and device firmware.
- Test recorded videos from car, bike, tuk-tuk, bus/van, village roads, highway, night, rain, and low light.
- Test video call interruption and camera recovery.
- Test Bluetooth, silent mode, Do Not Disturb, and speaker volume for warnings.
- Test GPS speed accuracy while moving.
- Test heating, battery drain, RAM, FPS, and inference latency.

## Benchmark procedure

1. Install the debug APK on the phone.
2. Start Driving Mode.
3. Tap `Benchmark` to start a benchmark session.
4. Drive or play recorded dashcam videos.
5. Tap `FP` when the app detects something wrong.
6. Tap `FN` when the app misses a visible target.
7. Tap `Benchmark` again to stop logging.
8. Pull logs from:

```text
Android/data/com.airacare.animalsafety/files/airacare_benchmarks/session_*/
```

Files:

```text
detections.csv
failure_notes.csv
summary.txt
```

## Performance logs

Performance CSV files are saved under:

```text
Android/data/com.airacare.animalsafety/files/airacare_performance/performance_*.csv
```

Tracked fields:

```text
fps
inference_ms
total_pipeline_ms
skipped_frames
memory
battery_percent
battery_temp_c
```

## Backend hardening notes

The backend supports optional API key auth through:

```text
AIRACARE_API_KEY
```

If set, clients must send:

```text
X-Airacare-Api-Key: <key>
```

The prototype Android client currently sends:

```text
airacare-dev-key
```

For production, replace this with secure device registration/token handling. Do not ship a fixed public API key in a production APK.

## Remaining production gaps

- Firebase/FCM true push notifications are not implemented. Current notifications are polling based.
- USB/UVC dashcam input is not fully implemented.
- Backend still needs production deployment, HTTPS, durable rate limiting, auth, monitoring, and abuse control.
- Phone IMU, radar, thermal, and fusion engine remain future extensions.
- Real-world benchmark needs manually reviewed ground truth before making safety claims.
- V5 must be built only from human-reviewed real-world failures and hard negatives.
- Sensor fusion data must be stored in a separate event dataset, not mixed into the YOLO image dataset.
- Distance/TTC/risk thresholds must be validated against known-distance road tests.
- The system should be described as a driver-assistance prototype/pilot until field validation is complete.
