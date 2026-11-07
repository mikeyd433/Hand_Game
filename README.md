# Hand Game: tech prototype

A cozy 3D puzzle game controlled by your real hand. The phone camera tracks your hand, and a
chunky cartoon hand on screen copies it. You never see the camera picture.

This is the **first build**: a tech prototype that tests whether the core mechanic feels good. It
has one table, one cube and a hand. See `hand-game-spec.md` (Section 11) for the full scope.

## Stack

All of it is written in Kotlin, with no game engine or editor:

| Part | Choice |
|---|---|
| Hand tracking | MediaPipe Tasks **Hand Landmarker** (GPU, falls back to CPU) + CameraX, front or back camera |
| Rendering | **Filament**. All meshes are generated in code, and materials are compiled on the device with `filamat` |
| Physics | Small custom rigid-body solver (`physics/Physics.kt`) |
| UI | Jetpack Compose on top of the 3D view |
| Min Android | 8.0 (API 26). Portrait only |

## Build and install

Requirements: JDK 17+, Android SDK with platform 36. Android Studio is optional.

```bash
./gradlew :app:assembleDebug          # downloads the MediaPipe model on the first build
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # JVM tests: physics, filters, gestures, calibration, game flow
```

The hand model (`hand_landmarker.task`, about 7.8 MB) is downloaded by Gradle into
`app/src/main/assets/` and is git-ignored.

## How to play

1. **Calibrate.** Hold an open hand still at a comfortable distance:
   - *Front camera:* hold your hand in front of the screen, palm facing it, fingers up.
   - *Back camera:* reach behind the phone, palm facing away from you, fingers up.

   Then sweep your hand around the area you want to use, or tap **Skip**.
2. **Move** your hand to move the on-screen hand. Your comfortable range (from calibration) covers
   the whole screen. The cube glows when it is in reach.
3. **Grab** with a **pinch** (thumb and index finger together) or a **fist**.
4. **Lift** by pulling your hand back. The screen is a window looking down at the table, so pushing
   "into" it goes down:
   - *Front camera:* move your hand toward the screen to lower, pull it back toward yourself to lift.
   - *Back camera:* reach farther behind the phone to lower, bring your hand back toward it to lift.

   **Reverse lift direction** in settings flips this.
5. **Throw** by opening your hand while moving. The cube flies off with your hand's speed.
6. **Pause:** take your hand out of view and the game pauses after 0.4 s. Anything you're holding
   is set down. Show your hand again to resume after a short countdown.

The ⚙ button opens settings: camera, playing hand, movement and lift sensitivity, vibration,
recalibrate and reset cube. Debug builds also show a **DBG** button for the developer overlay (camera
feed with landmark dots, gesture, depth, FPS, tracking confidence) and One Euro smoothing sliders.

## Code map

```
app/src/main/java/com/handgame/prototype/
├── MainActivity.kt        wiring: permission, tracker, game loop, haptics, settings
├── tracking/
│   ├── HandTracker.kt     CameraX → upright bitmap → MediaPipe (LIVE_STREAM), feed never shown
│   ├── HandData.kt        landmark indices, mirroring, camera→game axes, handedness vote
│   └── OneEuroFilter.kt   smoothing
├── input/
│   ├── GestureDetector.kt pinch / fist / open with hysteresis + debounce, scaled by palm size
│   └── Calibration.kt     open-hand baseline + movement-range sweep
├── game/
│   ├── HandRig.kt         landmarks → smoothed world pose (X/Z from position, Y from apparent size)
│   ├── ViewRig.kt         scene camera framing + screen→world projection (shared with renderer)
│   ├── GameController.kt  phases (calibrate/play/pause/resume), grab, soft snap, throw, highlight
│   └── GameSettings.kt    settings + calibration persistence
├── physics/Physics.kt     box rigid body, table + floor contacts, friction, sleeping, velocity drive
├── render/
│   ├── MeshFactory.kt     sphere, cylinder, rounded box, gradient dome
│   ├── Materials.kt       toy vinyl material + gradient backdrop (shader source in Kotlin)
│   └── SceneRenderer.kt   Filament scene, lights, hand from 21 joints + 24 bones, frame loop
└── ui/                    Compose overlays: calibration, pause, settings, debug
```

### How the mapping works
- **Mirroring:** frames go to MediaPipe upright and un-mirrored. For the front camera, the screen
  position is mirrored (`u → 1-u`) so moving right moves the hand right. The 3D shape is rotated
  with a *proper* rotation for each camera, so a left hand is never turned into a right hand.
- **Handedness:** MediaPipe labels assume a mirrored selfie image, so the label is inverted for our
  images and then majority-voted over about 20 frames. You can override it in settings.
- **Screen-wide reach:** the calibrated movement range maps to screen coordinates (nearly
  edge to edge). `ViewRig` then casts a ray from the scene camera to find the matching point at hover
  height. The camera is refit to each screen's shape so the portrait table fills the display.
- **Depth:** apparent hand size (palm length and knuckle width) divided by the calibrated size.
  Moving into the screen goes down (bigger for the front camera, smaller for the back). It has a
  small dead zone, is clamped, and gets its own heavier One Euro filter. It only controls lifting.
- **Grabbing:** the hand closing (OPEN → PINCH/FIST) grabs the cube if it is within 6 cm of the
  pinch point, fist point or palm, or was within the last 0.3 s. The held cube follows through a
  velocity drive, so it still collides with the table. Releasing uses the hand's average
  velocity over the last ~100 ms.

## What still needs on-device testing

The code was written and tested on the JVM in a cloud container without a phone. These things
can only be judged on a real device:
- How responsive it feels, and whether the One Euro defaults are right. Tune them live in DBG → ⚙.
- Gesture thresholds on real hands (`GestureThresholds` in `GestureDetector.kt`). Watch the pinch and
  curl numbers in the debug overlay.
- Lighting and exposure (`SceneRenderer.setupLights`), and whether the colours look right.
- Frame rate on a mid-range phone. If it is low, the first things to drop are 4× MSAA and
  shadow map size in `SceneRenderer`.
- Materials compile at startup, which may add a short pause before the first frame.

## Known limitations (prototype)
- Hand pose assumption: the mapping assumes a roughly upright hand (fingers up). A hand held flat
  will show up tilted in the game.
- The physics only supports boxes against the table top and the floor. That is enough for one cube;
  stacking needs box–box contacts next.
- The APK is large (about 87 MB) because it ships three ABIs plus the runtime material compiler. Use
  ABI splits or precompiled materials for release.
