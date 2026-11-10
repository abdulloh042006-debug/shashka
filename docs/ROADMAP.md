# Draughts AI Roadmap

- [x] Initialize Android application structure.
- [x] Add Kotlin + Jetpack Compose UI foundation.
- [x] Add immutable 8x8 board model and manual position editor.
- [x] Add board-model unit tests.
- [x] Implement classic Russian Draughts legal move generation.
- [x] Implement mandatory capture and backward captures by men.
- [x] Implement flying kings and all legal landing squares after a captured piece.
- [x] Implement compulsory continuation of multiple captures.
- [x] Implement promotion during capture and continuation as a king.
- [x] Keep captured pieces on the board until the capture sequence finishes (Turkish strike).
- [ ] Run the Android Gradle build and JUnit suite in Android Studio/CI.
- [ ] M3: implement local AI search after the rules engine passes the full test suite.
- [ ] M4: position save/load and notation.
- [ ] M5: user-selected screenshot import and correction.
- [ ] M6: optional transparent floating analysis panel for authorized positions.
