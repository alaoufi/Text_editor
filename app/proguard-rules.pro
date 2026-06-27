# Keep universalchardet detector classes (loaded reflectively in places).
-keep class org.mozilla.universalchardet.** { *; }
-dontwarn org.mozilla.universalchardet.**

# Compose already ships consumer rules; nothing custom needed otherwise.
