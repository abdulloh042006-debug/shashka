# Draughts AI

Offline-first Android analysis and training app for **classic Russian Draughts**.

## Current implementation
- Kotlin + Jetpack Compose Android app.
- 8x8 board and editable positions.
- Immutable board model + FEN import/export.
- Classic Russian Draughts legal-move engine:
  - Men move forward only on quiet moves.
  - Men capture forwards and backwards.
  - Captures are compulsory.
  - Multi-captures continue until no further capture is available.
  - Flying kings can land on any available square beyond a captured piece (must land on a continuation square if further capture exists).
  - Promotion during a capture continues immediately with flying-king movement.
  - Captured pieces remain on the board until the full capture sequence ends (Turkish strike).
  - Classic Russian capture choice is not forced by the maximum number of captured pieces.
- Local AI Search Engine:
  - Iterative Deepening Negamax with Alpha-Beta Pruning.
  - Zobrist Hashing and Transposition Table.
  - Move Ordering and Quiescence capture extensions.
- Explicit Screenshot Import via Android Photo Picker with confidence preview and editor verification.
- Optional, user-invoked Analysis HUD for manually entered or imported positions.
- Comprehensive JUnit 4 unit tests for board, rules, and AI search.

## Open & Run
Open this repository in Android Studio, sync Gradle, and run:
```bash
./gradlew testDebugUnitTest assembleDebug
```
