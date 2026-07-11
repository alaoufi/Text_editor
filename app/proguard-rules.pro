# Keep universalchardet detector classes (loaded reflectively in places).
-keep class org.mozilla.universalchardet.** { *; }
-dontwarn org.mozilla.universalchardet.**

# Tesseract4Android (OCR) uses JNI; keep its Java classes.
-keep class com.googlecode.tesseract.android.** { *; }
-keep class org.opencv.** { *; }
-dontwarn com.googlecode.tesseract.android.**
-dontwarn org.opencv.**

# Compose already ships consumer rules; nothing custom needed otherwise.
