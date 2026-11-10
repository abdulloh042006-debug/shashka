# Draughts AI

Offline-first Android analysis and training app for **classic Russian Draughts**.

## Current implementation
- Kotlin + Jetpack Compose Android app.
- 8x8 board and editable positions.
- Immutable board model.
- Classic Russian Draughts legal-move engine:
  - Men move forward only on quiet moves.
  - Men capture forwards and backwards.
  - Captures are compulsory.
  - Multi-captures continue until no further capture is available.
  - Flying kings can land on any available square beyond a captured piece.
  - Promotion during a capture continues with king movement.
  - Captured pieces remain on the board until the full capture sequence ends (Turkish strike).
  - Classic Russian capture choice is not forced by the maximum number of captured pieces.
- Unit tests for the core board and movement rules.
- English Gemini master prompt and rule reference.

## Important
This follows the **classic Russian Draughts** rule set described by the Russian Draughts Federation. It is not International Draughts. The separate tournament variant named Shashki-64 may apply a majority-capture rule; this code follows classic Russian rules instead.

The Android build and full JUnit suite have not yet been run in Android Studio/CI. Do not assume that the project is build-verified.

## Open
Open this repository in Android Studio, sync Gradle, then run the app and unit tests.

## Next milestones
1. Run and fix Android build and all unit tests.
2. Add AI search (minimax + alpha-beta pruning).
3. Add position save/load and notation.
4. Add explicit screenshot import with user correction.
5. Add optional, user-invoked analysis panel for positions the user has entered or deliberately imported.
