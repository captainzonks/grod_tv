# Building grod_tv from source

This document covers the toolchain setup required to build, install, and test `grod_tv` on a desktop machine, plus the relevant CI prerequisites.

---

## Toolchain

| Tool                 | Required version       | Notes                                                                   |
| -------------------- | ---------------------- | ----------------------------------------------------------------------- |
| JDK                  | 17 (LTS)               | Set as `archlinux-java` default on Arch, or via `JAVA_HOME`             |
| Android SDK platform | API 35                 | `compileSdk = 35`, `targetSdk = 35`                                     |
| Android NDK          | **not required**       | We do not ship native code; AGP's `stripDebugDebugSymbols` warning on `libandroidx.graphics.path.so` is benign |
| Gradle               | 8.13                   | Bundled via the wrapper (`./gradlew`)                                   |
| AGP                  | 8.10.0                 | Declared in `libs.versions.toml`                                        |
| Kotlin               | 2.0.21                 | Via the Kotlin plugin alias                                             |
| KSP                  | `2.0.21-1.0.28`        | For Room codegen                                                        |
| adb / emulator       | latest                 | For running on a device or emulator                                     |

### Arch Linux setup

```bash
sudo pacman -S jdk17-openjdk android-tools
# Android SDK from AUR (or download Command Line Tools manually):
yay -S android-sdk android-sdk-platform-tools android-sdk-build-tools
sudo archlinux-java set java-17-openjdk
echo 'export ANDROID_HOME=/opt/android-sdk' >> ~/.zshrc
echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin' >> ~/.zshrc
```

Accept SDK licenses once:

```bash
yes | sdkmanager --licenses
```

Install the platforms/build-tools/system-image needed:

```bash
sdkmanager \
    "platforms;android-35" \
    "build-tools;35.0.0" \
    "platform-tools" \
    "emulator" \
    "system-images;android-36;android-tv;x86_64"
```

API 36 is for the **emulator**; the app targets API 35 (compileSdk/targetSdk).

---

## Gradle properties

The committed `gradle.properties` enables AGP's configuration cache and parallel/cache features:

```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
android.useAndroidX=true
```

`_JAVA_OPTIONS=-Djava.util.prefs.userRoot=/home/barhamm/.config/java` is set in the user environment to redirect Java's prefs from `~/.java/.userPrefs/` (a violation of `XDG_CONFIG_HOME`). This is unrelated to the build itself but it affects the **AVD path** — see [`emulator.md`](emulator.md).

---

## Building

### Debug APK

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

First build: ~1–2 minutes cold, ~10 seconds incremental thanks to the configuration cache.

### Release APK (unsigned)

```bash
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk
```

We do not commit signing keys. For sideload/F-Droid distribution, sign with `apksigner` against your own keystore.

### Lint / static checks

```bash
./gradlew :app:lintDebug
```

---

## Installing on a device

### Real Android TV (Shield, Chromecast w/ Google TV, Fire TV)

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.captainzonks.grodtv/.MainActivity
```

Enable ADB over network in the TV's developer settings first. On Fire TV the procedure is documented [in Amazon's developer guide](https://developer.amazon.com/docs/fire-tv/connecting-adb-to-device.html).

### Emulator

See [`emulator.md`](emulator.md) — there are non-obvious flags required for the API 36 TV image (`-dns-server`, `-audio pulse`, `ANDROID_AVD_HOME` redirect).

---

## Running tests

### JVM unit tests

```bash
./gradlew :app:testDebugUnitTest
```

Runs in milliseconds; no emulator needed. Covers `PipedClient` parsing + picker (15 cases against captured fixtures) and `AutoAdvancer` state machine (6 cases against functional fakes).

### Instrumentation tests

Requires a running adb device or emulator:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Covers `QueueDao` against `Room.inMemoryDatabaseBuilder` (8 cases). The instrumentation runner is `androidx.test.runner.AndroidJUnitRunner`.

---

## Continuous integration

There is no committed CI configuration yet. A minimal GitHub Actions workflow would look like:

```yaml
name: ci
on: [push, pull_request]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: 'temurin', java-version: '17' }
      - uses: android-actions/setup-android@v3
      - run: ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Instrumentation tests require an emulator and `reactivecircus/android-emulator-runner@v2` or similar; defer that until we hit a real DAO regression.

---

## Troubleshooting

### "Qt library not found" on `emulator` launch

You are invoking `/opt/android-sdk/tools/emulator` (the deprecated binary), not `/opt/android-sdk/emulator/emulator`. Force the right path:

```bash
/opt/android-sdk/emulator/emulator -avd grod_tv_api36 -accel on -gpu auto -audio pulse -dns-server 8.8.8.8,1.1.1.1
```

### `unable to locate adb`

`platform-tools` not installed or not on `$PATH`. `sudo pacman -S android-tools` and re-export `$ANDROID_HOME/platform-tools`.

### KSP fails with `cannot find symbol androidx.room…`

You're missing the Room runtime + KSP plugin. Both are declared in `libs.versions.toml`; `./gradlew --refresh-dependencies` to re-resolve.

### Build hangs at `> Task :app:packageDebug`

Gradle wrote a corrupt APK file. `./gradlew clean :app:assembleDebug`.

### `ProtocolException: unexpected end of stream` at play time

You changed `OkHttpDataSource.Factory` back to `DefaultHttpDataSource.Factory`. **Revert.** See [`architecture.md`](architecture.md#1-okhttpdatasource-not-defaulthttpdatasource).
