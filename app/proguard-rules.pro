# Keep the libxposed entry class and keep its metadata valid if the rule is
# later relaxed to allow obfuscation. LSPosed discovers this class solely via
# META-INF/xposed/java_init.list.
-adaptresourcefilecontents META-INF/xposed/java_init.list
-keep class com.mifan.kt.HookEntry { *; }

# Keep libxposed API references. They are compileOnly/provided by LSPosed at runtime.
-dontwarn io.github.libxposed.**
-keep class io.github.libxposed.** { *; }

# DexKit's AAR supplies a narrowly scoped consumer rule that keeps the JNI
# native methods and their descriptor classes.  Do not keep the entire API:
# that would retain several thousand unused query/result classes and make the
# release APK needlessly large.
-keepclasseswithmembernames,includedescriptorclasses class org.luckypray.dexkit.** {
    native <methods>;
}
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
