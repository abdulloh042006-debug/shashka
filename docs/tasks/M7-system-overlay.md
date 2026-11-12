# M7 task: system floating window (over other apps) with transparency and lock

Task for the shashka chat (repo abdulloh042006-debug/shashka, current main d8aa430 — run `git pull` first).
Goal: turn the existing in-app "Analysis HUD" (MainActivity.kt, M6) into a real floating window that stays on top of the
whole phone (over other apps), with adjustable transparency and a lock mode. One branch `feature/system-overlay`, one PR,
Android CI green. Keep the existing rules engine and DraughtsAI untouched except for wiring.

## 1. Floating window over other apps
- Permission: SYSTEM_ALERT_WINDOW. Check `Settings.canDrawOverlays()`; if missing, explain in one sentence and open
  `ACTION_MANAGE_OVERLAY_PERMISSION` for our package. Never ask again automatically if the user refuses.
- Host it in a foreground Service (`TYPE_APPLICATION_OVERLAY`) with a persistent notification "Shashka oynasi ochiq"
  + "Yopish" action. Android 14: declare `foregroundServiceType="specialUse"` with the
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property; request POST_NOTIFICATIONS on Android 13+.
- Content (Compose inside the overlay via a ComposeView with its own Lifecycle/SavedState owners):
  a mini 8x8 board of the CURRENT position held by the app, the engine's best move drawn as an arrow, the evaluation,
  and small buttons: undo, flip board, open full app, transparency, lock, close.
- The user can drag the window by its title bar, resize it with a corner handle (min ~160 dp, max screen width) and it
  snaps inside the screen edges. Position, size, transparency are saved (DataStore/SharedPreferences) and restored.
- Engine runs on a background coroutine with a time limit (e.g. 300–800 ms, setting); never blocks the UI thread;
  a new position cancels the previous search.

## 2. Transparency
- A slider 20%–100% visible opacity (or +/- buttons), applied live to the board window (`LayoutParams.alpha`).
- In LOCKED mode the opacity is capped at 80% automatically and the slider shows why ("qulflanganda ko'pi bilan 80%"):
  Android 12+ blocks touches that pass through an overlay more opaque than
  `InputManager.getMaximumObscuringOpacityForTouch()` (0.8), so above that the app underneath would not get the touches.

## 3. Lock mode (qulf)
- Lock button → the board window becomes see-through for touches: add `FLAG_NOT_TOUCHABLE` (+ `FLAG_NOT_FOCUSABLE`),
  so every tap goes to the app underneath; the window stays visible where it is; dragging/resizing/buttons are off.
- The ONLY thing that still works is a small separate unlock bubble (a second tiny overlay window, ~40 dp, touchable,
  placed at the board window's corner and moving with it). Tap → unlock (remove the flags, restore controls).
  Optional: long-press on the bubble = close the overlay.
- Lock state survives rotation and service restarts; the notification also offers "Qulfni ochish".

## 4. How the position gets into the window (scope line — keep it)
- From the app: the position on the main board (manual editor, FEN, user-picked screenshot import M5) is shared with
  the overlay through one state holder (e.g. a singleton StateFlow / repository); both stay in sync.
- On the overlay itself: when UNLOCKED the user can make moves on the mini board by tapping (legal moves only, using
  RussianDraughtsRules), so he can follow a game by entering each move himself; undo works.
- Do NOT add continuous capture of other apps' screens (MediaProjection loops), reading other apps through an
  AccessibilityService, or any automatic tapping/moving in other apps. The overlay never plays for the user and never
  reads another app's game by itself. Keep the existing HUD wording ("never captures other apps' screens or performs
  automated clicks") true. The user-picked screenshot import (M5) stays as it is.

## 5. Acceptance
- JVM unit tests: overlay state holder (sync between app and overlay, move/undo, lock state, opacity clamp to 0.8 when
  locked, saved position/size restore) and any pure helpers. Existing tests stay green.
- `./gradlew testDebugUnitTest assembleDebug` passes; Android CI green on the PR.
- Manual check list in the PR (owner runs it on his phone): permission flow; window over the home screen and over
  another app; drag/resize; opacity 20–100%; lock → taps reach the app underneath, only the bubble reacts; unlock;
  rotate; close from notification.
- Update README "Current implementation" and ROADMAP (new item M7: system overlay with lock + transparency).
- Report back: PR link, CI result, what was tested on a real device and what was not.
