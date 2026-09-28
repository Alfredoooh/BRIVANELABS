# Looply JavaScript bridge is invoked by WebView reflection.
-keepattributes *Annotation*
-keep class com.angozone.app.ao.WebAppInterface { public *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
