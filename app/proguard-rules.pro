# Keep universalchardet detector classes (loaded reflectively in places).
-keep class org.mozilla.universalchardet.** { *; }
-dontwarn org.mozilla.universalchardet.**

# PDFBox-Android (PDF text extraction). Keep its classes and silence warnings
# for the desktop/crypto APIs it references but that aren't on Android.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**

# Compose already ships consumer rules; nothing custom needed otherwise.
