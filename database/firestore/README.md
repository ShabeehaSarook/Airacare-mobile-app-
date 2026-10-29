# Firestore

firestore.rules is the existing rules file moved from the root, without rule changes.
The root firebase.json now references this path.

Collections used by Android:
- detections: selected detection events.
- captured_pets: captured-pet metadata and Cloudinary URLs/public IDs.

Actual Firestore data lives in Firebase, not this directory. SQLite reference SQL
remains at ../airacare_detection_database.sql; the app creates its own device database.
No cloud rules were deployed and no database records were changed.

