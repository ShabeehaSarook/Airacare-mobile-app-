# Airacare Company Setup Guide

This guide explains how to obtain, understand, build and test the Airacare
project after handover.

## 1. What Airacare does

Airacare uses a camera and an AI model to detect road objects and animals. The
Android application can display the detected class, confidence, bounding box,
distance, movement, risk and warning state. Detection events can be stored in
Firebase Firestore.

For a confirmed dog or cat, the user can use Capture & Store:

```text
Dog/cat detected
  -> capture and crop
  -> background removal through Render/PhotoRoom
  -> transparent PNG preview
  -> user confirms Save
  -> Cloudinary stores the image
  -> Firestore stores the metadata
  -> My Collection displays the saved pet
```

The browser version provides camera detection through a web link. iPhone camera
access requires an HTTPS deployment and browser camera permission.

## 2. Technologies

### Android application

- Kotlin, Android SDK 35 and minimum API 23.
- Gradle 8.9 with Android Gradle Plugin 8.7.3.
- CameraX 1.4.1 for camera preview, analysis and image capture.
- TensorFlow Lite 2.16.1 for on-device detection.
- Firebase Firestore for cloud metadata and detection events.
- SQLite for local detection history and local fallback metadata.
- OkHttp 4.12.0 for backend HTTP requests.
- Glide 4.16.0 for collection image loading.

### Web application

- HTML, CSS and browser JavaScript.
- Firebase Web SDK 10.13.2 and Firestore.
- ONNX model assets for browser inference.
- Browser MediaDevices API for camera access.

### Backend and storage

- Python 3.11, Flask and Gunicorn.
- TensorFlow Lite runtime, Ultralytics, NumPy and OpenCV.
- PhotoRoom for background removal, with a backend fallback path.
- Cloudinary for transparent image storage.
- Firebase Firestore for detection and captured-pet metadata.
- Render for the deployed backend service.

## 3. Download the project

The repository is:

```text
https://github.com/ShabeehaSarook/Airacare-mobile-app-
```

Use Git:

```powershell
git clone https://github.com/ShabeehaSarook/Airacare-mobile-app-.git
cd Airacare-mobile-app-
```

The repository should be kept private because it contains model assets and
Firebase client configuration.

## 4. Folder guide

```text
app/          Android application source, resources and TFLite models
backend/      Flask API, processing code, configuration and backend models
frontend/web/ Firebase-hosted browser application
frontend/backend-web/ Browser UI served by the Flask backend
database/     Firestore rules and local SQLite schema reference
docs/         Setup, architecture, testing and handover documentation
models/       Model inventory and model-location guide
scripts/      Project validation scripts
gradle/       Gradle wrapper files
```

Generated folders such as `app/build`, `.gradle`, `.idea`, `.kotlin`, Python
`__pycache__` and `.venv` are not included. They are created locally when needed.

## 5. Build the Android application

Install:

- Android Studio
- JDK 17
- Android SDK Platform 35
- Android SDK Build Tools and Platform Tools
- USB debugging enabled on the Android phone, or an Android emulator

Open the project root in Android Studio:

```text
C:\Users\HP\AiracareAndroid
```

Android Studio may create `local.properties` automatically. That file is
machine-specific and must not be copied between computers.

From PowerShell, run:

```powershell
powershell -File scripts\check-project.ps1
.\gradlew.bat :app:assembleDebug
```

The APK will be created at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

Install it on a connected phone:

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

The Android package name is:

```text
com.airacare.animalsafety
```

## 6. Test the Android application

Grant the requested camera, location and notification permissions. Then test:

1. Open the app.
2. Start Driving Mode.
3. Confirm that the camera preview opens.
4. Test person, dog, cat, horse and cow detections.
5. Confirm that boxes and labels appear.
6. Confirm that risk and warning values update.
7. Check Firestore collection `detections`.
8. Detect a dog or cat.
9. Press Capture & Store.
10. Confirm the preview shows the processed transparent image.
11. Press Save to Collection.
12. Check Cloudinary and Firestore collection `captured_pets`.
13. Open My Collection and verify the image and metadata.
14. Delete the item and verify both image and metadata deletion.
15. Open Detection History and confirm local history still works.

Use Android Studio Logcat with these tags when diagnosing problems:

```text
FirestoreDetectionRepo
FirestoreDetectionRepo
```

Also filter by the package name `com.airacare.animalsafety`.

## 7. Firebase and database

The Android Firebase client file is:

```text
app/google-services.json
```

The Firestore collections used by the project are:

```text
detections       AI detection event records
captured_pets    saved dog/cat image metadata
```

Firestore rules are stored at:

```text
database/firestore/firestore.rules
```

The actual records are stored in the Firebase project, not in the downloaded
repository. Confirm the correct Firebase project and rules before deployment.

## 8. Backend and Capture & Store

The Android client calls the backend URL configured in:

```text
app/src/main/java/com/airacare/animalsafety/PetCaptureRepository.kt
```

The backend API source is:

```text
backend/web_backend.py
```

Important endpoints include:

```text
GET  /api/health
POST /api/remove-background
POST /api/captured-pets/upload
POST /api/captured-pets/delete
```

The deployed backend must have these environment variables configured:

```text
PHOTOROOM_API_KEY
AIRACARE_BACKGROUND_REMOVAL_ENGINE
CLOUDINARY_URL
```

The exact values must be transferred securely by the project owner. Never place
these secrets in Android code, browser JavaScript, GitHub, or documentation.

The local backend can be run for development:

```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-web.txt
.\.venv\Scripts\python.exe -m flask --app web_backend run --port 5000
```

Then check:

```text
http://127.0.0.1:5000/api/health
```

The local backend and the deployed Render backend are separate. Editing local
backend files does not update Render until the correct deployment is performed.

## 9. Run the web application

For the Firebase browser client, from the project root run:

```powershell
python -m http.server 5173 --directory frontend/web
```

Open:

```text
http://localhost:5173
```

This is suitable for desktop testing. iPhone camera testing requires the web
application to be deployed on HTTPS, for example through the approved Firebase
Hosting project.

The browser model is separate from the Android model. Do not replace one model
without checking its labels, tensor layout and preprocessing.

## 10. Security and handover rules

Never commit or share:

- PhotoRoom API keys
- Cloudinary API secrets
- Firebase service-account JSON files
- Android signing keys
- Real `.env` files
- `local.properties`

The included `backend/.env.example` contains variable names only. The included
`app/google-services.json` is Firebase client configuration needed by Android,
but the GitHub repository should remain private.

## 11. Current completion status

Implemented in source:

- Android camera detection, tracking, distance, risk and warnings.
- Local and Firestore detection history.
- Dog/cat Capture & Store flow.
- Backend background removal, Cloudinary upload and deletion APIs.
- Firestore `captured_pets` metadata.
- My Collection filters, refresh, image loading and deletion.
- Browser camera detection and Firestore saving.
- Project organization and documentation.

The next developer must still verify on the target phone and deployed services:

- Real Android detection quality.
- Dog/cat capture quality and transparency.
- PhotoRoom and Cloudinary production responses.
- Firestore rules and saved records.
- Delete behavior.
- iPhone camera access over HTTPS.

These are external tests and cannot be guaranteed by a source checkout alone.
