# Keep universalchardet detector classes (loaded reflectively in places).
-keep class org.mozilla.universalchardet.** { *; }
-dontwarn org.mozilla.universalchardet.**

# PDFBox-Android (PDF text extraction). Keep ALL of its classes (it loads fonts
# and parsers reflectively, so partial keeps break text extraction at runtime
# even though the build succeeds) and silence warnings for the desktop/crypto
# APIs it references but that aren't on Android.
-keep class com.tom_roush.** { *; }
-keep class com.gemalto.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.**
-dontwarn java.awt.**
-dontwarn javax.**

# Compose already ships consumer rules; nothing custom needed otherwise.
