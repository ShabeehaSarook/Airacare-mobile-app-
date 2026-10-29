# Android application

This is the native Android frontend and on-device detection module.
Kept at app/ so Android Studio, Gradle, manifest/resource paths and APK output remain stable.

- src/main/java/com/airacare/animalsafety/: Kotlin implementation.
- src/main/res/: layouts, icons, strings and themes.
- src/main/assets/: Android TFLite models and labels.
- src/main/AndroidManifest.xml: permissions and activities.
- google-services.json: Firebase Android client configuration.
- build.gradle.kts: module dependencies and SDK settings.
- build/: generated outputs, not source.

See [setup](../docs/SETUP.md) and [source map](../docs/PROJECT_MAP.md).

