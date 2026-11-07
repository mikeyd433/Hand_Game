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
./gradlew :app:testDebugUnitTest      # JVM tests; also writes rough hand previews to app/build/hand-preview/
```

The hand model (`hand_landmarker.task`, about 7.8 MB) is downloaded by Gradle into
`app/src/main/assets/` and is git-ignored.

## How to play

1. **Calibrate.** Hold an open hand still at a comfortable distance:
   - *Front camera:* hold your hand in front of the screen, palm facing it, fingers up.
   - *Back camera:* reach behind the phone, palm facing away from you, fingers up.

   Then sweep your hand around the area you want to use, or tap **Skip**.

   The hold only counts while your whole hand has some room inside the camera's view, and a hint says
   which way to move. Calibration also measures how far your hand reaches on each side of the palm,
   so the play area keeps your whole hand in view. Holding your hand a little farther from the camera
   gives more room to move, and therefore less shake.
2. **Move** your hand to move the on-screen hand. Your comfortable range (from calibration) covers
   the whole screen. It is capped so you can reach every screen edge without your hand leaving the
   camera's view, and a soft glow appears on the screen edge if it starts to. The cube glows when it
   is in reach.
3. **Grab** with a **pinch** (thumb and index finger together) or a **fist**.
4. **Lift** by pulling your hand back. The screen is a window looking down at the table, so pushing
   "into" it goes down:
   - *Front camera:* move your hand toward the screen to lower, pull it back toward yourself to lift.
   - *Back camera:* reach farther behind the phone to lower, bring your hand back toward it to lift.

   **Reverse lift direction** in settings flips this.
5. **Throw** by opening your hand while moving. The cube flies off with your hand's speed.
6. **Pause:** take your hand out of view and the game pauses after 0.4 s. Anything you're holding
   is set down. Show your hand again to resume after a short countdown.

The ⚙ button opens settings: camera, playing hand, movement and lift sensitivity, hand size, vibration,
recalibrate and reset cube. Debug builds also show a **DBG** button for the developer overlay (camera
feed with landmark dots, gesture, depth, FPS, tracking confidence) and One Euro smoothing sliders.

## Tuning it to your hand

Debug builds include a tuning recorder (⚙ → Developer: tuning recorder; turn DBG on to see the
developer section):

- **Guided test (~2½ min):** on-screen prompts for holding still, relaxing, pinch/open and fist/open
  in time with a cue, slow and fast sweeps, lift/lower, and tracing the screen edges. Then five
  object tasks that end when done: pinch-grab the cube, lift and hold it steady, set it in a glowing
  ring, fist-carry it to another ring, and throw it off the table. The cues label the data, so the
  analysis knows what your hand was supposed to be doing.
- **Record play:** play normally and tap **⚑ Mark issue** right after anything feels off (jittery,
  missed grab, grabbed by itself, dropped, lost hand, laggy, lift, hand shape). Up to 10 minutes.

When you stop, the app replays the recording through the game code and shows a **summary**: jitter in
mm, gesture accuracy and delay, the actual pinch/curl measurements per cue against the current
thresholds, smoothing lag, depth range, edge warnings, dropouts, and the context of each mark.
**Copy summary** to paste it into a chat, or **Save recording…** to get a `.jsonl.gz` file. The file
holds hand-landmark numbers only, never camera images.

To replay saved recordings against the current code (for example after changing smoothing or
gesture thresholds), put them in `recordings/` (git-ignored) and run:

```bash
./gradlew :app:testDebugUnitTest --tests '*RecordingReplayTest*' -i
```

The on-device summary and the offline replay use the same `TuningAnalyzer`, so their numbers match.

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
│   ├── HandSkeleton.kt    rigid hand: angles from landmarks → fixed-length skeleton
│   ├── HandCollision.kt   drawn-hand overlap detection/resolution (fingers, palm, table, cube)
│   ├── ViewRig.kt         scene camera framing + screen→world projection (shared with renderer)
│   ├── GameController.kt  phases (calibrate/play/pause/resume), grab, soft snap, throw, highlight
│   └── GameSettings.kt    settings + calibration persistence
│   └── GripSolver.kt      drawn-hand grip pose + HandLook proportions
├── physics/Physics.kt     box rigid body, table + floor contacts, friction, sleeping, velocity drive
├── render/
│   ├── MeshFactory.kt     sphere, cylinder, rounded box, gradient dome
│   ├── HandMeshBuilder.kt smooth hand rebuilt each frame: tapered fingers, palm, thumb pad, cuff
│   ├── Materials.kt       toy vinyl material + gradient backdrop (shader source in Kotlin)
│   └── SceneRenderer.kt   Filament scene, lights, dynamic hand meshes + outline, frame loop
├── tuning/                recorder, guided-test script, JSONL format, replay analyzer
└── ui/                    Compose overlays: calibration, pause, settings, debug, tuning
```

### How the mapping works
- **Mirroring:** frames go to MediaPipe upright and un-mirrored. For the front camera, the screen
  position is mirrored (`u → 1-u`) so moving right moves the hand right. The 3D shape is rotated
  with a *proper* rotation for each camera, so a left hand is never turned into a right hand.
- **No overlaps in the drawn hand:** after the grip pass, `HandCollision` treats every finger bone
  as a capsule. It pushes apart fingers that pass through each other, fingertips sinking into the
  palm, joints in the table and bones cutting through the cube, then restores bone lengths. Corrections
  ease in over ~60 ms so nothing pops. A free cube under the hand is something to rest on, like the
  table. In a fist the drawn thumb is guided to rest across the outside of the curled fingers,
  because tracking puts it inside them. Per-frame overlap depth is recorded for tuning.
- **Solid hand:** the drawn hand is a rigid skeleton with fixed cartoon bone lengths
  (`HandSkeleton.kt`). From tracking we read only joint angles (finger curl, a little spread, thumb
  direction and bend), clamp them to real hand limits and smooth them. Noisy landmark depth can't
  stretch or fold the fingers. Gestures still use the raw landmarks so they stay responsive.
  Smoothing runs every rendered frame, so the hand glides between ~30 fps tracking updates.
- **Even pacing:** tracking results arrive unevenly (17/33/50 ms apart). Each result keeps the time
  its camera frame was captured, and the hand is drawn ~55 ms behind real time, blended between
  the two nearest results (`SampleInterpolator`). Without this the hand froze and then jumped.
  Gestures still use each new result immediately.
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
