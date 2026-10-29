# Database reference

[airacare_detection_database.sql](airacare_detection_database.sql) is a schema reference.
The Android app creates its actual local SQLite database through
DetectionDatabaseHelper.kt, not by importing this file.

Firestore is separate: detections and captured_pets are managed through Android
repository classes. See [architecture](../docs/ARCHITECTURE.md) and
[historical SQLite notes](../docs/reference/AIRACARE_DATABASE_DOCUMENTATION.md).

