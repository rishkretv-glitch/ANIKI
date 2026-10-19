# QA matrix: PDF Architect Ultra v0.4.0-alpha

## Automated here / in CI
- GeometrySmokeTest.kt — length calibration and degeneracy checks (JVM smoke; run here).
- PdfPageSpaceSmokeTest.kt — CropBox translations and 0/90/180/270 rotations (JVM smoke; run here).
- AreaSmokeTest.kt — polygon shoelace formula, opposite winding and no-calibration behavior (JVM smoke; run here).
- ExportReadinessSmokeTest.kt — regression for export of area-only annotated pages (JVM smoke; run here).
- `app/src/test/.../EngineeringMathTest.kt` — JUnit; executed in Android-capable CI with `:app:testDebugUnitTest` **only after a successful Gradle build**.

## On-device acceptance (NOT run here)
- [ ] Launch Android debug APK on Samsung Galaxy S24 Ultra.
- [ ] Open bundled Engineering Demo via the home-screen button.
- [ ] Open a PDF through SAF picker and from Android ACTION_VIEW / file manager.
- [ ] Open 500-page and image-scanned PDFs; measure cold open, navigation, memory; no performance claims before profiling.
- [ ] Draw S Pen pressure-sensitive paths; place a palm on screen; try stylus barrel button / eraser.
- [ ] Pan/pinch in both stylus-only and touch-draw modes; verify annotations align after zoom / rotation.
- [ ] Add and erase multiple strokes; Undo/Redo; clear page and Undo.
- [ ] Calibrate a 5m reference and measure lines and a 10m² rectangle.
- [ ] Write text in Hebrew and Russian; import an image, save, re-open.
- [ ] Export preserving PDF; compare selectable source text, vector graphics, links and images.
- [ ] Export flattened PDF; verify content becomes pixels.
- [ ] Export a multi-page PDF, inspect signed/encrypted forms, rotated / nonzero CropBox PDFs.
- [ ] Reopen an existing PDF after force-stop; verify saved markup.
- [ ] Run searches in text-based document and verify helpful message for scanned document.

## Known exclusions
Existing text editing, certified signatures, true PDF annotation dictionaries, OCR, AI, tiled vector viewer, encrypted PDFs, destructive redaction, multi-document tabs. These are NOT implemented.
