# Global Text Editor · محرر النصوص العالمي

A fast, lightweight Android text editor with **first-class Arabic support** and
**automatic encoding detection** that never corrupts text — Arabic, English, or
any world encoding.

محرر نصوص أندرويد سريع وخفيف مع **دعم متميّز للغة العربية** و**اكتشاف تلقائي
للترميز** بدون أي تلف للنصوص العربية أو الإنجليزية أو أي ترميز عالمي.

> Top priority / الأولوية القصوى: **Arabic encoding is never corrupted under any circumstance.**

---

## Download / التحميل

Pre-built APKs are produced by CI on every push and attached to tagged
releases:

- **Latest build:** the **Actions** tab → newest run → `GlobalTextEditor-release`
  artifact.
- **Tagged release:** the **Releases** page (a signed APK is attached
  automatically when a `v*` tag is pushed).

> CI builds are signed with the self-signed key in `release.keystore` (dev key)
> unless production signing secrets are configured (see **Production signing**
> below). **Use your own keystore before publishing to Google Play.**

نسخ APK الجاهزة يبنيها CI مع كل دفعة، وتُرفق بالإصدارات الموسومة: من تبويب
**Actions** (أحدث تشغيل) أو من صفحة **Releases** عند دفع وسم `v*`.

## Features / المزايا

- **Open / edit / save** TXT, CSV, JSON, XML, HTML/HTM, CSS, JS, TS, SQL, PHP,
  JAVA, KOTLIN, PY, MD, LOG, YAML, INI, CONF, BAT, SH — and any other text file
  regardless of extension.
- **Automatic encoding detection** with a manual fallback dialog that shows a
  **live preview** and **remembers the last encoding per file**. Supported:
  UTF-8, UTF-8 BOM, UTF-16 LE/BE, UTF-32 LE/BE, ASCII, ISO-8859-1,
  ISO-8859-6 (Arabic), Windows-1256 / CP1256 (Arabic), Windows-1252, CP720,
  GBK, Shift-JIS, EUC-KR.
- **No data loss** on open / edit / save / re-save / share — line endings and
  BOMs round-trip exactly; saves default to the original encoding.
- **Large files up to 500 MB** via streaming reads; files above 16 MB open in a
  memory-safe, paged **read-only** mode so the UI never freezes.
- **Find / Replace** with **Regex**, match case, whole word, result highlighting,
  and **Go to line**.
- **Smart bidirectional text**: Arabic RTL, English LTR, and mixed lines render
  correctly — digits, brackets and punctuation are never flipped
  (`TextDirection.Content`).
- **Syntax highlighting** for SQL, HTML, CSS, JavaScript, JSON, XML, Python,
  Java, Kotlin, PHP, **Markdown**, and **YAML**.
- **Text transforms**: sort lines (A→Z / Z→A), remove duplicate lines, trim
  trailing whitespace, duplicate the current line, and UPPER/lower-case the
  selection — applied to the selected lines or the whole document, all undoable.
- **Dark / light / system theme** (Material Design 3, dynamic color on Android 12+).
- **Share**, **Export to PDF**, **Print** (Android print framework), **new files**,
  and **multiple tabs**.
- **Open text files inside ZIP archives** without manual extraction.
- **Read PDF, Word and Excel files**: extracts the text into an editable buffer
  for viewing, copying, and re-saving as text. PDF uses Apache PDFBox
  (Unicode/Arabic-safe); `.docx` keeps table structure; `.xlsx` is parsed into a
  tab-separated table (shared strings, cell types, columns, multiple sheets).
  Legacy binary `.doc`/`.xls` are recovered best-effort. Formatting is not
  preserved.
- **Scanned PDFs**: image-only PDFs open in a read-only page-image viewer;
  tapping **Edit** runs on-device **OCR** (Tesseract, Arabic + English) to turn
  the pages into editable text.
- **Status bar**: line numbers, word / character / line counts, file size,
  current encoding, and caret position.
- **Crash-safe autosave** every 30 seconds with **recovery on next launch**.
- **In-app updates**: checks GitHub Releases for a newer build and downloads &
  installs the APK from inside the app (also available from the ⋮ menu).
- **Full Arabic & English UI** with **in-app language switching** (no reinstall).
- Android 8.0 (API 26) → 14, phones and tablets, small and large screens.

## Architecture / البنية

Kotlin · Jetpack Compose · **MVVM** · ViewModel · Coroutines · DataStore ·
Storage Access Framework (SAF).

```
app/src/main/java/com/uts/editor/
├── data/         EncodingDetector, FileIo (SAF streaming), ZipSupport,
│                 RecoveryStore (autosave), SettingsStore (DataStore)
├── model/        TextEncoding, DocumentState, SyntaxLanguage, TextStats
├── editor/       SyntaxHighlighter
├── viewmodel/    EditorViewModel, EditorTab, UI states
├── ui/           AppRoot, EditorArea, dialogs, find bar, settings, theme
└── util/         LocaleManager, PdfExporter, PrintHelper, ShareHelper
```

### How Arabic safety is guaranteed

`EncodingDetector` layers evidence strongest-first: BOM → strict UTF-8
validation → statistical guess (juniversalchardet) → a **decode-and-score** pass
across the Arabic/Latin single-byte codepages. The Arabic decision is **density-
and run-length aware**: a codepage wins as Arabic only when it produces a
substantial proportion of Arabic letters *that form contiguous words* — so
genuine Arabic is always caught, while accent-dense European text (whose accents
would decode to isolated Arabic glyphs) is never mistaken for Arabic. When
confidence is low, the user is prompted with a live preview instead of guessing.

## Build / البناء

```bash
# Requires Android SDK (compileSdk 34, build-tools 34.0.0)
./gradlew assembleRelease      # signed, shrunk APK (~1.4 MB)
./gradlew assembleDebug        # debug APK
```

Output: `app/build/outputs/apk/release/app-release.apk`.

### Continuous integration / التكامل المستمر

Every push and pull request runs `.github/workflows/android-build.yml`, which
builds both the **debug** and **release** APKs on GitHub Actions (JDK 17,
Gradle wrapper) and uploads them as downloadable artifacts
(`GlobalTextEditor-debug`, `GlobalTextEditor-release`) — grab a freshly built
APK from the **Actions** tab without building locally.

مع كل دفعة (push) أو طلب دمج (PR) يبني GitHub Actions نسختي debug و release
تلقائياً ويرفعهما كملفات قابلة للتنزيل من تبويب **Actions**.

### Production signing / التوقيع للإنتاج

The release build is signed with the bundled self-signed **dev** key by default
so it always produces an installable APK. **Do not ship that key.** For a real
release, sign with your own private keystore via the environment — no secrets in
source:

| Variable / secret | Meaning |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | your `.keystore` file, base64-encoded (CI only) |
| `RELEASE_STORE_FILE` | path to the keystore (set automatically in CI; set it yourself for local signed builds) |
| `RELEASE_STORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | key alias |
| `RELEASE_KEY_PASSWORD` | key password |

In GitHub: **Settings → Secrets and variables → Actions** → add the four
`RELEASE_*` secrets plus `RELEASE_KEYSTORE_BASE64`
(`base64 -w0 my-release.keystore`). The CI then signs with your key; with no
secrets set it falls back to the dev key. Locally, export the `RELEASE_*`
variables (pointing `RELEASE_STORE_FILE` at your keystore) before
`./gradlew assembleRelease`. For Play Store distribution, also enable
**Play App Signing**.
