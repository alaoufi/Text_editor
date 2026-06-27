# Global Text Editor · محرر النصوص العالمي

A fast, lightweight Android text editor with **first-class Arabic support** and
**automatic encoding detection** that never corrupts text — Arabic, English, or
any world encoding.

محرر نصوص أندرويد سريع وخفيف مع **دعم متميّز للغة العربية** و**اكتشاف تلقائي
للترميز** بدون أي تلف للنصوص العربية أو الإنجليزية أو أي ترميز عالمي.

> Top priority / الأولوية القصوى: **Arabic encoding is never corrupted under any circumstance.**

---

## Download / التحميل

Pre-built signed APK: [`dist/GlobalTextEditor-v1.5-release.apk`](dist/GlobalTextEditor-v1.5-release.apk) (~1.4 MB).

> The release is signed with the self-signed key in `release.keystore` (dev key).
> **Replace it with your own keystore before publishing to Google Play.**

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
  Java, Kotlin, PHP.
- **Dark / light / system theme** (Material Design 3, dynamic color on Android 12+).
- **Share**, **Export to PDF**, **Print** (Android print framework), **new files**,
  and **multiple tabs**.
- **Open text files inside ZIP archives** without manual extraction.
- **Status bar**: line numbers, word / character / line counts, file size,
  current encoding, and caret position.
- **Crash-safe autosave** every 30 seconds with **recovery on next launch**.
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
