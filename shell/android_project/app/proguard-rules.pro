# Savage Browser R8 rules.
#
# The JavaScript bridge is called by name from the web page, so its @JavascriptInterface
# methods are the one reflective contract that must survive shrinking.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Activities, the Application subclass and every other component declared in AndroidManifest.xml
# are kept by the rules AGP generates from the merged manifest (aapt_rules.txt); no blanket
# "-keep class com.savage.soulbrowser.**" rule is needed, which keeps R8 shrinking meaningful.
