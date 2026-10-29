# Airacare Android

Android animal/road-object detection with dog/cat Capture & Store and My Collection.
Package: `com.airacare.animalsafety`.

## Start here

1. Follow [Setup](docs/SETUP.md) to build on your own computer.
2. Read the [Handover guide](docs/HANDOVER.md) for the complete system overview.
3. Read the [Company setup guide](docs/COMPANY_SETUP_GUIDE.md) for step-by-step setup.
4. Read [Project map](docs/PROJECT_MAP.md) to find the right files.
5. Read [Current architecture](docs/ARCHITECTURE.md) for the data flow.
6. Use [Testing and troubleshooting](docs/TESTING.md) before a demonstration.
7. See [Documentation index](docs/README.md) for retained plans and references.

## Current source configuration

- Kotlin Android app, CameraX, TensorFlow Lite, OkHttp and Glide.
- Active Android model: `yolo26n_w8a32.tflite` with `coco_labels.txt`.
- Supported displayed classes: person, dog, cat, horse and cow.
- Retained custom model assets are not the active detector selected by MainActivity.
- Camera, recorded video and live dashcam inputs feed the safety pipeline.
- Capture & Store: dog/cat -> camera photo -> adjustable crop -> backend/PhotoRoom
  -> PNG preview -> user saves -> Cloudinary image + Firestore metadata.
- Detection history uses SQLite and Firestore. My Collection uses Firestore plus
  local metadata fallback; locally stored metadata does not guarantee offline image access.
- Hazard sharing and nearby hazard alerts are disabled in current source flags.

These describe implementation, not a guarantee of detection accuracy or service uptime.
Model tensor sizes are read at runtime; do not assume that the Android and web
models use the same resolution or label order.

## Repository layout

| Folder/file | Purpose |
| --- | --- |
| `app/` | Android source, resources, model assets and module build |
| `docs/` | Onboarding, architecture, file map, testing, historical plans |
| `scripts/` | Developer checks and explicitly labelled legacy checks |
| `backend/` | Flask API, PhotoRoom/Cloudinary integration, Python runtime and models |
| `frontend/` | Firebase browser client and separate Flask-served browser client |
| `database/` | Firestore rules and SQLite schema reference; not a database server |
| `models/` | Model inventory describing each runtime's model files |
| `model-export/` | Reserved model-export workspace |
| `artifacts/local/` | Ignored local screenshots and test outputs |
| `gradle/`, `gradlew*` | Pinned Gradle wrapper |
| `firebase.json`, `.firebaserc`, `render.yaml` | Firebase and optional Render deployment configuration |
| `local.properties`, `.idea/`, `.gradle/` | Per-machine settings/caches; do not distribute |
| `app/build/` | Generated Android outputs |

## Build

After setup, from the repository root:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

Backend source is included as a local handover copy. See [Backend setup](backend/README.md),
[Frontend guide](frontend/README.md), [Database guide](database/firestore/README.md)
and [Model inventory](models/README.md). Android still calls the configured Render
endpoint in `PetCaptureRepository.kt`. Local organization does not update that deployment.
