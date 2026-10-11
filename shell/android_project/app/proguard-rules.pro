# Proguard rules for Savage Browser
-keep class com.savage.soulbrowser.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
