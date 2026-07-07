# Keep the libxposed entry class name; LSPosed loads it from META-INF/xposed/java_init.list.
-keep class com.mifan.kt.HookEntry { *; }

# Keep libxposed API references. They are compileOnly/provided by LSPosed at runtime.
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }

# Android framework / host app classes are resolved from target processes at runtime.
-dontwarn android.**
-dontwarn androidx.**
-dontwarn miuix.**
-dontwarn com.android.**
-dontwarn com.miui.**
