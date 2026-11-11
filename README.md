# Shashka AI

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
- Offline AI search:
  - Negamax (the minimax equivalent) with alpha-beta pruning.
  - Principal Variation Search (PVS) for later ordered moves.
  - Late Move Reductions (LMR), verified at full depth when a reduced move improves alpha.
  - Single-legal-move extensions.
  - Iterative deepening with depth, time, and node limits.
  - Transposition table with position hashing and bound types.
  - Move ordering using transposition moves, captures, promotion, killer moves, and history scores.
  - Quiescence search extends positions where a compulsory capture exists.
  - Heuristic evaluation of material, king activity, advancement, and centralization.
- Android UI action to analyze a manually edited position without blocking the UI thread.
- Unit tests for the board, movement rules, and AI search.
- Rule reference and architecture notes in `docs/`.

## Rules scope
This follows **classic Russian Draughts**. It is not International Draughts. The separate tournament variant named Shashki-64 may apply a majority-capture rule; this project follows classic Russian rules instead.

## AI scope and limitations
The current AI is a deterministic alpha-beta search engine, not a neural network and not yet a tournament-certified engine. It has no opening book, endgame tablebase, repetition history, or full tournament draw-claim logic. Search strength depends on available time and node limits. Search is run only on positions entered or explicitly imported by the user.

## Build and tests
The repository currently uses a Gradle build without a checked-in Gradle Wrapper. GitHub Actions installs Gradle 8.10.2 and runs:
```bash
gradle --no-daemon testDebugUnitTest assembleDebug
```

Open the project in Android Studio to run it locally. The CI workflow uploads the debug APK after a successful build.

## Next milestones
1. Add position save/load and draughts notation.
2. Add benchmark positions and compare search quality against known solutions.
3. Add user-selected screenshot import with preview and manual correction.
4. Consider opening-book and endgame-tablebase support after the core engine is benchmarked.
