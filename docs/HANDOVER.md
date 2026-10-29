# Airacare Handover Guide

This document is the shortest path for the next developer. The project has
three runtime parts, so do not assume that one model or one deployment controls
all of them.

## Technology stack

### Android

- Kotlin and Android SDK 35, minimum Android API 23.
- Gradle 8.9 and Android Gradle Plugin 8.7.3.
- CameraX 1.4.1 for camera preview, analysis and capture.
- TensorFlow Lite 2.16.1 for on-device inference.
- OkHttp 4.12.0 for backend requests.
- Glide 4.16.0 for collection images.
- Firebase Firestore for detection and captured-pet metadata.
- SQLite through the Android SQLite APIs for local detection history and local
  collection fallback.

### Web

- HTML, CSS and browser JavaScript.
- Firebase Web SDK 10.13.2 and Firestore.
- ONNX model assets for browser inference.
- Browser MediaDevices camera API; iPhone camera use requires HTTPS.

### Backend and storage

- Python 3.11, Flask and Gunicorn.
- TensorFlow Lite runtime, Ultralytics, NumPy and OpenCV.
- PhotoRoom API for background removal, with an OpenCV fallback path.
- Cloudinary for captured PNG image storage.
- Firebase Firestore for metadata and detection events.
- Render is the current intended backend hosting platform. The local backend
  copy and the deployed Render service are separate until redeployed.

## Completion status

### Implemented in this project

- Android live camera detection with bounding boxes and supported class labels.
- Distance, movement, risk and warning pipeline.
- Detection history in local storage and Firestore event saving.
- Dog/cat Capture & Store flow, crop adjustment and preview flow.
- Backend background-removal endpoints and transparent PNG diagnostics.
- Cloudinary upload/delete endpoints and Firestore `captured_pets` metadata.
- Android My Collection screen with filters, refresh, image loading and delete.
- Firebase browser detection client and Firestore detection saving.
- Local Flask backend copy with health, detection, background-removal, upload and
  delete endpoints.
- Project documentation, folder organization and structure validation script.

### Verified locally

- Required project files: `21/21` present.
- Android debug build completed successfully before generated build files were
  cleaned for handover.
- Python backend files compiled successfully.
- Flask backend served its browser files successfully.
- Backend health check loaded the local TFLite model successfully.
- Firebase configuration paths resolve to existing local files.

### Still requires the next developer's real environment

- Rebuild the APK after checkout and test it on the target Samsung phone.
- Confirm camera, location and notification permissions on that phone.
- Perform a real dog/cat Capture & Store test through the deployed Render service.
- Confirm PhotoRoom Live/Sandbox behavior, Cloudinary output and Firestore rules.
- Verify delete behavior in both Cloudinary and Firestore.
- Deploy or update Firebase/Render only after confirming the correct project and
  service ownership.

The project is implementation-complete for handover, but these external tests
cannot be honestly marked complete from a local source checkout alone.

## 1. Build the Android app

Requirements: Android Studio, Android SDK 35, JDK 17 and a connected Android
device or emulator.

```powershell
cd C:\Users\HP\AiracareAndroid
powershell -File scripts\check-project.ps1
.\gradlew.bat :app:assembleDebug
```

The APK is generated at:
`app\build\outputs\apk\debug\app-debug.apk`.

Install it with:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

## 2. Runtime ownership

| Runtime | Main location | Current model | Data/services |
| --- | --- | --- | --- |
| Android | `app/` | `app/src/main/assets/yolo26n_w8a32.tflite` | CameraX, local SQLite, Firebase, Render API |
| Firebase web | `frontend/web/` | `frontend/web/models/airacare_yolo.onnx` | Browser camera and Firestore |
| Flask backend | `backend/` | `backend/models/airacare_custom.tflite` | Detection API, PhotoRoom, Cloudinary |

The models and class lists are maintained separately. Verify tensor shapes and
labels before replacing a model.

## 3. Important Android flows

- Live detection: `MainActivity.kt` -> `AnimalSafetyAnalyzer.kt` -> detector.
- Risk and warnings: `RiskEvaluator.kt` and `WarningManager.kt`.
- Detection history: `DetectionHistoryRepository.kt` and `FirestoreDetectionRepository.kt`.
- Capture & Store: `PetCropper.kt` -> `PetCaptureRepository.kt` -> Render -> Cloudinary.
- Collection: `CapturedPetsActivity.kt` and `CapturedPetRepository.kt`.

## 4. Services and secrets

Firebase Android configuration is in `app/google-services.json`. Firestore rules
are in `database/firestore/firestore.rules`. The Android backend URL is defined
in `PetCaptureRepository.kt`.

PhotoRoom and Cloudinary secrets must stay in the Render environment. Use
`backend/.env.example` only as a variable-name reference. Never commit a real
`.env`, service-account key, Cloudinary secret, or signing key.

The local `backend/` is a handover copy. Editing it does not update the existing
Render service. Confirm the Render repository, environment variables, health URL,
and deployment owner before changing production.

## 5. Local backend

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-web.txt
.\.venv\Scripts\python.exe -m flask --app web_backend run --port 5000
```

Check `http://127.0.0.1:5000/api/health` before testing API calls.

## 6. Safe cleanup policy

Do not distribute generated folders such as `app/build`, `.gradle`, `.idea`,
`.kotlin`, `.firebase`, Python `__pycache__`, `.venv`, or `artifacts/local`.
They are ignored and recreated as needed. Keep all source, model, configuration,
Firebase, Gradle wrapper and documentation files.

## 7. First test after handover

1. Run the structure check and Android build.
2. Install the APK and grant camera, location and notification permissions.
3. Start Driving Mode and verify person/dog/cat/horse/cow detection.
4. Detect a dog or cat, open Capture & Store, preview, save, and open My Collection.
5. Confirm `detections` and `captured_pets` in Firestore.
6. Test delete and verify the Cloudinary image and Firestore metadata are removed.
7. Test the browser client over HTTPS on an iPhone.

For failures, collect Logcat, backend `/api/health`, Firebase rules/project name,
and the exact failing step. Do not paste API keys into tickets or chat.
