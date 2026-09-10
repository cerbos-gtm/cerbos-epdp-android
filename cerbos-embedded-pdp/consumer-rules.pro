# The bridge calls these from JavaScript by name; keep them through R8.
-keepclassmembers class dev.cerbos.epdp.** {
    @android.webkit.JavascriptInterface <methods>;
}
