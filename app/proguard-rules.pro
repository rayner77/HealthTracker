# Keep line numbers/source info so crashes are easier to read while testing release builds.
-keepattributes SourceFile,LineNumberTable

# Optional but useful for readable stack traces and Crashlytics-style deobfuscation workflows.
-keep public class * extends java.lang.Exception

# Optional: remove noisy debug/info/verbose logs from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}