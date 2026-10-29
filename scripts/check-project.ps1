# File-presence check only: does not build, connect to services, or test inference.
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Required = @(
    "settings.gradle.kts", "app/build.gradle.kts", "gradlew.bat",
    "gradle/wrapper/gradle-wrapper.jar",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/assets/yolo26n_w8a32.tflite",
    "app/src/main/assets/coco_labels.txt",
    "app/src/main/java/com/airacare/animalsafety/MainActivity.kt",
    "app/src/main/java/com/airacare/animalsafety/CapturedPetsActivity.kt",
    "docs/SETUP.md", "docs/PROJECT_MAP.md", "docs/ARCHITECTURE.md",
    "backend/web_backend.py", "backend/requirements-web.txt",
    "backend/models/airacare_custom.tflite",
    "frontend/web/index.html", "frontend/web/models/airacare_yolo.onnx",
    "frontend/backend-web/index.html", "database/firestore/firestore.rules",
    "models/README.md", "firebase.json"
)
$Missing = @($Required | Where-Object { -not (Test-Path -LiteralPath (Join-Path $Root $_) -PathType Leaf) })
foreach ($Path in $Missing) { Write-Host "MISSING: $Path" }
foreach ($LocalFile in @("local.properties", "app/google-services.json")) {
    if (-not (Test-Path -LiteralPath (Join-Path $Root $LocalFile))) {
        Write-Host "SETUP NEEDED: $LocalFile (see docs/SETUP.md)"
    }
}
Write-Host ("Required files: {0}/{1}" -f ($Required.Count - $Missing.Count), $Required.Count)
Write-Host "Next: ./gradlew.bat :app:assembleDebug, then real-device tests."
if ($Missing.Count -gt 0) { exit 1 }
exit 0
