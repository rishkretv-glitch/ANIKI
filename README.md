# PDF Architect Ultra · 0.4.0-alpha

**Native Android PDF markup app** optimized for stylus-first interaction on Galaxy S24 Ultra. Written in Kotlin / Jetpack Compose. **Source project, not a compiled APK.** No network permission; no cloud processing.

## What is implemented

- Open local/cloud PDF files with Android Storage Access Framework, or choose PDF Architect from a file manager's **Open with…** dialog (`ACTION_VIEW`).
- Built-in 3-page engineering demonstration: **Try demo drawings** on the home screen; no file selection necessary.
- Page-by-page native `PdfRenderer`; pinch/pan, toolbar zoom 100–500%, page jump, local recent files.
- S Pen pressure-aware strokes, highlighter, stroke eraser, hover feedback, stylus-only touch rejection toggle.
- Page-local Undo/Redo and **Clear page** with confirmation and undo. Atomic autosaved JSON draft annotations.
- Text notes (including Russian/Hebrew), imported images, engineering distance calibration, linear dimensions and closed polygon area in m².
- Text-layer search via PdfBox-Android (offline; OCR is **not included**).
- Export a **new PDF**: vector ink, outlines, inserted images/text as PDF content overlays without rasterizing original source pages. Also an optional fully flattened raster export.
- AMOLED-style Material 3 dark interface with Russian UI text.
- GitHub Actions workflow that can build and archive an installable debug APK on Android SDK / JDK 17 runners.

## Create an APK

Read `BUILD_APK_RU.md`. From Android Studio or Gradle 8.13 + Android SDK API 36, run:

```bash
gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

APK output: `app/build/outputs/apk/debug/app-debug.apk` **after successful build**.

If using GitHub Actions, upload the *contents of this project* to a private repository and run **Actions → Android Debug APK → Run workflow**. Download the artifact after successful CI execution.

## Engineering area tool

1. Open PDF, choose **Масштаб**, draw a line whose real-world length is known, enter meters.
2. Choose **Площадь** and tap 3+ corners in order using S Pen.
3. Tap the **first** corner again to close the polygon. The area label appears in m².
4. Measurements are based on page coordinates and uniform scale. They are **not a substitute for a verified source drawing or certified measurement**.

## Tested here

- `kotlinc` + JVM smoke tests: geometry, 0/90/180/270-degree page transforms, polygon area.
- Checked XML structure, input assets and ZIP archive; Kotlin lexer/parser did not report syntax errors.
- **Not tested:** full Android compilation, real S Pen interaction, real-device rendering, export with complex PDFs. The authoring environment has no Android SDK/Gradle and cannot fetch them.

## Boundaries

- **Cannot edit or erase existing PDF text or images.** It adds new PDF page content, or flattens copies.
- Preserving export **rewrites** documents and can invalidate signed PDFs, special interactive forms, accessibility metadata and some advanced features. Do not edit signed originals without backup and verification.
- New ink is PDF vector page content, *not native editable PDF annotations*. Added text is rasterized as isolated image overlays; the original selectable text still exists beneath it.
- No OCR, AI, digital-ink handwriting recognition, batch processing, real tiled PDF renderer, certified e-signatures, password-protected PDF support guarantee or benchmark against Acrobat.
- Imported images stored privately and app-local edits are removed on uninstall. Existing drafts are matched by source URI; a replaced document under the same URI may inherit old drafts.
- For safety, never overwrite original PDFs directly. Export a copy.

## Technical notes

- Android package / app ID: `com.pdfarchitect.starter` for compatibility with earlier prototype installations.
- `compileSdk=36`, `targetSdk=35`, `minSdk=26`; Kotlin 2.2.20, Android Gradle Plugin 8.13.2, Gradle 8.13, JDK 17.
- PDFBox-Android 2.0.27.0 (Apache License 2.0), invoked for text-layer extraction and preserving export.
- No bundled dependencies or Android SDK binaries. CI resolves dependencies online.

`docs/ARCHITECTURE.md` explains extension points; `docs/QA.md` contains the Android-device acceptance checklist.
