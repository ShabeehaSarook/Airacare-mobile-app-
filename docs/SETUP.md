# Setup and handover

## Android workstation

1. Obtain the complete project, including Gradle wrapper and model assets.
2. Install Android Studio, Android SDK platform 35 and platform-tools.
3. Install JDK 17. Set Studio's Gradle JDK to that installation. Set JAVA_HOME
   to the same installation for terminal builds.
4. Open the repository root in Studio. Let Studio create your own
   `local.properties` with your Android SDK path.
5. Sync Gradle. The wrapper pins Gradle 8.9; root build pins AGP 8.7.3.
6. Use the `app` run configuration with a connected Android device (minimum API 23).

Do not copy another computer's `local.properties`, `.gradle/config.properties`
or `.idea` settings. A machine-specific `org.gradle.java.home`, if needed,
belongs in your user Gradle settings, not the shared project.

```powershell
java -version
.\gradlew.bat --version
powershell -File scripts\check-project.ps1
.\gradlew.bat :app:assembleDebug
```

The structure check only checks required files. It does not compile the app.

## Firebase and services

- Android client configuration belongs at `app/google-services.json`.
  Obtain it from the project owner or download it for the correct Firebase
  Android registration with package `com.airacare.animalsafety`.
- Firestore collections: `detections` and `captured_pets`.
- Review `database/firestore/firestore.rules` and the selected Firebase project before deployment.
  Never deploy rules to somebody else's project simply to get a build working.
- Capture backend base URL is in `PetCaptureRepository.kt`.
- Render backend must implement health, background removal, upload and delete
  endpoints. A local source copy is included: see [Backend setup](../backend/README.md).
  The existing online deployment remains independent until explicitly updated.
- PhotoRoom and Cloudinary secrets belong on the backend. Do not add them to
  Kotlin, browser JavaScript, documentation or version control.
- PhotoRoom Sandbox output can contain a watermark. Production requires legitimate
  Live access; the client must not hide it.
- OwnerIdProvider supplies the collection owner identifier. Do not assume two
  installations or web/Android clients automatically share one collection.

Firebase client config is not a server credential, but coordinate project access
and rules with the owner. Do not include service-account keys or signing secrets
in the handover.

## Install and test

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Unlock the phone, accept appropriate permissions, then follow [Testing](TESTING.md).
An install update preserves local data when signing/package compatibility allows it.
Do not uninstall to troubleshoot without considering local data loss.

## Web implementation

See [Frontend guide](../frontend/README.md) and [Web README](../frontend/web/README.md).
From the repository root:

```powershell
python -m http.server 5173 --directory frontend/web
```

Open localhost:5173 on that computer. Remote mobile camera access needs an
appropriate secure HTTPS host. `firebase.json` serves `frontend/web/`.
Android assets and browser model assets are separate; changing one does not update the other.

## Sharing the project

Include source, resources, model files, wrapper, database references and docs.
Provide client configuration and backend access through the project owner's agreed process.
Exclude build outputs, IDE/cache directories and `artifacts/local/`.
Build an APK after setup; do not treat a stale APK as proof the new machine can build.
