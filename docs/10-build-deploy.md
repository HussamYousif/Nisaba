# 10 — Build & Deploy (getting the APK onto the Boox)

Status: **Implemented (M6, 2026-09-01)** · Debug builds iterate on the Boox; the release APK is signed with the private keystore and minified.

## 1. Prerequisites (dev machine)

- JDK 17+ (`brew install --cask temurin@17` or Android Studio's JBR)
- Android SDK Platform 35 + Build-Tools 35 (`ANDROID_HOME` set)
- Android Studio (optional; Gradle CLI suffices)
- The Boox Note Air 5c on the same Wi-Fi, USB debugging or wireless ADB enabled

## 2. Project scaffolding (M1)

```
nisaba/
├── settings.gradle.kts          # pluginManagement, dependencyResolutionManagement, include(":app")
├── build.gradle.kts             # root: AGP 8.x + Kotlin 2.x plugins (apply false)
├── gradle/libs.versions.toml    # version catalog (single source of versions)
├── gradle.properties            # org.gradle.jvmargs=-Xmx2g, android.useAndroidX=true
├── local.properties             # sdk.dir (gitignored)
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/nisaba/…    (03 §2)
│       ├── res/…                (themes, drawables — parchment world, 09 §1)
│       └── assets/fonts/        (DancingScript-Medium.ttf, DancingScript-Bold.ttf,
│                                 GreatVibes-Regular.ttf, Kalam-Regular.ttf)
├── keystore/                    (gitignored)
├── .gitignore
└── docs/
```

**`app/build.gradle.kts` essentials (as built):**

```kotlin
android {
    namespace = "com.nisaba"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.nisaba"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        create("release") {
            // keystore.properties-driven (below); debug uses default debug key
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

**Manifest highlights:** `INTERNET` permission only; `android:usesCleartextTraffic="false"`; single activity `MainActivity` (portrait/landscape per M1 device decision); `FileProvider` for PNG export (F8.1); no backup rules that would exclude the notebooks dir from Google backup (or `android:allowBackup="false"` — decide: notebooks are personal; **v1: allowBackup=false**, export is the backup story).

## 3. Signing

```
# one-time (keystore/ is gitignored):
keytool -genkeypair -v -keystore keystore/nisaba.jks -alias nisaba \
  -keyalg RSA -keysize 2048 -validity 10000 -storetype PKCS12

# keystore.properties (gitignored) consumed by build.gradle.kts:
storeFile=../keystore/nisaba.jks
storePassword=…
keyAlias=nisaba
keyPassword=…
```

## 4. Build commands

```bash
./gradlew assembleDebug          # quick iteration APK: app/build/outputs/apk/debug/
./gradlew assembleRelease        # signed release APK
./gradlew lint                   # before every milestone close
```

## 5. Installing on the Boox Note Air 5c

**Option A — adb over Wi-Fi (fastest for iteration):**
1. On the Boox: Settings → About → tap build number 7× (dev mode) → enable **USB debugging** (and **Wireless debugging** if Android 11+).
2. Find the tablet IP (`Settings → Wi-Fi → network details`).
3. `adb pair <ip>:<port>` (Android 11+ pairing code), then `adb connect <ip>:<port>`; verify `adb devices`.
4. `adb install -r app/build/outputs/apk/debug/app-debug.apk`

**Option B — file transfer (no cable/ADB):**
1. Copy the APK to the tablet (Send to Boox, Google Drive, or a USB cable).
2. Open the APK in the file manager → allow "install unknown apps" for it → install.

**Boox quirk — disabled after install:** Boox firmware marks freshly installed/sideloaded packages `DISABLED_USER` (`enabled=3`); the activity then "does not exist". After every `adb install` (including test-run reinstalls) run `adb shell pm enable com.nisaba` (or open the app once from the launcher). Also enable USB debugging + "USB debugging (Security settings)" in Developer Options.

**Boox OS settings for Nisaba** (critical for feel, 04 §2):
- **App Optimization** (Boox settings → Apps → Nisaba): refresh mode **Fast** (critical — the Android 15 firmware exposes no EPD API to apps, so this per-app setting is the only way to get responsive writing; see docs/04 §2). Enable "Disable animation" if offered.
- **Battery optimization**: exclude Nisaba (prevents background kills mid-summon).
- **Doze**: keep default; our save-on-stop covers it.
- If the Boox system offers per-app "Refresh mode" presets (A2/Regal), test writing comfort in each; document the winner in this doc after M1.

## 6. Debugging on device

- `adb logcat | grep -i nisaba` — we tag every subsystem (`NisabaInk`, `NisabaLLM`, `NisabaStore`, `NisabaMagic`).
- Screenshots: `adb exec-out screencap -p > shot.png` (works on e-ink; captures what the user sees).
- **Reading the screen**: e-ink photos via the tablet's camera-phone are the true test of legibility/ghosting (04 §8 checklist); do this in M4 font pass.
- `adb shell dumpsys gfxinfo com.nisaba` for frame stats of the ink view (target: jank-free by construction — we draw incrementally).
- Memory: `adb shell dumpsys meminfo com.nisaba` (N6: ≤ 256 MB).

## 7. Release checklist

- [x] `lint` clean (or all warnings triaged) — gate with baseline since P2-3
- [x] `assembleRelease` with the keystore; signing verified (`apksigner verify` → CN=Nisaba)
- [x] Release is key-clean: `strings classes*.dex | grep sk-` finds nothing (debug embeds the .env key — see README "Development secrets")
- [x] Minification on (R8); keep-rules cover kotlinx.serialization + PdfBox; release smoke-tested on the emulator (create → list → open → render)
- [ ] Full 04 §8 device checklist pass
- [ ] Fresh-install test (no data) + upgrade test (data preserved)
- [ ] Version bump in `libs.versions.toml`/`versionName` (first release: 1 / 0.1.0)

## 8. CI (v2, optional)

GitHub Actions: `actions/setup-java@v4` + `gradle/actions/setup-gradle` → `assembleDebug` on every push, `assembleRelease` on tag, keystore from repository secrets. Not needed for a solo local build; add when the project grows.
