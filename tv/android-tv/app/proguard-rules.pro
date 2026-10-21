# The WebView bridge is reflected by Chromium; keep its public surface stable.
-keep class com.savage.zeroplay.tv.MainActivity$ZeroPlayTvBridge { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
