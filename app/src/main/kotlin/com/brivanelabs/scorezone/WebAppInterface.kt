package com.brivanelabs.scorezone

import android.graphics.Color
import android.webkit.JavascriptInterface
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Small JavaScript bridge exposed as window.Android inside the ScoreZone WebView.
 *
 * The public methods are intentionally kept by app/proguard-rules.pro because
 * WebView calls them reflectively from JavaScript and R8 otherwise may remove
 * or rename them during shrinking.
 */
class WebAppInterface(
    private val activity: MainActivity
) {

    @JavascriptInterface
    fun setStatusBarColor(hexColor: String) {
        activity.runOnUiThread {
            try {
                activity.window.statusBarColor = Color.parseColor(hexColor)
            } catch (_: IllegalArgumentException) {
                // Invalid web input is ignored rather than crashing the Activity.
            }
        }
    }

    @JavascriptInterface
    fun setStatusBarLight(isLight: Boolean) {
        activity.runOnUiThread {
            WindowInsetsControllerCompat(
                activity.window,
                activity.window.decorView
            ).isAppearanceLightStatusBars = isLight
        }
    }

    @JavascriptInterface
    fun setNavigationBarColor(hexColor: String) {
        activity.runOnUiThread {
            try {
                activity.window.navigationBarColor = Color.parseColor(hexColor)
            } catch (_: IllegalArgumentException) {
                // Invalid web input is ignored rather than crashing the Activity.
            }
        }
    }
}
