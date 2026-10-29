# Airacare Detection Database

> Historical reference/planning document. Start with [the current project guide](../README.md).
> File paths shown as code are relative to the repository root. Planned features are not proof of runtime availability.

## Database Purpose

The Airacare Android app stores every detected road animal/class in a local database. When the app detects a class such as `dog`, it saves the detection label and the related safety details.

## Database Used

- Database type: SQLite local database
- Android database file: `airacare_detections.db`
- Main table: `detection_history`
- Device path: `/data/data/com.airacare.animalsafety/databases/airacare_detections.db`

## Project Files

- Database schema helper: `app/src/main/java/com/airacare/animalsafety/DetectionDatabaseHelper.kt`
- Database save/read logic: `app/src/main/java/com/airacare/animalsafety/DetectionHistoryRepository.kt`
- Detection database model: `app/src/main/java/com/airacare/animalsafety/DetectionRecord.kt`
- In-app database screen: `app/src/main/java/com/airacare/animalsafety/DetectionHistoryActivity.kt`
- SQL schema reference: `database/airacare_detection_database.sql`

## Data Stored

The database stores:

- Detection label, for example `dog`, `cow`, `cat`
- AI confidence score
- Risk level
- Risk reason
- Distance from vehicle
- Distance source and quality
- Vehicle speed
- GPS latitude and longitude
- GPS bearing and accuracy
- Movement direction
- Vehicle path zone
- Predicted path crossing details
- Time to collision
- Bounding box position
- Camera source
- Visibility condition
- Warning level
- Audio and vibration warning status
- Detection timestamp
- Sync status

## Demo Steps

1. Run the app.
2. Tap `Start Driving Mode`.
3. Let the app detect an animal/class such as dog.
4. Tap the `DB` button.
5. Show the saved record in the `Detection Database` screen.

## Example Stored Record

```text
label: dog
confidence: 0.91
risk_level: HIGH
distance_meters: 8.5
vehicle_speed_kmh: 42.1
latitude: 6.9271
longitude: 79.8612
movement_direction: TOWARD_LANE
path_zone: IN_PATH
warning_level: DANGER
sync_status: LOCAL
```

## Android Studio Check

Open:

```text
View > Tool Windows > App Inspection > Database Inspector
```

Select:

```text
com.airacare.animalsafety
```

Open:

```text
airacare_detections.db > detection_history
```
