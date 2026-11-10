# Preserve the same production contracts validated for the current public R8 APK.
-keep class go.** { *; }
-keep class studio.networkcore.** { *; }
-keepattributes InnerClasses,EnclosingMethod
-keep,allowoptimization class com.forcemind.chatgpt.profiles.EnvironmentBrowserActivity { <init>(); }
-keep,allowoptimization class com.forcemind.chatgpt.profiles.EnvironmentBrowserActivity$Slot* { <init>(); }
-keepnames class org.yaml.snakeyaml.**
-keep class com.forcemind.chatgpt.profiles.CIBuild {
    static void installLocalExportProbe(java.io.File);
    static void installHandoffProbe(java.io.File);
}
