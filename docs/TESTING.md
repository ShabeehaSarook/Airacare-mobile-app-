# Testing and troubleshooting

## Before handover

- Run `powershell -File scripts/check-project.ps1` from the root.
- Run `.\gradlew.bat :app:assembleDebug`.
- Install the newly generated APK on a phone.
- Test camera permission denied/granted, start/stop, and detection of supported classes.
- Test dog/cat capture, crop correction, background-removal failure and success,
  preview cancellation, explicit Save, collection reload, all filters and delete confirmation.
- Verify the actual saved PNG dimensions/transparency, not just the filename.
- Check readable collection layout at normal and enlarged font settings.
- Test network loss and reconnect. Local metadata is not an offline image guarantee.

Do not delete valuable records for a test; create disposable test captures.
Do not call the app production-ready based only on a successful build.

## Useful diagnostics

Logcat tags: AiracareCamera, YoloTfliteDetector, PetCaptureRepo,
FirestoreDetectionRepo, CapturedPetsActivity.

Look for model input/output tensors, captured photo dimensions, crop dimensions,
PhotoRoom output and Cloudinary upload input diagnostics, and save callbacks.
A backend health response with configured services does not validate credentials,
credits, transparency or database writes.

## Common problems

| Symptom | Action |
| --- | --- |
| Unknown --jvm-vendor on updateDaemonJvm | Gradle 8.9 does not support that flag. Select JDK 17 and run assembleDebug normally. |
| Requested internal only, but not enough space | Free phone internal storage; preserve app data. |
| Gradle cannot create cache/JAR | Check PC disk space and filesystem permissions. |
| Missing SDK | Set local.properties via Studio on this computer. |
| Missing google-services.json | Obtain correct Android Firebase client config. |
| Capture/backend timeout | Check internet and backend logs; health alone is not an end-to-end test. |
| Null GPS/speed/bearing/TTC | Check permissions, freshness and available measurements; do not fabricate values. |
| Collection image looks soft | Inspect original capture/crop pixel size and image URL, not just UI dimensions. |
| 16 KB native-library warning | Separate future-device compatibility work; inspect bundled libraries before upgrading. |

## Legacy checks

`scripts/legacy/verify_airacare_android_status.ps1` is retained as historical V4
inspection code. It contains stale model expectations and is not a release gate.
It does not run Gradle or test a phone. Use current build and runtime checks above.

