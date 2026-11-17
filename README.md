# Preset Camera (beta v1.0)

An Android camera app that looks like the iPhone 18 Pro Max camera and
automatically applies Shihab's photo preset to every photo — on-device,
with no internet.

## Two ways to take a photo

### 1. In-app camera (primary path)

1. Tap the shutter. The photo is captured with CameraX into a private
   temp file (it is never saved to the gallery).
2. Shihab's preset is applied on-device: dehaze (1–99% percentile
   stretch), gentle S-curve, golden warmth, green-only saturation boost,
   saturation / brightness / contrast, mild unsharp mask.
3. Only the graded photo is saved to the gallery
   (`DCIM/PresetCamera`). The original temp file is deleted.

### 2. Default-camera auto-preset (folder watcher)

When you take a photo with your phone's normal camera app, Preset
Camera notices the new photo in `DCIM/Camera`, applies the same preset
on-device, saves only the graded copy to `DCIM/PresetCamera`, and
deletes the original.

How it runs: a foreground service watches the camera folder with a
MediaStore ContentObserver (near real time, 2 s debounce), and a
WorkManager sweep every 15 minutes catches anything missed. The
service shows a small persistent notification; tapping it opens the
watcher settings. The watcher restarts after a reboot when enabled.

**Delete permission — one time only, never a prompt per photo:**
the first time the app opens, onboarding asks once for the system's
media-management grant (Android 12+: `MANAGE_MEDIA`, via
`Settings.ACTION_REQUEST_MANAGE_MEDIA`) — or all-files access on
Android 11 (`MANAGE_EXTERNAL_STORAGE`). After that one grant, the
watcher deletes originals silently. If the grant is missing, graded
copies are still saved, but originals stay queued (visible in watcher
settings) until the grant is given. The app never uses a per-photo
confirmation dialog as the normal path.

**Honest limits:**
- The graded copy appears a few seconds after the shot.
- The original is briefly visible in the gallery until it is deleted.
- If Android pauses the watcher to save battery, photos are still
  caught by the 15-minute sweep. The settings screen offers a
  battery-optimisation exemption for this reason.

The 6-dot controls button on the camera opens the watcher settings
(grant status, originals waiting, battery exemption, on/off).

## Status

- PHOTO mode fully works.
- VIDEO and REFERENCE modes show "Coming soon" for now.
- The top-row values are display labels in v1; the ƒ button shows
  "Coming soon".

## Download (beta)

`releases/preset-camera-v1.0.apk` — built by GitHub Actions on every
push to `main`.

## Build locally

```sh
./gradlew assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 34).
