<p align="center"><img src="docs/icon.svg" width="128" alt="Ghost Frame icon"></p>

<h1 align="center">Ghost Frame</h1>

<p align="center">Float a see-through reference photo over your phone's own camera app, then match the pose and shoot.</p>

## What it does

- Pick any photo (or share one into the app from Gallery / Instagram). It floats over every app as a translucent ghost.
- Your real camera keeps working underneath. Taps pass straight through to the shutter, zoom and modes.
- **Photo** mode fades the whole picture; **Outline** mode shows only its edges (Sobel edge detection, adjustable detail, four line colours).
- A floating bubble opens the controls: **Move** (drag / pinch / rotate the ghost), opacity **− / +**, **Edge / Photo**, **Flip**, **Fit**, **Hide**, **New** photo, **Cam**, and **✕** to stop.
- The overlay is only on screen. It never appears in your photos.

## Build

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Needs the "Display over other apps" permission (the app asks for it). Min Android 9, tested on a Galaxy S24 Ultra.

## Android overlay notes

- Android 12+ only passes taps through another app's overlay when the overlay is at most ~80% opaque
  (`InputManager.getMaximumObscuringOpacityForTouch`), so ghost opacity is capped there.
- The reverse also holds: a *touchable* overlay window with low window alpha receives no touches at all.
  In Move mode the window goes fully opaque and the view draws the photo translucent instead.
- The camera is launched by package so apps like Snapchat that also claim `STILL_IMAGE_CAMERA` don't trigger a chooser.
