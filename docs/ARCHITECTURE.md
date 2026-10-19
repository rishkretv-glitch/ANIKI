# PDF Architect Ultra — software architecture (0.4-alpha)

## MVP source layout

- `MainActivity.kt`: Compose UI, document picker, editor tools, navigation,
  sequential annotation persistence, search/page-jump dialogs and dual export orchestration.
- `PdfDocumentSession.kt`: native renderer, conservative raster memory budget,
  seekable SAF URI handling and **flattened** PDF fallback export.
- `PreservingPdfExporter.kt`: PDFBox-Android export that appends vector paths and
  image/text overlays to a COPY without replacing original text/vector content.
- `PdfPageSpace.kt`: pure 2D coordinates mapping displayed pages to PDF user
  space with /CropBox offsets and /Rotate 0/90/180/270.
- `PdfTextSearch.kt`: offline text-layer PDF search (no OCR).
- `InkPageView.kt`: platform View handles raw MotionEvent pressure/history,
  single-touch pan, multi-touch scale, stylus filtering and coordinate transforms.
- `Geometry.kt`: pure Kotlin annotation models, calibration arithmetic and tools.
- `Annotations.kt`: single Canvas implementation shared by preview and export.
- `InkStore.kt`: versioned per-page JSON, serialized AtomicFile persistence;
  schema 1 starter ink is migrated during loading, schema 2 also loads.
- `ImageAssets.kt`: local image import, downsampling and bounded memory cache.
- `RecentDocuments.kt`: offline recent URI list.

## Conceptual next-generation architecture

```
Compose Reader / Home / Editor / AI
                 |
       DocumentViewModel (MVVM)
                 |
        DocumentRepository
        /       |          \
  PdfEngine   InkEngine   AI/OCR Engine
     |           |           |
  Tiled      Stylus      Opt-in RAG +
  bitmap     motion      OCR processing
  cache      pipeline
     \           |           /
         SAF + Room + WorkManager
```

Use `PdfEngine` abstraction to switch between free renderer and a commercial
PDF edit engine without tying page coordinates, overlays or UI to vendor API.
Use `PageTransform` for explicit mapping among PDF points, CropBox, page rotation,
render tiles, editor viewport and final PDF annotation rectangles.

## Rendering performance roadmap

1. Render only visible tiles at native zoom (PdfRenderer clip + Matrix), not
   full high-resolution pages. Queue jobs by distance to viewport center.
2. Separate background image cache and annotations, use per-document LRU budgets.
3. Keep UI/main thread untouched by PdfRenderer, decode and export.
4. Prefetch neighboring pages at thumbnail resolution; cancel obsolete work.
5. Benchmark real 500MB scans, 200-page PDFs and A0 CAD exports against Acrobat.
6. Profile frame durations, jank, memory pressure, device temperatures and
   handwriting end-to-end latency on Galaxy S24 Ultra.

## Core correctness requirements

- Every annotation is stored in page coordinates. A pixel-to-page transform
  must be invertible and include zoom, translation, rotation and CropBox.
- Calibration uses physical page dimensions in PDF points, not displayed pixels.
- Avoid accidental destructive writes to source PDFs; export copies first.
- Never pass private document content to AI APIs without opt-in and disclosure.
- Genuine PDF redaction requires deleting original content, not painting a box.
- Signatures must use established PDF/PAdES tooling: a drawing of a signature
  is not a cryptographic digital signature.
- Before promising searchable/structural PDF editing, verify SDK support,
  output interoperability, and license rights on real files.

## Roadmap for the requested flagship app

P1: production data layer + multiple pages + renderer tiles + annotation PDF
export. P2: full document object editing + forms + text selection + images.
P3: S Pen Jetpack Ink, lasso, shapes, Ink-to-Text. P4: engineering tools
(polyline, scale, areas, layers, measurements). P5: offline OCR search for
Hebrew/Russian and opt-in AI chat with page citations and local encryption.

## Additions in v0.4.0-alpha

- `AreaPolygon` page-normalized model + shoelace/calibration in `MeasureMath.squareMeters`.
- `InkPageView`: stylus corner-by-corner polygon construction; tap the first corner to close.
- `InkStore` schema v4: backward read compatibility for schemas v1/v2/v3; adds `areas`.
- `PreservingPdfExporter`: stroked vector polygon outlines and isolated area labels.
- `MainActivity`: external `ACTION_VIEW` file intents, bundled demo PDF, confirmed page-clear with Undo.
- JUnit math tests under `app/src/test` for execution by CI.
