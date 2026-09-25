# ScoreZone

Native Kotlin Android WebView wrapper.

- Package: `com.brivanelabs.scorezone`
- Version: `1.0.0` / `versionCode 1`
- minSdk 24 / targetSdk 34 / compileSdk 34
- One Activity and one WebView; no Compose, Material, Fragments, Navigation Component, Firebase, analytics, or ads.
- The site URL is defined only in `app/src/main/kotlin/com/brivanelabs/scorezone/Config.kt`.

## Important build-size constraint

The project enables `minifyEnabled true` and `shrinkResources true` for both debug and release. A hard 400 KiB CI gate is also included. However, 400 KiB is not a technically guaranteed APK size for a Kotlin + AndroidX Activity/WebView application. The CI gate intentionally fails rather than silently accepting an oversized APK.

## Debug keystore

The personal debug keystore is `scorezone-debug.keystore` and is intentionally not ignored by `.gitignore` for this personal CI workflow.
