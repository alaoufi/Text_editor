# Compose ships its own consumer rules; nothing custom needed for it.

# OpenCV is accessed through JNI — keep its classes and native methods.
-keep class org.opencv.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn org.opencv.**
