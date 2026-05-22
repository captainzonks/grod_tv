# fastlane metadata

[F-Droid](https://f-droid.org/) and several other Android stores read app
metadata from this directory layout. It is the standard [Triple-T fastlane
plugin](https://github.com/Triple-T/gradle-play-publisher) format.

```
fastlane/metadata/android/en-US/
├── title.txt              # ≤30 chars
├── short_description.txt  # ≤80 chars
├── full_description.txt   # ≤4000 chars (Markdown not rendered on all stores)
├── changelogs/
│   └── <versionCode>.txt  # one file per release, named after versionCode
└── images/
    ├── icon.png           # 512×512 PNG, app icon
    ├── tvBanner.png       # 1280×720 PNG, **required** for Android TV apps
    ├── featureGraphic.png # 1024×500 PNG (Play Store hint, harmless on F-Droid)
    └── phoneScreenshots/  # 1080×1920 or similar, optional
        └── 1.png
```

## What's here today

| File                          | Status                                |
| ----------------------------- | ------------------------------------- |
| `title.txt`                   | ✅ committed                          |
| `short_description.txt`       | ✅ committed                          |
| `full_description.txt`        | ✅ committed                          |
| `changelogs/2.txt`            | ✅ committed (matches versionCode 2 = v0.1.0) |
| `images/icon.png`             | ❌ TODO — needs a 512×512 source       |
| `images/tvBanner.png`         | ❌ TODO — **F-Droid blocker for TV apps** |
| `images/phoneScreenshots/`    | ❌ optional, deferred                  |

The text content is enough to validate the metadata structure. Submitting to
F-Droid still requires the banner image at minimum.

## Updating per release

When you bump `versionCode` in `app/build.gradle.kts`:

1. Add a new `changelogs/<newVersionCode>.txt` with the user-visible changes.
2. If feature work changed the elevator pitch, update `full_description.txt`.
3. Leave `title.txt` and `short_description.txt` alone unless you are rebranding.

## F-Droid metadata YAML

Once banner + icon land, an `metadata/com.captainzonks.grodtv.yml` PR against
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) will look like:

```yaml
Categories:
  - Multimedia
License: MIT
WebSite: https://github.com/captainzonks/grod_tv
SourceCode: https://github.com/captainzonks/grod_tv
IssueTracker: https://github.com/captainzonks/grod_tv/issues

AutoName: grod_tv
Description: |-
  (mirrors full_description.txt)

RepoType: git
Repo: https://github.com/captainzonks/grod_tv

Builds:
  - versionName: 0.1.0
    versionCode: 2
    commit: v0.1.0
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.1.0
CurrentVersionCode: 2
```

F-Droid will reproduce the build from source on their own infrastructure
using their own signing key — the production keystore documented in
[`../docs/release.md`](../docs/release.md) signs the GitHub-release APK,
not the F-Droid APK. Two separate signing identities is normal for F-Droid
projects.

## Where the assets should live

If/when the banner + icon are designed, drop them at:

```
fastlane/metadata/android/en-US/images/icon.png        # 512×512
fastlane/metadata/android/en-US/images/tvBanner.png    # 1280×720
```

That matches the layout F-Droid's metadata grabber expects, and Triple-T's
fastlane plugin can sync them to Play Store if a Play listing ever happens.
