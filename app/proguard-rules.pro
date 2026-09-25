# ScoreZone R8 rules.
# The WebView JavaScript bridge is invoked reflectively by JavaScript.
# Keep the class, public constructor and public bridge method names exactly so
# window.Android.setStatusBarColor/setStatusBarLight/setNavigationBarColor work.
-keep class com.brivanelabs.scorezone.WebAppInterface { public *; }
