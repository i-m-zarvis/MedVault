# MedVault — Android starter

Private NEET PG lecture library. This is the initial Android project foundation, not the finished Drive-connected app.

## Current state
- Dark Jetpack Compose home screen
- Android project configured for cloud builds
- GitHub Actions workflow builds a debug APK
- Dependencies prepared for Navigation, Room and Media3

## Build from a phone using GitHub
1. Create/sign in to a GitHub account.
2. Create a new **public or private** repository named `MedVault`.
3. Upload the contents of this folder to the repository root. Make sure `.github/workflows/android.yml` is included.
4. Open the repository's **Actions** tab.
5. Enable Actions if GitHub asks.
6. Run **Build MedVault APK** using **Run workflow**, or push a commit.
7. Open the successful workflow run and download the `MedVault-debug` artifact.
8. Extract the downloaded ZIP and install `app-debug.apk` on your Android phone. Android may ask you to allow installation from that browser/file manager.

## Technical baseline
- Android Gradle Plugin 8.7.3
- Gradle 8.9
- JDK 17
- Kotlin 2.0.21
- Compile SDK 35
- Minimum Android API 26

## Next development stages
1. Google OAuth and select a private Drive root folder.
2. Recursive Drive folder indexing and metadata cache.
3. Authenticated byte-range video streaming in Media3.
4. Room-backed playback progress and search.
5. Offline download queue and storage controls.

Do not put OAuth client secrets, refresh tokens, or service-account keys in the repository.
