# Airacare Field Validation Checklist

> Historical reference/planning document. Start with [the current project guide](../README.md).
> File paths shown as code are relative to the repository root. Planned features are not proof of runtime availability.

Airacare is not accepted as working properly until this checklist passes on the real Android phone.

## Build To Install

Use the debug APK from:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Required Pass Targets

- App starts without model error.
- Driving Mode starts and shows `PHONE CAMERA MINIMUM SYSTEM`.
- Phone camera preview remains live for at least 30 minutes.
- FPS is stable enough for warnings to feel live, target at least 2 FPS on the current phone.
- Inference is consistently below 1000 ms on the current phone.
- Overlay boxes align with visible people/animals.
- Empty roads do not produce repeated false CAT/DOG/HORSE/ELEPHANT alerts.
- People and common animals are detected in day footage.
- Night, rain, far-object, and side-crossing misses are logged, not used for retraining until human-reviewed.
- GPS speed/bearing shows valid values outdoors while moving.
- Closing speed and TTC become available for approaching in-path or near-path objects.
- Path relation changes correctly between `IN_PATH`, `NEAR_PATH`, and `OUTSIDE_PATH`.
- Roadside cow/deer moving toward the lane produces `MOVING TOWARD LANE` before entering the lane.
- LOW/MEDIUM/HIGH risk changes match the visible scene.
- Visual warning appears for MEDIUM/HIGH risk.
- Audio warning plays for MEDIUM/HIGH risk.
- Vibration warning triggers for MEDIUM/HIGH risk.
- Recorded dashcam video uses the same Airacare pipeline.
- Live MJPEG/HTTP dashcam uses the same Airacare pipeline.
- Live RTSP dashcam uses the same Airacare pipeline if the dashcam codec works with Android.

## Test Clips

Recorded dashcam validation must include:

- day
- night
- rain
- animals crossing
- roadside animals
- far animals
- empty roads
- side-crossing cow/deer
- people near road

## Failure Logging

Use `Benchmark`, `FP`, and `FN` during tests. Pull logs from:

```text
Android/data/com.airacare.animalsafety/files/airacare_benchmarks/session_*/
Android/data/com.airacare.animalsafety/files/airacare_performance/performance_*.csv
```

Use failures only for diagnosis until the runtime pipeline is stable. Use them for V5 only after human review.

## Stop Conditions

Do not move to V5, sensor fusion, production backend, or safety claims if any of these remain true:

- FPS stays below 2 FPS on the target phone.
- Inference stays above 1000 ms.
- Boxes are visibly misaligned.
- False animal alerts repeat on empty roads.
- TTC is mostly unavailable for obvious approaching objects.
- GPS speed/bearing is stale or wrong outdoors.
- Visual/audio/vibration warnings do not trigger reliably.
