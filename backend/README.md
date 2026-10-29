# Backend

This folder contains a local handover copy of the existing Python/Flask runtime.
Imported from the separate airacare-animal-detector project on 2026-10-28.
That project remains untouched. This copy is not proof of what is deployed on Render.

- web_backend.py: Flask API entry point and PhotoRoom/Cloudinary integration.
- src/: runtime detection, distance, tracking, risk and sensor dependencies.
- config/: existing configuration/calibration files; recalibrate for another camera.
- models/: backend model binaries and labels.
- requirements-web.txt: existing Python dependencies.
- ../frontend/backend-web/: this backend's bundled browser UI.
- .env.example: variable names only; load values into the environment yourself.

Run from this folder using Python 3.11 and a dedicated environment:

```powershell
python -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-web.txt
.\.venv\Scripts\python.exe -m flask --app web_backend run --port 5000
```

Linux production command, with backend as working directory:
`gunicorn web_backend:app --bind 0.0.0.0:$PORT --workers 1 --threads 2 --timeout 180`

PhotoRoom/Cloudinary secrets must be set on the server, never in frontend code.
No credentials were copied. Dependencies retain upstream version constraints;
this is not a reproducible production lockfile.

The root render.yaml describes an optional deployment from this combined repository.
Do not create a duplicate production service inadvertently. Existing Render settings
are unchanged; switching repositories requires an explicit deployment decision.

