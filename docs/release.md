# Release process

Tag-driven, signed APKs published to GitHub Releases via the
[`release` workflow](../.github/workflows/release.yml).

---

## TL;DR

```bash
# Bump versionName + versionCode in app/build.gradle.kts, open a PR.
# After PR merges to main:

git checkout main
git pull --ff-only
git tag -a v0.1.0 -m "v0.1.0"
git push origin v0.1.0
```

CI builds, signs, and attaches the APK + SHA-256 to a **draft** GitHub release. Edit the auto-generated release notes if needed, then publish.

---

## What the workflow does

[`.github/workflows/release.yml`](../.github/workflows/release.yml) triggers on any tag matching `v*`. On a runner with JDK 17 + Android SDK 35:

1. **Decodes the release keystore** from the `RELEASE_KEYSTORE_BASE64` secret to `$RUNNER_TEMP/keystore/release.keystore`.
2. **Sets four env vars** consumed by `app/build.gradle.kts` (see *Signing config* below):
   - `RELEASE_KEYSTORE_PATH` → the decoded path
   - `RELEASE_KEYSTORE_PASSWORD` → from secret
   - `RELEASE_KEY_ALIAS` → from secret
   - `RELEASE_KEY_PASSWORD` → from secret
3. Runs `./gradlew :app:assembleRelease`.
4. Verifies the signature with `apksigner verify --verbose --print-certs`.
5. Computes `sha256sum` of the APK.
6. Renames the APK to `grod_tv-<tag>.apk` and uploads it (plus `.sha256` sidecar) to a **draft** GitHub release with auto-generated notes.

---

## Signing config

[`app/build.gradle.kts`](../app/build.gradle.kts) reads the four `RELEASE_*` env vars at configure time. When any of them is missing or blank (the default for local builds), `signingConfig` is **not** set on the `release` build type — `assembleRelease` then produces an unsigned APK. CI is the only environment where all four are populated, so production signing is impossible to run by accident.

```kotlin
val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
// ... three more

val hasReleaseSigning = listOf(/* all four */).all { !it.isNullOrBlank() }

if (hasReleaseSigning) {
    signingConfigs {
        create("release") {
            storeFile = file(releaseKeystorePath!!)
            storePassword = releaseKeystorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }
}

buildTypes {
    release {
        isMinifyEnabled = false
        if (hasReleaseSigning) {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

---

## Keystore details

The current production signing key:

| Field             | Value                                                                                          |
| ----------------- | ---------------------------------------------------------------------------------------------- |
| Algorithm         | RSA 4096                                                                                       |
| Signature alg     | SHA384withRSA                                                                                  |
| Validity          | 25 years (9 125 days)                                                                          |
| Alias             | `grod_tv`                                                                                      |
| Distinguished Name| `CN=captainzonks, OU=grod_tv, O=personal, L=Unknown, ST=Unknown, C=US`                         |
| Container         | PKCS12 (`.keystore`) — same password for store + key (PKCS12 limitation)                       |

The keystore file lives **off the repo** at `~/Documents/grod_tv-keystore/release.keystore` on the developer machine, and as a Bitwarden secure-note attachment for disaster recovery. **Never** commit the keystore to git; ggshield's pre-commit + pre-push hooks will block it.

The SHA-256 certificate fingerprint is recorded in the secure-note. F-Droid and Google Play attach app identity to this fingerprint — losing the keystore means losing the ability to publish updates under the same `applicationId`.

---

## GitHub secrets

The release workflow reads four secrets from `https://github.com/captainzonks/grod_tv/settings/secrets/actions`:

| Secret name                  | What it holds                                              |
| ---------------------------- | ---------------------------------------------------------- |
| `RELEASE_KEYSTORE_BASE64`    | `base64 -w 0 release.keystore` of the PKCS12 file          |
| `RELEASE_KEYSTORE_PASSWORD`  | The keystore password set at `keytool -genkeypair` time    |
| `RELEASE_KEY_ALIAS`          | `grod_tv`                                                  |
| `RELEASE_KEY_PASSWORD`       | Same as the keystore password (PKCS12 enforces equality)   |

To rotate any of them via `gh`:

```bash
gh secret set RELEASE_KEYSTORE_PASSWORD --body "<new-value>" --repo captainzonks/grod_tv
```

---

## Versioning

`versionName` follows semver (`MAJOR.MINOR.PATCH`); the matching git tag is `v<versionName>`. `versionCode` is a monotonically increasing integer — bump by 1 per release. Both fields live in `app/build.gradle.kts`:

```kotlin
defaultConfig {
    // …
    versionCode = 2
    versionName = "0.1.0"
}
```

The workflow does **not** parse the tag — it relies on what was committed when the tag was created. So:

1. Bump `versionCode` + `versionName` on a feature branch.
2. PR + merge.
3. Tag the merged commit.

This makes the tag immutable evidence of the released code, with no clever sed-on-CI to debug later.

---

## Disaster recovery

If you lose the keystore:

1. You cannot publish updates to the existing `applicationId` on F-Droid/Play under the same identity. New users would have to uninstall + reinstall a freshly-signed APK.
2. Generate a new keystore (see [`build.md`](build.md) for the `keytool -genkeypair` invocation, mirrored from the keystore-generation procedure).
3. Update all four GitHub secrets.
4. Bump `applicationId` to something like `com.captainzonks.grodtv2` for sideload-only release, **or** accept the install break and continue with the new key.

Mitigation: keep the keystore in **two** offline locations (Bitwarden attachment + an encrypted external drive) and verify both copies remain readable yearly.

---

## Local dry-run

You can dry-run the signing step locally without leaking the production key by generating a throwaway debug keystore:

```bash
keytool -genkeypair -v \
  -keystore /tmp/test.keystore \
  -alias test -keyalg RSA -keysize 2048 -validity 30 \
  -storepass testpass -keypass testpass \
  -dname "CN=test, OU=test, O=test, L=test, ST=test, C=US"

RELEASE_KEYSTORE_PATH=/tmp/test.keystore \
RELEASE_KEYSTORE_PASSWORD=testpass \
RELEASE_KEY_ALIAS=test \
RELEASE_KEY_PASSWORD=testpass \
./gradlew :app:assembleRelease

# Verify
"$ANDROID_HOME"/build-tools/35.0.0/apksigner verify --verbose \
    app/build/outputs/apk/release/app-release.apk

rm /tmp/test.keystore
```

This proves the signing path is wired correctly without exposing the production keystore.
