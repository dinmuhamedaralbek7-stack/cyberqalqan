# WebView көпірінің әдістері JavaScript-тен шақырылады — оларды сақтау керек
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keep class kz.cyberqalqan.CheckActivity$Bridge { *; }
# Логтарды релизде өшіру
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
