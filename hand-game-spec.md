# Hand-Tracked Puzzle Game — Design Spec

**Working title:** TBD
**Platform:** Native Android
**First build target:** Tech prototype (hand tracking + grab one cube)

---

## 1. Core Concept

A cozy, calm 3D puzzle game controlled entirely by the player's real hand. The phone's camera tracks the player's hand, and that tracking drives a **stylized cartoon 3D hand** on screen. The camera feed is **never shown** to the player. They only see the 3D hand interacting with the world.

- The player holds the phone in one hand and plays with the other hand.
- **Front or back camera**, chosen in settings:
  - Front camera: the playing hand is held in front of the screen.
  - Back camera: the playing hand reaches behind the phone, so it feels like reaching "through" the screen.
  - Both modes use the same control mapping, with orientation/mirroring flipped correctly so movement always feels natural.
- Main mode: level-based puzzles on a world map.
- Side mode: a toybox / free play room with critters and objects the player has met.

## 2. World & Framing

- **Framing:** The critters are the hand's little friends. There's no heavy plot. The relationship between the hand and the critters is the heart of the game.
- **Setting:** Abstract, dreamy spaces. Each area of the world map is its own surreal environment (floating shapes, soft gradients, impossible geometry, gentle light).
- **Tone:** Cozy and calm. No timers, no fail pressure, no punishing mechanics.

## 3. Presentation

- **Art style:** Smooth, toy-like vinyl/plastic look. Soft rounded shapes, glossy-but-gentle materials, pastel-friendly palette.
- **Hand:** Five-fingered, simple, stylized cartoon hand. Chunky and readable from every camera angle.
- **Camera view of the play space:** Changes per level (top-down tabletop, side-on diorama, angled 3/4, etc.), chosen to suit each puzzle.

## 4. Generative Music

- **Per-area palettes:** Each area has its own key/scale and instrument set, so every area has a distinct musical identity.
- **Actions trigger short melodic phrases** (not single notes). Grabbing, placing, poking, flicking, throwing, and petting each have phrase types that fit the current area's key and stay in time/harmony with the ambient bed.
- **Puzzle solved:** A gentle resolving chord. Calm and satisfying, not a big fanfare.
- Phrases should be quantized or loosely synced to the ambient bed so rapid interaction never sounds chaotic.

## 5. Hand Input

### Tracking
- MediaPipe Hand Landmarker (21 landmarks per hand), using the front or back camera depending on the setting.
- Single hand tracked at a time.
- **Handedness:** Auto-detect left or right hand, with a manual override in settings.
- Correct mirroring per camera so the on-screen hand always moves the way the player expects.
- Landmark smoothing (a One Euro filter or similar) so the hand feels solid without noticeable lag.

### Gestures
| Action | Gesture | Use |
|---|---|---|
| Grab small | Pinch (thumb tip + index tip) | Small objects, precise placement |
| Grab large | Closed fist | Big objects, critters |
| Poke | Extended index finger, contact with fingertip | Buttons, nudging, prodding critters |
| Flick | Fast fingertip motion on contact | Sending objects across the space |
| Pet/stroke | Open hand moving across a surface | Critters react happily |
| Throw | Release a grab while the hand is moving | Velocity comes from recent hand motion |

- Use **hysteresis** on pinch/fist thresholds so grabs don't flicker on and off.
- Gesture thresholds are scaled by hand size from calibration.

### Depth
- **Light use only:** depth controls lifting objects up/down off the surface, not full 3D reaching.
- Estimate depth from apparent hand size (e.g., wrist to middle-finger knuckle distance) compared to the calibrated baseline.
- Clamp and heavily smooth the depth value. It is noisier than X/Y.

### Calibration
- Short calibration at first launch and whenever the camera setting changes: player holds up an open hand at a comfortable distance.
- Records baseline hand size and comfortable movement range, then maps that range to the play space.

### Tracking Loss
- If the hand is lost for longer than a short grace period (~0.3–0.5s), the game **auto-pauses**.
- Any held object is released/settled safely when paused.
- Resume when the hand is detected again (a brief "hand found" moment or short countdown).

### Feedback (no haptics on the hand)
- Objects highlight/glow when the hand is close enough to grab.
- Soft snap/settle when grabbed and placed.
- Every interaction has a musical phrase (see Section 4).
- Optional phone vibration on grab/release, felt in the hand holding the phone.

### Comfort & Accessibility Settings
- Adjustable movement range and sensitivity.
- Front/back camera selection.
- Handedness override.

## 6. Onboarding

- **Short guided tutorial room** before the world map. It introduces each gesture in turn (pinch, fist grab, poke, flick, pet, throw), with a simple task for each.
- Calibration happens at the start of the tutorial.
- The tutorial room should also introduce the first critter friend.

## 7. Objects & Critters

- **Mix of object types:** rigid props/blocks, squishy soft-body objects, and critters.
- **Critters:** Small blobby creatures, **one species** in many colors/variants.
  - **Just reactive, no stats.** No hunger, mood meters, or care needs.
  - They respond to being poked, petted, picked up, flicked, and thrown with animations, squishes, and sounds.
  - **New variants are earned by finishing areas.** Completing an area adds that area's critter variant to the player's collection and to the toybox.
- **Critter movement in puzzles varies per level.** Depending on the level, critters might follow the hand when it's near, only move when nudged or carried, or wander toward things they like. Each level defines which behavior applies.

## 8. Puzzles

Puzzle types to include across the game:
- Stacking / balancing
- Sorting objects into the right places
- Assembling objects from parts
- Guiding critters to a destination
- Untangling / unknotting (ropes, strings, tangled things)
- Physics contraptions (Rube Goldberg-style chains)

**Level length:** Untimed, but designed to be bite-sized. Each level should center on one clear idea.

**Success:** Pass/fail only. No stars, timers, or scores. A level is complete when the goal state is reached, and the player can retry freely.

## 9. Structure & Progression

- **World map with themed areas.** Each area is a dreamy, abstract space with its own musical palette, groups levels around a theme, and introduces new objects.
- **Area completion** unlocks the next area and that area's critter variant.
- **Toybox / free play room:**
  - A free play room with no goals.
  - Objects and critters the player has encountered in levels appear there.
  - Completing levels unlocks decorations and toys for the room.

## 10. Recommended Tech Stack

**Unity (Android build) + MediaPipe hand tracking.**

Reasons:
- The game needs real physics (stacking, balancing, throwing, contraptions), soft bodies, rope/cloth for untangling puzzles, and a rigged/skinned hand. Unity has all of that built in or well supported.
- Easy to change camera setups per level.
- Good audio tooling for building the generative music system.

Options for getting MediaPipe into Unity (verify current status and versions before choosing):
1. A community MediaPipe Unity plugin (e.g., homuler's MediaPipeUnityPlugin).
2. A small native Android (Kotlin) plugin that runs the official MediaPipe Tasks Hand Landmarker and passes landmarks into Unity each frame.

Option 2 likely gives more control and uses the officially supported Android API. The coding chat should evaluate both and pick one. Whichever is chosen must support switching between front and back cameras.

## 11. First Build: Tech Prototype

Goal: prove the core mechanic feels good before building any content.

**Scope:**
1. Camera feed running MediaPipe Hand Landmarker (feed hidden from the player).
2. Front/back camera toggle with correct mirroring for each.
3. Left/right hand auto-detection.
4. Simple rigged five-fingered cartoon hand that follows all 21 landmarks (placeholder model is fine).
5. One table surface and **one cube** with physics.
6. Pinch to grab, fist to grab, release to drop, throw on release with velocity.
7. Light depth: lift the cube up/down via hand distance.
8. Landmark smoothing (One Euro filter) with tweakable parameters.
9. Calibration step (open hand to set baseline size).
10. Auto-pause on hand loss, resume on re-detection.
11. Grab-range highlight on the cube.
12. **Developer-only debug overlay** (toggle): camera feed with landmark dots, gesture state, depth value, FPS, tracking confidence.

**Out of scope for the prototype:** levels, world map, tutorial room, critters, generative music, toybox, final art.

**Success criteria:**
- The hand feels responsive and stable (no visible jitter at rest).
- Pinch and fist grabs register reliably without false triggers.
- Both front and back camera modes feel natural to control.
- Holding the phone in one hand while playing with the other feels comfortable.
- Steady frame rate on a mid-range Android phone.

## 12. Open Questions (for later)

- Game title.
- Names and specific looks for each dreamy area.
- Critter design (shape, face, how variants differ beyond color).
- Exact musical palettes per area (keys, scales, instruments).
