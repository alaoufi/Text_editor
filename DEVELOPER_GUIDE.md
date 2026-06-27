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
| Charset detection | `com.github.albfernandez:juniversalchardet:2.5.0` (only 3rd-party lib) |
| Persistence | `androidx.datastore:datastore-preferences:1.1.1` |
| File access | Storage Access Framework + `androidx.documentfile` |

Everything else is AndroidX core/lifecycle/activity-compose. No network, no DB,
no DI framework.

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
            │   ├── WordExtractor.kt    # .docx (zip+xml) and .doc (best-effort) text extraction
            │   ├── SettingsStore.kt    # DataStore: AppSettings + per-file encoding memory
            │   └── RecoveryStore.kt    # crash-recovery drafts (internal files)
            ├── editor/
            │   └── SyntaxHighlighter.kt # regex highlighter for 10+ languages
            ├── viewmodel/
            │   ├── EditorViewModel.kt  # all app logic (open/save/find/format/recovery…)
            │   └── UiStates.kt         # EditorTab, RichSpan, FindState, prompts, UiMessage
            ├── ui/
            │   ├── AppRoot.kt          # whole screen: toolbar, tabs, editor, dialogs wiring
            │   ├── EditorArea.kt       # the editable surface (gutter, spans, bidi, paragraphs)
            │   ├── Dialogs.kt          # encoding/goto/zip/binary/discard/filename/save-encoding dialogs
            │   ├── FindReplaceBar.kt   # find & replace UI
            │   ├── SettingsSheet.kt    # settings bottom sheet
            │   └── theme/Theme.kt      # Material 3 color schemes + syntax palette
            └── util/
                ├── LocaleManager.kt    # runtime AR/EN language switch (synchronous prefs)
                ├── PdfExporter.kt      # text → paginated PDF (RTL aware)
                ├── PrintHelper.kt      # Android print framework adapter
                ├── ShareHelper.kt      # FileProvider share intents
                └── HtmlExporter.kt     # rich text → standalone HTML
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

- Repo: `alaoufi/t_office`  ·  working branch: `claude/android-text-editor-arabic-ny1qrs`
- Each release is committed and the APK placed under `dist/`.
