# Looply

Native Kotlin Android WebView wrapper.

- App: `Looply`
- Package: `com.brivanlabs.looply`
- Version: `1.0.0` / `versionCode 1`
- minSdk 24 / targetSdk 34 / compileSdk 34
- One Activity and one WebView.
- No Compose.
- No Material Components.
- No Fragments.
- No Navigation Component.
- No Firebase.
- No analytics.
- No ads.
- Site URL is defined in `app/src/main/kotlin/com/brivanlabs/looply/Config.kt`.

## Native features available to the website

The wrapper exposes `window.Android` to the HTTPS site. The bridge supports status and navigation bars, modal dimming, theme presets, persistent splash background color, camera/microphone/location permission requests, keep-screen-on, clipboard copy, text sharing, vibration, external URLs, app version and an app detection flag.

WebView also supports: JavaScript, DOM storage, Web SQL/database mode, normal HTTP cache, cookies, image/file picker, multiple-file selection, camera capture, video capture, audio capture, WebRTC camera/microphone requests, Web Geolocation/GPS requests, external-link routing, downloads through Android DownloadManager, Android back navigation and automatic reload after connectivity returns.

## Theme and splash behavior

Android 12+ uses the native system splash screen with the transparent Looply icon. The splash background follows the device light/dark theme by default. When the site calls `setSplashBackgroundColor(...)` or `setThemeMode(...)`, Looply persists the chosen color/theme and uses it during the next native launch as closely as the Android system allows.

The Android 12 system splash is rendered before Activity code can execute, so a website-specific color cannot be injected into that first system frame. The persisted color controls the native window immediately after that system frame.

## JavaScript examples

### Status bar and modal dim

```html
<script>
  if (window.Android) {
    window.Android.setStatusBarColor('#121212');
    window.Android.setStatusBarLight(false);

    // Slightly darken the status bar while a modal is open.
    window.Android.setStatusBarDimmed(true, 0.15);

    // Restore the original status-bar color when the modal closes.
    window.Android.setStatusBarDimmed(false, 0.15);
  }
</script>
```

### Theme adaptation

```html
<script>
  if (window.Android) {
    window.Android.setThemeMode('dark');
    window.Android.setSplashBackgroundColor('#0B0D10');
    window.Android.setStatusBarColor('#0B0D10');
    window.Android.setStatusBarLight(false);
    window.Android.setNavigationBarColor('#0B0D10');
    window.Android.setNavigationBarLight(false);
  }
</script>
```

Valid preset modes are `light`, `dark` and `system`.

### Camera and microphone

```html
<script>
  if (window.Android) {
    // Optional proactive permission prompt.
    window.Android.requestPermission('camera');
    window.Android.requestPermission('microphone');
  }

  async function startCameraAndMic() {
    const stream = await navigator.mediaDevices.getUserMedia({
      video: true,
      audio: true
    });
    return stream;
  }
</script>
```

The wrapper also handles the WebView `PermissionRequest` callback, so `getUserMedia()` can trigger the native permission dialogs automatically.

### GPS / location

```html
<script>
  if (window.Android) {
    window.Android.requestPermission('location');
  }

  navigator.geolocation.getCurrentPosition(
    position => {
      console.log(
        position.coords.latitude,
        position.coords.longitude
      );
    },
    error => console.error(error),
    { enableHighAccuracy: true, timeout: 15000 }
  );
</script>
```

### Image/file picker

```html
<input
  type="file"
  accept="image/*"
  multiple
/>
```

The native wrapper forwards selected `content://` URIs to the WebView. `capture` can also invoke the device camera/video/audio capture path when the website requests it.

### Clipboard, sharing and vibration

```html
<script>
  if (window.Android) {
    window.Android.copyText('Texto copiado pelo Looply');
    window.Android.shareText(
      'Conteúdo para partilhar',
      'Partilhar via Looply'
    );
    window.Android.vibrate(40);
  }
</script>
```

### Keep screen awake

```html
<script>
  if (window.Android) {
    window.Android.setKeepScreenOn(true);
  }
</script>
```

### App detection/version

```javascript
if (window.Android && window.Android.isLooplyApp()) {
  console.log('Looply native app', window.Android.getAppVersion());
}
```

## Regenerating the icon from the supplied image

The supplied source image is processed with ImageMagick. The helper removes the near-black background and creates all launcher densities plus the Android 12+ splash icon:

```bash
chmod +x tools/generate-icons.sh
./tools/generate-icons.sh /path/to/icon_512x512_under_10kb.webp
```

The current project already contains the processed transparent icon.

## Build-size target

Both debug and release enable R8 and resource shrinking. The Codemagic workflow rejects an APK above 400 KiB instead of silently accepting a larger file. Actual APK size must be measured by the build environment because the Android Gradle Plugin and transitive dependencies participate in packaging.

## Debug signing

The personal CI/debug keystore is `looply-debug.keystore` and is intentionally versioned for this requested setup. Password: `android`.

## Local build

```bash
chmod +x ./gradlew
./gradlew clean assembleDebug --stacktrace
ls -lh app/build/outputs/apk/debug/*.apk
```

## Exact APK-size check on Linux/Termux

```bash
APK="app/build/outputs/apk/debug/app-debug.apk"
BYTES=$(stat -c%s "$APK")
echo "${BYTES} bytes / $((BYTES / 1024)) KiB"
if [ "$BYTES" -gt 409600 ]; then exit 1; fi
```

## Codemagic

`codemagic.yaml` is at the repository root. The workflow is `looply-android-debug`, uses `mac_mini_m2`, caches Gradle, builds `assembleDebug --stacktrace`, checks the 400 KiB limit and publishes the resulting APK as a Codemagic artifact. 