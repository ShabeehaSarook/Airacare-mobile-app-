# Frontend applications

- [web/](web/README.md): the browser project previously named web-dashboard.
  Firebase Hosting serves this directory; its relative URLs and model folder stay intact.
- backend-web/: the existing browser UI bundled with the imported Flask backend.
  It remains separate because its code and API expectations differ from web/.
- [../app/](../app/README.md): native Android frontend and on-device processing,
  kept at the standard Android module path.

To serve the Firebase browser client locally from the repository root:
`python -m http.server 5173 --directory frontend/web`.
Use HTTPS for remote phone-camera access. To run backend-web, start Flask using
the backend README; it is served by Flask at port 5000.

Moving these files locally does not publish a new website.

