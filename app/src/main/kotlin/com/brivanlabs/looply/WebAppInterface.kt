package com.brivanlabs.looply

import android.graphics.Color
import android.webkit.JavascriptInterface

/**
 * Small, R8-safe JavaScript bridge exposed as window.Android.
 *
 * Every method that changes native UI is posted to the Activity main thread.
 */
class WebAppInterface(
    private val activity: MainActivity
) {

    @JavascriptInterface
    fun setStatusBarColor(hexColor: String) {
        activity.runOnUiThread {
            parseColorOrNull(hexColor)?.let {
                activity.updateStatusBarColor(it)
            }
        }
    }

    @JavascriptInterface
    fun setStatusBarLight(isLight: Boolean) {
        activity.runOnUiThread {
            activity.updateStatusBarLight(isLight)
        }
    }

    @JavascriptInterface
    fun setNavigationBarColor(hexColor: String) {
        activity.runOnUiThread {
            parseColorOrNull(hexColor)?.let {
                activity.updateNavigationBarColor(it)
            }
        }
    }

    @JavascriptInterface
    fun setNavigationBarLight(isLight: Boolean) {
        activity.runOnUiThread {
            activity.updateNavigationBarLight(isLight)
        }
    }

    /**
     * Darkens only the status bar using the last color supplied by the website.
     * amount=0.15 means approximately 15% darker.
     */
    @JavascriptInterface
    fun setStatusBarDimmed(
        enabled: Boolean,
        amount: Float
    ) {
        activity.runOnUiThread {
            activity.updateStatusBarDimmed(
                enabled,
                amount
            )
        }
    }

    /**
     * Equivalent helper for the navigation bar.
     */
    @JavascriptInterface
    fun setNavigationBarDimmed(
        enabled: Boolean,
        amount: Float
    ) {
        activity.runOnUiThread {
            activity.updateNavigationBarDimmed(
                enabled,
                amount
            )
        }
    }

    /**
     * Applies a common dark/light/system theme preset and remembers it for the
     * next launch so the native launch window can approximate the website theme.
     */
    @JavascriptInterface
    fun setThemeMode(mode: String) {
        activity.runOnUiThread {
            activity.updateThemeMode(mode)
        }
    }

    /**
     * Stores the site's current surface/background color for the next launch.
     */
    @JavascriptInterface
    fun setSplashBackgroundColor(hexColor: String) {
        activity.runOnUiThread {
            parseColorOrNull(hexColor)?.let {
                activity.updateSplashColor(it)
            }
        }
    }

    @JavascriptInterface
    fun setKeepScreenOn(enabled: Boolean) {
        activity.runOnUiThread {
            activity.requestKeepScreenOn(enabled)
        }
    }

    @JavascriptInterface
    fun copyText(text: String) {
        activity.runOnUiThread {
            activity.copyToClipboard(text)
        }
    }

    @JavascriptInterface
    fun shareText(text: String, title: String) {
        activity.runOnUiThread {
            activity.shareText(text, title)
        }
    }

    @JavascriptInterface
    fun vibrate(milliseconds: Long) {
        activity.runOnUiThread {
            activity.vibrate(milliseconds)
        }
    }

    @JavascriptInterface
    fun openExternalUrl(url: String) {
        activity.runOnUiThread {
            activity.openExternalUrl(url)
        }
    }

    /**
     * Optional proactive runtime permission request. The website can still use
     * getUserMedia and geolocation normally; WebChromeClient also requests the
     * required native permissions automatically when those APIs are invoked.
     */
    @JavascriptInterface
    fun requestPermission(kind: String) {
        activity.runOnUiThread {
            activity.requestNativePermissions(kind)
        }
    }

    @JavascriptInterface
    fun getAppVersion(): String = activity.appVersion()

    @JavascriptInterface
    fun isLooplyApp(): Boolean = true

    private fun parseColorOrNull(value: String): Int? {
        return try {
            Color.parseColor(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
