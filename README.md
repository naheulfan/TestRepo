# Beta Censor (Android)

Live, on-device overlay that draws "beta-style" censor bars over selected body parts while the
Reddit app (`com.reddit.frontpage`) is in the foreground. Nothing is uploaded anywhere.

Toggleable parts: eyes, breasts, ass, pussy, dicks, armpits, feet, belly buttons
(+ optional "covered" mode that also censors clothed parts).
Styles: black bar, pink bar, pixel mosaic, heart stickers, caution tape, with a custom caption.

## Setup

1. **The model is included** at `app/src/main/assets/320n.onnx` (NudeNet v3 320n, YOLOv8n export;
   AGPL-3.0 per its Ultralytics metadata — check the license before redistributing).
   CI verifies its SHA-256 so a corrupted/replaced file fails the build instead of the app.
   Prebuilt debug APKs are attached to each GitHub Actions run (`BetaCensor-apk` artifact).
2. Open the folder in Android Studio (Koala or newer), let it sync, and run on a phone
   (Android 10 / API 29+).
3. In the app: tap **Open accessibility settings** → enable *Beta Censor*.
4. Tap **Start censoring** and accept the screen-capture prompt (choose *entire screen* on older Android).
5. In Reddit, disable "Blur NSFW images" so the detector sees the real picture.

## How it works

- `CaptureService` mirrors the screen at half resolution via MediaProjection and runs NudeNet
  (YOLOv8n, ONNX Runtime, CPU) on each frame, only while Reddit is foreground.
- `PartMapper` converts detector classes to your toggles. Eyes = a bar across the upper part of each
  detected face; belly button = a small square in the middle of the detected belly.
- `Tracker` smooths boxes between frames.
- `OverlayAccessibilityService` draws each censor as its own tiny accessibility-overlay window.
  Accessibility overlays let taps pass through (normal overlays block touches on Android 12+).
  Each window is `FLAG_SECURE`, so it shows as pure black in the capture; a box that's still black
  is kept alive, which stops censors from flickering off once they've hidden the part.

## Known limitations

- **Android only.** iOS doesn't allow apps to read or draw over other apps' screens.
- During fast scrolling, censors follow at detection speed (~10–20 fps on a recent phone), so
  you may see a brief flash at the leading edge. Raise "Bar size" to reduce this.
- Detection isn't perfect: expect occasional misses on unusual angles, drawings/hentai, or
  tiny thumbnails, and occasional false positives at high sensitivity.
- Because censor windows are secure, your own screenshots will show black boxes instead of
  the styled bars.
- Male chest (NudeNet `MALE_BREAST_EXPOSED`) isn't mapped; add it in `PartMapper` if you want it.
- Want other apps too? Add their package names to `TARGET_PACKAGES` in
  `OverlayAccessibilityService.kt`.

## Tweaks

- Eye-bar position: `PartMapper.eyeBar()` fractions.
- Speed vs. battery: `CAPTURE_SCALE` in `CaptureService` (0.5 default; 0.35 is faster).
- Try `addNnapi()` in `NudeDetector` for GPU/NPU acceleration on supported phones.
