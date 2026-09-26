# Looply JavaScript bridge is invoked by WebView reflection.
-keepattributes *Annotation*
-keep class com.brivanlabs.looply.WebAppInterface { public *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
