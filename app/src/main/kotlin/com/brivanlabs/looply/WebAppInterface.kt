package com.brivanlabs.looply

import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import androidx.core.view.WindowInsetsControllerCompat

/**
 * JavaScript bridge exposed to the website as:
 *
 * window.Android
 *
 * All native UI operations are dispatched to the Activity main thread.
 *
 * R8/ProGuard must keep this class and its public @JavascriptInterface
 * methods because WebView invokes them reflectively from JavaScript.
 */
class WebAppInterface(
    private val activity: MainActivity
) {

    private val preferences by lazy {
        activity.getSharedPreferences(
            Config.PREFS,
            Context.MODE_PRIVATE
        )
    }

    /**
     * Changes the status bar background color.
     *
     * Example:
     * window.Android.setStatusBarColor("#121212");
     */
    @JavascriptInterface
    fun setStatusBarColor(hexColor: String) {
        activity.runOnUiThread {
            parseColorOrNull(hexColor)?.let { color ->
                activity.updateStatusBarColor(color)
            }
        }
    }

    /**
     * Controls whether the status bar uses dark icons.
     *
     * true  = dark status bar icons
     * false = light/white status bar icons
     *
     * The value is persisted so the native launch window can use the same
     * preference on the next application start.
     */
    @JavascriptInterface
    fun setStatusBarLight(isLight: Boolean) {
        activity.runOnUiThread {
            preferences.edit()
                .putBoolean(
                    Config.PREF_STATUS_LIGHT,
                    isLight
                )
                .apply()

            WindowInsetsControllerCompat(
                activity.window,
                activity.window.decorView
            ).isAppearanceLightStatusBars = isLight
        }
    }

    /**
     * Changes the navigation bar background color.
     */
    @JavascriptInterface
    fun setNavigationBarColor(hexColor: String) {
        activity.runOnUiThread {
            parseColorOrNull(hexColor)?.let { color ->
                activity.updateNavigationBarColor(color)
            }
        }
    }

    /**
     * Controls whether the navigation bar uses dark icons.
     *
     * true  = dark navigation bar icons
     * false = light navigation bar icons
     */
    @JavascriptInterface
    fun setNavigationBarLight(isLight: Boolean) {
        activity.runOnUiThread {
            preferences.edit()
                .putBoolean(
                    Config.PREF_NAVIGATION_LIGHT,
                    isLight
                )
                .apply()

            WindowInsetsControllerCompat(
                activity.window,
                activity.window.decorView
            ).isAppearanceLightNavigationBars = isLight
        }
    }

    /**
     * Darkens the current status bar color when a website modal opens.
     *
     * amount:
     * 0.0  = no darkening
     * 0.15 = approximately 15% darker
     * 0.30 = approximately 30% darker
     *
     * Example:
     * window.Android.setStatusBarDimmed(true, 0.15);
     *
     * Disable:
     * window.Android.setStatusBarDimmed(false, 0.15);
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
     * Changes the native system theme preset.
     *
     * Supported:
     * "light"
     * "dark"
     * "system"
     */
    @JavascriptInterface
    fun setThemeMode(mode: String) {
        activity.runOnUiThread {
            activity.updateThemeMode(
                mode
            )
        }
    }

    /**
     * Saves the website background/splash color so the next native launch
     * can approximate the website theme before the WebView renders.
     */
    @JavascriptInterface
    fun setSplashBackgroundColor(hexColor: String) {
        activity.runOnUiThread {
            parseColorOrNull(hexColor)?.let { color ->
                activity.updateSplashColor(
                    color
                )
            }
        }
    }

    /**
     * Keeps the WebView screen on.
     *
     * true  = prevent screen timeout while the WebView is active
     * false = normal Android screen timeout behavior
     */
    @JavascriptInterface
    fun setKeepScreenOn(enabled: Boolean) {
        activity.runOnUiThread {
            activity.requestKeepScreenOn(
                enabled
            )
        }
    }

    /**
     * Copies text to the Android clipboard.
     */
    @JavascriptInterface
    fun copyText(text: String) {
        activity.runOnUiThread {
            activity.copyToClipboard(
                text
            )
        }
    }

    /**
     * Opens the Android share sheet.
     */
    @JavascriptInterface
    fun shareText(
        text: String,
        title: String
    ) {
        activity.runOnUiThread {
            activity.shareText(
                text,
                title
            )
        }
    }

    /**
     * Triggers a short native vibration.
     */
    @JavascriptInterface
    fun vibrate(
        milliseconds: Long
    ) {
        activity.runOnUiThread {
            activity.vibrate(
                milliseconds
            )
        }
    }

    /**
     * Opens a URL using an external Android application.
     */
    @JavascriptInterface
    fun openExternalUrl(
        url: String
    ) {
        activity.runOnUiThread {
            activity.openExternalUrl(
                url
            )
        }
    }

    /**
     * Proactively asks Android for a native permission.
     *
     * Supported:
     *
     * "camera"
     * "microphone"
     * "mic"
     * "audio"
     * "location"
     * "gps"
     * "all"
     * "media"
     */
    @JavascriptInterface
    fun requestPermission(
        kind: String
    ) {
        activity.runOnUiThread {
            activity.requestNativePermissions(
                kind
            )
        }
    }

    /**
     * Returns the current Looply application version.
     */
    @JavascriptInterface
    fun getAppVersion(): String {
        return activity.appVersion()
    }

    /**
     * Simple detection helper for website code.
     */
    @JavascriptInterface
    fun isLooplyApp(): Boolean {
        return true
    }

    /**
     * Parses a CSS/Android compatible color safely.
     *
     * Invalid values are ignored instead of crashing the WebView host.
     */
    private fun parseColorOrNull(
        value: String
    ): Int? {
        return try {
            Color.parseColor(
                value.trim()
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}