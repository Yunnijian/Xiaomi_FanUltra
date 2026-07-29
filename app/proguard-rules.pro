# Keep the libxposed entry class name; LSPosed loads it from META-INF/xposed/java_init.list.
-keep class com.mifan.kt.HookEntry { *; }

# Keep libxposed API references. They are compileOnly/provided by LSPosed at runtime.
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }

# DexKit (native + JNI bridge). Used at runtime inside host processes.
-keep class org.luckypray.dexkit.** { *; }
-keepclassmembers class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**
-keepclasseswithmembernames class * {
    native <methods>;
}

# Android framework / host app classes are resolved from target processes at runtime.
-dontwarn android.**
-dontwarn androidx.**
-dontwarn miuix.**
-dontwarn com.android.**
-dontwarn com.miui.**
