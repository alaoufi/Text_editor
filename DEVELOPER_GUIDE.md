# Global Text Editor — Developer Guide / دليل المطوّر

> محرر النصوص العالمي — دليل تقني كامل لنقل المشروع ومتابعة تطويره في جلسة مخصّصة.
> A complete technical handover guide: architecture, every file, data storage,
> feature internals, build & signing, and how to extend it.

---

## 0) TL;DR / ملخّص سريع

- **Type:** Android app, single-module (`:app`). A fast, Unicode-safe **text editor**
  with first-class Arabic support, automatic encoding detection, and a lightweight
  rich-text layer.
- **Language/UI:** Kotlin + Jetpack Compose (Material 3), MVVM.
- **Min/Target SDK:** 26 / 34. APK ≈ 1.4 MB (R8 + resource shrink).
- **There is NO SQL database / Room.** Persistence = **DataStore Preferences**
  (settings + last-used encoding per file) + **plain files** in internal storage
  (crash-recovery drafts). Details in §4.
- **Package:** `com.uts.editor`  ·  **App name:** Global Text Editor / محرر النصوص العالمي
- **Current version:** see `app/build.gradle.kts` (`versionName` / `versionCode`).

---

## 1) Tech stack & versions

| Thing | Value |
|---|---|
| Build | Gradle 8.14.3 (wrapper), AGP 8.5.2 |
| Kotlin | 2.0.21 (+ `org.jetbrains.kotlin.plugin.compose`) |
| Compose | BOM 2024.09.03, Material 3 |
| Min / Compile / Target SDK | 26 / 34 / 34 |
| Java | 17 |
| Charset detection | `com.github.albfernandez:juniversalchardet:2.5.0` |
| On-device OCR | `com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0` (JitPack) |
| Persistence | `androidx.datastore:datastore-preferences:1.1.1` |
| File access | Storage Access Framework + `androidx.documentfile` |
| PDF render | platform `android.graphics.pdf.PdfRenderer` (no library) |

Third-party libs are only: **juniversalchardet** (charset) and **Tesseract4Android**
(OCR native libs, ~7 MB, gated to `arm64-v8a`/`armeabi-v7a`). Everything else —
PDF/Word/Excel reading **and** writing — is hand-rolled with the JDK zip/XML/graphics
APIs (see §9). **No Apache POI/PDFBox** (removed to keep the app small). No network
stack beyond `HttpURLConnection` (update check + OCR data download), no DB, no DI.

**APK size:** `arm64-v8a` split ≈ **8.5 MB**, universal ≈ 14.5 MB. ABI splits +
a universal fallback are produced by `splits { abi { … } }` in `app/build.gradle.kts`.

---

## 2) Build, run, sign

```bash
# Requires Android SDK with: platforms;android-34, build-tools;34.0.0, platform-tools
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # signed + shrunk: app/build/outputs/apk/release/app-release.apk
```

- `local.properties` must contain `sdk.dir=/path/to/android-sdk` (not committed).
- **Signing:** release is signed by `release.keystore` at the repo root
  (self-signed dev key; alias `uts`, store/key password `uts12345`). Config is in
  `app/build.gradle.kts` → `signingConfigs.release` (guarded by `if (ksFile.exists())`).
  **Replace this keystore with your own before publishing to Google Play.**
- **Version bump:** edit `versionCode` (+1) and `versionName` in
  `app/build.gradle.kts`. Android blocks installing a *lower* `versionCode` over an
  existing install, so always increase it for a new build the user will side-load.
- The version string is shown in-app via `BuildConfig.VERSION_NAME`
  (`buildConfig = true` is enabled).

---

## 3) Project structure (every file)

```
T_Office/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/wrapper/…, gradlew, gradlew.bat
├── release.keystore                  # dev signing key (see §2)
├── dist/GlobalTextEditor-vX.Y-release.apk   # prebuilt APK
├── README.md, DEVELOPER_GUIDE.md
└── app/
    ├── build.gradle.kts              # module config, deps, signing, version
    ├── proguard-rules.pro            # keep juniversalchardet
    └── src/main/
        ├── AndroidManifest.xml       # activity, file-type intent filters, FileProvider
        ├── res/
        │   ├── values/strings.xml        # English strings
        │   ├── values-ar/strings.xml     # Arabic strings
        │   ├── values/themes.xml, values-night/themes.xml
        │   ├── drawable/ic_launcher_*    # adaptive icon vectors
        │   ├── mipmap-anydpi-v26/ic_launcher*.xml
        │   └── xml/file_paths.xml        # FileProvider paths (share/export/recovery)
        └── java/com/uts/editor/
            ├── UtsApplication.kt     # empty Application
            ├── MainActivity.kt       # Compose host; locale wrap; intent handling; autosave on stop
            ├── model/
            │   ├── TextEncoding.kt   # encodings list + charset resolution + BOM bytes
            │   └── Models.kt         # DocumentState, LoadMode, LineEnding, TextStats, SyntaxLanguage
            ├── data/
            │   ├── EncodingDetector.kt  # detection + binary check + UTF-16 detection
            │   ├── FileIo.kt           # SAF read/write, streaming, large-file paging, mime, createInTree
            │   ├── ZipSupport.kt       # list/read text entries inside .zip
            │   ├── WordExtractor.kt    # Word entry point: .docx (zip+xml) / .doc (binary) → text
            │   ├── DocBinaryExtractor.kt # legacy .doc parser: OLE2 + FIB + piece table (§9.4)
            │   ├── BinaryTextRecovery.kt # fallback UTF-16LE run recovery for .doc/.xls
            │   ├── DocxHtmlConverter.kt # .docx → formatted HTML (images, colours, tables) + → editable (§9.1)
            │   ├── DocxWriter.kt       # editor content → real .docx (OOXML, no library) (§9.3)
            │   ├── SpreadsheetExtractor.kt # .xlsx → text table / HTML grid; .xls best-effort (§9.2)
            │   ├── XlsxWriter.kt       # editor tab-table → real .xlsx (OOXML, no library) (§9.3)
            │   ├── PdfExtractor.kt     # PDF name check only (rendering is native, see util/)
            │   ├── TextDirection.kt    # Arabic-vs-Latin base-direction detection (§9.5)
            │   ├── UpdateChecker.kt    # GitHub-release update check + APK download
            │   ├── SettingsStore.kt    # DataStore: AppSettings + per-file encoding memory
            │   └── RecoveryStore.kt    # crash-recovery drafts (internal files)
            ├── editor/
            │   └── SyntaxHighlighter.kt # regex highlighter for 10+ languages
            ├── viewmodel/
            │   ├── EditorViewModel.kt  # all app logic (open/save/find/format/recovery…)
            │   └── UiStates.kt         # EditorTab, RichSpan, FindState, prompts, UiMessage
            ├── ui/
            │   ├── AppRoot.kt          # whole screen: toolbar, tabs, editor, all dialogs/viewers wiring
            │   ├── EditorArea.kt       # the editable surface (gutter, spans, bidi, paragraphs)
            │   ├── Dialogs.kt          # encoding/goto/zip/binary/discard/filename/update/crash dialogs
            │   ├── HtmlDocViewer.kt    # read-only WebView for Word/Excel formatted view (§9.6)
            │   ├── PdfImageViewer.kt   # full-screen PDF page-image viewer + pinch-zoom + OCR button (§9.7)
            │   ├── FindReplaceBar.kt   # find & replace UI
            │   ├── SettingsSheet.kt    # settings bottom sheet
            │   └── theme/Theme.kt      # Material 3 color schemes + syntax palette
            └── util/
                ├── LocaleManager.kt    # runtime AR/EN language switch (synchronous prefs)
                ├── PdfRenderHelper.kt  # native PdfRenderer wrapper: page→bitmap, hi-res OCR render (§9.7)
                ├── OcrHelper.kt        # Tesseract OCR: lang-data download, grayscale preprocess (§9.7)
                ├── PdfExporter.kt      # text → paginated PDF (RTL aware)
                ├── PrintHelper.kt      # Android print framework adapter
                ├── ShareHelper.kt      # FileProvider share intents
                └── HtmlExporter.kt     # rich text → standalone HTML

.github/workflows/android-build.yml   # CI: build debug+release APKs, ABI-split release job (§10)
```

---

## 4) Data & storage (no SQL DB)

There is **no relational database**. Two persistence mechanisms:

### 4.1 DataStore Preferences — `data/SettingsStore.kt`
File: `uts_settings` (Preferences DataStore). Holds `AppSettings`:

| Key | Type | Meaning |
|---|---|---|
| `theme` | String enum | SYSTEM / LIGHT / DARK |
| `font_size` | Float | default editor font size (sp) |
| `autosave` | Bool | autosave drafts on/off |
| `syntax` | Bool | syntax highlighting on/off |
| `line_numbers` | Bool | gutter on/off |
| `word_wrap` | Bool | wrap vs horizontal scroll |
| `save_folder_uri` / `save_folder_name` | String? | default save folder (SAF tree) |
| `editor_align` | Int | legacy global align (now per-paragraph; mostly unused) |
| `line_spacing` | Float | default line-height multiplier |
| `text_color` / `bg_color` | Int? | legacy global colour overrides (now per-selection) |
| `enc_<hash>` | String | **last-used encoding id per file** (key = `"enc_" + uri.hashCode()`) |

Language is **not** in DataStore — it lives in `util/LocaleManager.kt` using a
*synchronous* `SharedPreferences` (`uts_locale` / `lang`) because it must be read
in `Activity.attachBaseContext` before any UI exists.

### 4.2 Recovery drafts — `data/RecoveryStore.kt`
Directory: `filesDir/recovery/`. Per open editable tab:
- `<tabId>.meta` — JSON `{displayName, uri, encoding, lineEnding}`
- `<tabId>.txt`  — the raw content (UTF-8)

Written atomically (temp file + rename). Autosave every 30 s (see
`EditorViewModel.startAutosaveLoop`) and on `MainActivity.onStop`. On next launch,
any files present ⇒ unclean exit ⇒ `RecoveryDialog` offers restore/discard. On a
clean save or tab close the draft is deleted.

> If you later add a real DB (e.g. Room for a documents list / history), add it
> under `data/` and inject it into `EditorViewModel`. None exists today.

---

## 5) Core flows & algorithms

### 5.1 Open a file — `EditorViewModel.open(uri)`
1. `FileIo.queryMeta` → name + size; take persistable read permission.
2. If name is `.doc`/`.docx` → `WordExtractor.extract` (view/copy as text). `.zip` → entry picker.
3. If size > 500 MB → reject. Else read a 256 KB sample.
4. If a **remembered encoding** exists for this URI → load with it.
5. `EncodingDetector.looksBinary(sample)` → if binary (image/exe…), show "not a text file" prompt.
6. `EncodingDetector.detect(sample)` → if low confidence, show manual encoding dialog (live preview); else load.
7. Load: ≤ 16 MB ⇒ fully editable; larger ⇒ **read-only paged** mode
   (`FileIo.readLineWindow`, 5000-line pages) to keep memory bounded.

### 5.2 Encoding detection — `data/EncodingDetector.kt` (the heart)
Order of evidence (strongest first):
1. **BOM** (UTF-8/16/32).
2. **UTF-16 without BOM** (`utf16Guess`) — *checked before UTF-8/ASCII* because
   UTF-16LE Arabic bytes are coincidentally valid UTF-8. Uses a **structural
   high-byte test** (≈ all 16-bit units have a small/known high byte: 0x00-0x09,
   space, or Arabic Presentation Forms 0xFB-0xFE) + a decoded-text ratio.
3. **Pure ASCII / strict UTF-8** → UTF-8.
4. **juniversalchardet** statistical guess.
5. **Decode-and-score** across single-byte Arabic/Latin codepages. Arabic wins
   only when it produces ≥ 4 **clustered** Arabic letters (run-length ≥ 0.6 of
   Arabic chars) — so genuine Arabic is caught while accent-dense European text
   (isolated mis-decoded glyphs) is not. Chooses windows-1256 over ISO-8859-6 when
   both fit (fewest replacement chars).

`looksBinary` runs the NUL/control analysis **before** the UTF-8 check (NUL is a
valid UTF-8 codepoint), with a UTF-16 exception.

> All of this is **pure JVM** and was unit-tested standalone. To re-test, copy the
> functions into a `main()` and feed `"...".getBytes("windows-1256")` etc.

### 5.3 Lossless I/O — `data/FileIo.kt`
- `readAll` streams via `BufferedReader(InputStreamReader(charset))`, strips a UTF-8 BOM.
- `writeAll` writes BOM (if any) then the encoded text; normalises line endings to
  the document's original `LineEnding`.
- `mimeForName` derives the create-MIME from the file's extension so SAF never
  appends `.txt` (uses `application/octet-stream` for code extensions with no
  registered MIME). Used by the custom `CreateTextDocument` contract and `createInTree`.

### 5.4 Editor surface — `ui/EditorArea.kt`
- One `BasicTextField` with `TextDirection.Content` (per-line RTL/LTR; correct
  digits/brackets). Default font for plain text (monospace garbles Arabic on some
  ROMs); monospace for code.
- `VisualTransformation` layers, in order: **syntax** (regex) → **per-paragraph
  ParagraphStyle** (alignment + line height, tiled across *every* paragraph for
  docs ≤ 5000 lines so styles never bleed) → **rich SpanStyle** (bold/italic/
  colour/size/highlight) → **find-match** backgrounds. `OffsetMapping.Identity`
  (text length never changes).
- Line-number gutter is drawn in `drawBehind` using the captured `TextLayoutResult`
  (`getLineForOffset`/`getLineTop`), so it stays aligned even with wrapping.

### 5.5 Rich text — `viewmodel` + `util/HtmlExporter.kt`
- `RichSpan(start,end, bold/italic/underline/color/bg/sizeSp)` list per tab.
- Apply functions **clip same-attribute spans** out of the range first, then add
  the new one (new value replaces old; bold/italic toggle).
- `shiftSpans(old,new)` re-anchors offsets on every edit via a prefix/suffix diff.
- **Export HTML** serialises text + spans + per-paragraph alignment/spacing to a
  standalone HTML file (per-char effective style → grouped `<span>` runs;
  paragraphs as `<p dir="auto" style="text-align;line-height">`).
- Rich formatting & alignment/spacing are **display + HTML only**; saving as `.txt`
  stays plain (by design). HTML *import* (reopening formatted HTML into spans) is
  not yet implemented — a good next task.

### 5.6 Other
- **Word**: `.docx` = read `word/document.xml` from the OPC zip, strip markup,
  unescape entities. `.doc` = best-effort UTF-16LE run recovery (no Apache POI, to
  stay light). Both open as a new editable `.txt` buffer.
- **PDF/Print**: `PdfExporter` lays text with `StaticLayout` (first-strong bidi),
  paginated A4; `PrintHelper` feeds the same into the system print framework.
- **Find/replace**: regex/case/whole-word, match highlighting, replace-all with
  `$1` group expansion (`EditorViewModel` find section).
- **Localization**: `values/` + `values-ar/`; `LocaleManager.wrap` in
  `attachBaseContext`; switching language calls `recreate()`.
- **File associations**: `AndroidManifest.xml` declares MIME + 23 extension
  `pathPattern`s + `application/octet-stream` so the app appears in "Open with".

---

## 6) How to extend (common tasks)

- **Add an encoding:** add a `TextEncoding` constant + to `ALL` in
  `model/TextEncoding.kt`. If single-byte Arabic/Latin, also add it to the
  candidate lists in `EncodingDetector.scoreCandidates`.
- **Add a syntax language:** add to `SyntaxLanguage` (+ `fromFileName`) and a
  keyword set/rules in `editor/SyntaxHighlighter.kt`.
- **Add a toolbar tool:** add a `ToolButton`/menu in `ui/AppRoot.kt`
  (`CompactToolbar`) and a function in `EditorViewModel`.
- **Add a setting:** add a field+key+setter in `data/SettingsStore.kt`, surface it
  in `ui/SettingsSheet.kt`, consume it in `AppRoot`/`EditorArea`.
- **Add HTML import:** parse HTML → text + `RichSpan`s + paragraph maps; wire into
  `EditorViewModel.open` for `.html` when the user opts into "open as formatted".

---

## 7) Known limitations / honest notes

- 500 MB files are **read-only paged**; full in-place editing of huge files would
  need a piece-table/gap-buffer (not implemented).
- Rich formatting, per-paragraph alignment, and line spacing **do not persist in
  `.txt`** — only via **Export HTML**.
- `.doc` (legacy binary) extraction is approximate; `.docx` is clean.
- Per-paragraph styling is capped at 5000 lines for performance.
- The committed `release.keystore` is a **dev** key — replace for production.
- UI is compile-verified; there is no automated instrumented/UI test suite yet
  (good next task: add Compose UI tests + JVM tests for `EncodingDetector`).

---

## 8) Branch / repo

- Repo: `alaoufi/Text_editor`  ·  working branch: `claude/text-editor-app-hfafhv`
- Releases are published as GitHub Releases (per-ABI + universal APKs), not `dist/`.

---

# PART B — Office documents, PDF & OCR subsystem

Everything below was added on top of the core text editor. The design rule
throughout: **read/render/write Office formats with the JDK only** (zip + regex-XML
+ `android.graphics`) so the APK stays ~8.5 MB. No Apache POI, no PDFBox.

## 9) Document formats — file by file

### Open routing — `EditorViewModel.open(uri)`
```
name/……→ WordExtractor.isWord      → openWord()        (.docx formatted view / .doc text)
       → PdfExtractor.isPdf         → openPdf()         (always page-image viewer)
       → SpreadsheetExtractor.isSpreadsheet → openSpreadsheet()  (.xlsx grid / .xls text)
       → .zip                       → entry picker
       → else                       → encoding-detected text load
```
Each document type opens **read-only first** (`EditorTab.reading = true`); the
toolbar shows only Find + a prominent **Edit** button (`beginEdit()`), which reveals
the full editing tools. `stopEdit()` returns to reading mode.

### 9.1 `DocxHtmlConverter.kt` — .docx → formatted HTML, and → editable
Two entry points:
- **`toHtml(resolver, uri)`** — the read-only formatted view (rendered in a WebView):
  - Reads `word/document.xml`, `word/_rels/document.xml.rels` and `word/media/*` in
    **one zip pass** (`readEntries`, tolerant of a corrupt entry via `runCatching`).
  - Walks tables and the paragraphs between them **in document order**.
  - Runs → HTML with **bold/italic/underline**, **text colour** (`w:color`),
    **highlight** (`w:highlight`), tabs (`&emsp;`), line breaks.
  - **Images**: a run containing `<w:drawing>`/`<w:pict>` → resolve `r:embed` to a
    media file via the rels map → **downscale** (`BitmapFactory` + `inSampleSize`,
    cap `MAX_IMG_DIM=1400`, JPEG q80) → embed as a `data:` URI `<img>`. Downscaling
    is essential: full-size photos produce multi-MB HTML the WebView can't render.
  - **Tables**: `w:tbl→table`, cell shading (`w:shd w:fill`) → `background`.
  - **Page background** (`w:background w:color`) → `body{background}`.
  - **Direction**: base `dir` from `TextDirection.dominant` + `dir="auto"` on every
    block (see §9.5).
- **`toEditable(resolver, uri): EditableDoc`** — for the Edit button. Same structured
  walk but emits **plain text + `RichSpan`s + per-paragraph alignment map**, so the
  editable buffer keeps bold/italic/underline/colour/heading-size and alignment.
  Latin/number-only paragraphs in an RTL doc get End-alignment so they don't stick
  to the RTL start side. Returns `EditableDoc(text, spans, aligns)`.

### 9.2 `SpreadsheetExtractor.kt` — .xlsx / .xls
- `extract()` → tab-separated **text** table (shared strings, cell types, column
  refs, multiple sheets). `.xls` (legacy BIFF) → `BinaryTextRecovery`.
- `toHtml()` → an HTML `<table>` grid for the read-only view (RTL-aware).
- Regex-parses `xl/sharedStrings.xml`, `xl/workbook.xml`, `xl/worksheets/sheetN.xml`.
  `columnIndex()` maps `A→0, AA→26`. (Cell **fill colours** are not yet parsed — a
  good next task; see §11.)

### 9.3 Writers — `DocxWriter.kt` / `XlsxWriter.kt` (save back to native format)
A `.docx`/`.xlsx` is just an OPC **zip of XML**, assembled with `ZipOutputStream`:
- **DocxWriter**: each line → `<w:p>`; formatting runs from the tab's `RichSpan`s
  (`attrAt` computes effective per-char attrs, groups into `<w:r>` with `w:rPr`);
  tabs → `<w:tab/>`; alignment → `<w:jc>`; **Arabic paragraphs marked RTL**
  (`<w:bidi/>` + `<w:rtl/>`) so they open correctly in Word. Parts: `[Content_Types].xml`,
  `_rels/.rels`, `word/document.xml`.
- **XlsxWriter**: each line → `<row>`, tab-separated fields → cells; plain numbers →
  numeric `<v>`, everything else → `inlineStr` (Arabic exact). Parts add
  `xl/workbook.xml` (+ rels) and `xl/worksheets/sheet1.xml`.
- **Save routing** (`EditorViewModel.writeTo`): target name ends `.docx`→DocxWriter,
  `.xlsx`→XlsxWriter, else the normal text writer. The edit buffer keeps the original
  `report.docx`/`data.xlsx` name so Save targets the native format. Validated by
  opening the output in `python-docx`/`openpyxl`.

### 9.4 `DocBinaryExtractor.kt` — real legacy `.doc` parser (no library)
Parses the binary Word format so text comes out **clean and in order** (a raw byte
scan drags in style names + stream names as noise). Pipeline:
1. **OLE2 / Compound File Binary**: header → sector size, FAT (via DIFAT), directory
   entries, **mini-FAT + mini-stream** for small streams → `getStream(name)`.
2. **FIB** in the `WordDocument` stream: flag bit `0x0200` picks `0Table`/`1Table`;
   `fcClx`/`lcbClx` at `0x1A2`/`0x1A6` locate the piece table.
3. **Piece table (CLX/PlcPcd)**: each `PCD` FC bit `0x40000000` = 8-bit
   (**windows-1256**) at `fc/2`, else UTF-16LE at `fc`. Concatenate pieces in CP order.
4. `clean()` normalises Word control marks and **collapses blank lines** (diagram/
   SmartArt docs put an empty paragraph between every label → looked scattered).
Falls back to `BinaryTextRecovery` if the structure can't be parsed.

### 9.5 `TextDirection.kt` — RTL base direction
Counts strong **Arabic vs Latin** letters and returns `"rtl"`/`"ltr"`. Used instead
of relying on the *first character* — a real Arabic doc that starts with a digit or
an English title would otherwise flip the whole document LTR under `dir="auto"`.
The HTML views set an explicit base `dir` **and** `dir="auto"` per block so mixed
content resolves per-paragraph.

### 9.6 `HtmlDocViewer.kt` — the Word/Excel formatted view
Full-screen `Dialog` → `AndroidView { WebView }`. `loadDataWithBaseURL(null, html,
"text/html", "UTF-8", null)`; JS disabled; pinch-zoom on (`builtInZoomControls`,
`setSupportZoom`). Top bar: Close + file name + **Edit** (→ `editHtmlDoc()` extracts
the editable buffer). Shown when `viewModel.htmlViewer != null`.

### 9.7 PDF viewing + OCR — `PdfImageViewer.kt`, `PdfRenderHelper.kt`, `OcrHelper.kt`
- **Why images, not text:** PDF text extraction reordered/garbled RTL tables, so
  **all** PDFs are shown as page images that match a real reader exactly.
- `PdfRenderHelper.PdfPages`: wraps the platform `PdfRenderer` (copies the PDF to a
  cache file for a seekable fd). `pageCount` captured once at construction (reading it
  later crashed with *"Already closed"*). `render(i,widthPx)` for display (cap
  `MAX_DIM=2048`, ~4 MP); `renderForOcr(i,widthPx)` uncapped-for-display (up to
  `MAX_DIM_OCR=3000`, 12 MP) since OCR bitmaps aren't GPU textures. Idempotent
  `close()`; `lastError` for diagnostics.
- `PdfImageViewer`: `LazyColumn` of page bitmaps inside a `transformable` zoom box;
  a **stable holder** + `DisposableEffect(uri)` closes the doc only when the viewer
  truly leaves (keying on the changing render result closed it too early → the
  "closed" bug). Edit button OCRs the currently-visible page.
- `OcrHelper`: downloads `ara`+`eng` `tessdata_fast` on first use into `filesDir`.
  `recognizePage` renders at **2600px**, runs `preprocess()` (grayscale + high
  contrast over white — coloured cell fills otherwise defeat binarisation), sets
  `PSM_AUTO`, returns text. OCR result opens as a new editable `.txt` tab.

## 9.8 In-app updates — `UpdateChecker.kt`
Checks the GitHub *latest release* tag vs `BuildConfig.VERSION_NAME`; if newer, the
`UpdateDialog` downloads the APK and hands it to the system package installer
(`REQUEST_INSTALL_PACKAGES` + `FileProvider`). Silent on launch; manual via the menu.

## 9.9 Crash reporter — `UtsApplication.kt`
A global `UncaughtExceptionHandler` writes the stack trace to
`filesDir/last_crash.txt`; on next launch `CrashDialog` shows it (copyable). This is
how the device-only PDF/Compose bugs were diagnosed — keep it.

---

## 10) CI / release — `.github/workflows/android-build.yml`
- **build** job (every push/PR): `assembleDebug` + `assembleRelease`, uploads APK
  artifacts. Signing secrets (`RELEASE_KEYSTORE_BASE64`, …) are optional — falls back
  to the bundled dev key.
- **release** job (on `workflow_dispatch` with a `release_tag` input, since tag
  pushes are 403-blocked in this environment): builds, then stages **every ABI-split
  APK** as `GlobalTextEditor-<tag>-<abi>.apk` plus a device-agnostic
  `GlobalTextEditor-<tag>.apk` (the universal build), and publishes a GitHub Release.
- **Trigger it** from the API/UI with input `release_tag: vX.Y`. Bump
  `versionCode`/`versionName` first.

---

## 11) Known limitations / good next tasks (Office layer)
- **.xlsx cell colours / merged cells** are not parsed into the HTML grid yet
  (add `xl/styles.xml` fill parsing + `s=` style index; handle `<mergeCell>`).
- **Editing is text-based**: the Word/Excel *view* is rich (images/colours), but the
  *editable* buffer is text (+ `RichSpan`s for Word). A true in-grid spreadsheet
  editor / image-preserving Word editor is a large future feature.
- **.doc/.xls (legacy binary)** save back as `.txt` (no light binary writer); `.docx`/
  `.xlsx` round-trip natively.
- **Word tables on save** are written as tab-separated text (tab stops), not a
  reconstructed `w:tbl` grid.
- **OCR** is best-effort; for a digital "Excel→PDF" file, opening the original `.xlsx`
  is far more accurate than OCR-ing the PDF.
- Complex Word features (text boxes, SmartArt geometry, footnotes, equations) render
  as their text only.

---

## 12) Debugging playbook (what actually happened here)
- **PDF "keeps stopping"** → crash reporter revealed `IllegalStateException: Already
  closed` → fixed by capturing `pageCount` once and using a stable holder for the
  renderer (§9.7).
- **Word/PDF "scattered / jumbled"** → structured parsers (piece table for .doc,
  ordered walk for .docx) + blank-line collapse.
- **"يمين أكثر" / wrong direction on large docs** → `dir="auto"` took direction from
  the first char; fixed with `TextDirection` base-dir + per-block `dir="auto"` (§9.5).
- **"white / no images"** → images weren't embedded (added) and were too large
  (downscaled); page background applied (§9.1).
- **OCR returned "i 1 1"** on a coloured table → higher render resolution + grayscale
  preprocessing (§9.7).
- **Reproduce before fixing:** the Office bugs were reproduced by porting the Kotlin
  logic to Python against the user's real files and, for HTML, rendering the output
  in headless Chromium — do this before changing parser code.
