# Fire Remote Player

Android app for Fire tablets that plays video/streams and exposes a phone-friendly remote control page over local Wi-Fi.

## What it does

- Plays direct stream URLs on the tablet (`.m3u8`, MP4, and other ExoPlayer-supported formats).
- Shows a local control URL on the tablet screen (for example `http://192.168.1.25:8080`).
- Protects remote page/API with a simple PIN (default: `2468`).
- Lets your phone control playback by opening that URL in a browser.
- Supports remote actions: `load`, `play`, `pause`, `stop`, and `seek +/-10s`.

## Project layout

- `app/src/main/java/com/example/fireremoteplayer/MainActivity.kt`
- `app/src/main/java/com/example/fireremoteplayer/player/PlayerViewModel.kt`
- `app/src/main/java/com/example/fireremoteplayer/remote/HttpRemoteServer.kt`
- `app/src/main/java/com/example/fireremoteplayer/ui/MainScreen.kt`

## Build and install

This repo includes Gradle wrapper scripts and properties.
`gradle/wrapper/gradle-wrapper.jar` is still required and must be generated once on your machine.

1. Open the project root in Android Studio.
2. When prompted, use Android Studio's embedded Gradle/JDK.
3. Build the `app` module.
4. Enable Developer Options + ADB on your Fire tablet.
5. Install from Android Studio or with ADB.

To finalize wrapper binary:

```bash
gradle wrapper
```

## Build in Docker

Build debug APK in Docker (no local Android SDK needed):

```bash
docker build --platform=linux/amd64 -t fire-remote-player-builder .
```

Extract APK from container:

```bash
docker create --name fire-builder fire-remote-player-builder
docker cp fire-builder:/workspace/app/build/outputs/apk/debug/app-debug.apk ./app-debug.apk
docker rm fire-builder
```

Output APK:
- `./app-debug.apk` (after `docker cp`)

## GitHub Actions

- CI build workflow: `.github/workflows/android-build.yml`
- Release workflow: `.github/workflows/android-release.yml`

How it works:

1. Every push/PR to `main` builds `app-debug.apk` and uploads it as a workflow artifact.
2. Pushing a tag like `v1.0.0` builds release APK and publishes a GitHub Release with:
   `fire-remote-player-v1.0.0.apk`

## Use it

1. Connect tablet and phone to the same Wi-Fi network.
2. Launch the app on the Fire tablet.
3. On the tablet screen, copy the shown control URL.
4. Open that URL on your phone browser and enter the PIN shown on the tablet (`2468` by default).
5. Paste a stream URL and tap **Load**.

## Remote API

- `GET /api/status`
- `POST /api/load` body: `{ "url": "https://...", "autoPlay": true }`
- `POST /api/play`
- `POST /api/pause`
- `POST /api/stop`
- `POST /api/seek` body: `{ "positionMs": 120000 }`

All API requests require header `X-PIN: <pin>` (or `?pin=<pin>` query parameter).

## Notes

- This is designed for local-network control with simple PIN auth.
- For internet exposure, add stronger auth and TLS.
