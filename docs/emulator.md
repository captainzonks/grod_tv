# Android TV Emulator Setup

This document captures the non-obvious flags, paths, and one-off fixes required to run `grod_tv` on a desktop Android TV emulator. Everything here was discovered during Phases 0–4 on the development machine (Arch Linux, Hyprland/Wayland, AMD Ryzen 7 5700U / Lucienne iGPU, PipeWire audio).

If you only target hardware, skip this doc — the emulator is convenient for fast iteration but not required.

---

## AVD creation

```bash
ANDROID_AVD_HOME=$HOME/.config/.android/avd \
avdmanager create avd \
    -n grod_tv_api36 \
    -k 'system-images;android-36;android-tv;x86_64' \
    -d 'tv_1080p'
```

The `tv_1080p` device profile is shipped with the SDK and matches a 1920×1080 Android TV. Use `avdmanager list device` to see alternatives (e.g. `tv_4k`).

### `ANDROID_AVD_HOME`

Our environment exports `_JAVA_OPTIONS=-Djava.util.prefs.userRoot=/home/barhamm/.config/java` (an XDG-compliance fix). This redirects Java's `Preferences` root, and **as a side effect** the Android SDK's `avdmanager` creates AVDs under `~/.config/.android/avd/` instead of `~/.android/avd/`.

The `emulator` binary still **looks for AVDs under `~/.android/avd/`** unless told otherwise. Without the override it cannot find the AVD you just created.

**Fix:** export `ANDROID_AVD_HOME` to point at the actual path:

```bash
export ANDROID_AVD_HOME=$HOME/.config/.android/avd
```

Put this in `~/.zshrc` (or wherever you keep persistent env). Verify:

```bash
ls $ANDROID_AVD_HOME            # should show grod_tv_api36.avd + grod_tv_api36.ini
```

---

## Launching the emulator

```bash
ANDROID_AVD_HOME=$HOME/.config/.android/avd \
/opt/android-sdk/emulator/emulator \
    -avd grod_tv_api36 \
    -accel on \
    -gpu auto \
    -audio pulse \
    -dns-server 8.8.8.8,1.1.1.1 \
    -no-snapshot-save \
    -no-boot-anim
```

Each flag is load-bearing:

| Flag                                | Why                                                                                          |
| ----------------------------------- | -------------------------------------------------------------------------------------------- |
| **Absolute path** `/opt/.../emulator` | `$PATH` may contain the deprecated `/opt/android-sdk/tools/emulator` first; that one fails with "Qt library not found" against modern SDKs |
| `-accel on`                         | Newer emulators rejected the `-accel kvm` form we used to pass. `on` is the current syntax  |
| `-gpu auto`                         | Lets the emulator pick host GL; on Lucienne the VAAPI iGPU is selected                       |
| `-audio pulse`                      | The TV image is silent on the default backend; pulse routes into PipeWire's pulse shim       |
| `-dns-server 8.8.8.8,1.1.1.1`       | **Critical.** The API 36 TV image boots with empty `net.dns1`; without this, every Android `URL.openConnection` fails with `UnknownHostException` even though `ping` by IP works |
| `-no-snapshot-save`                 | Snapshot saves on shutdown corrupt the qemu state if the host suspends mid-save              |
| `-no-boot-anim`                     | Pure performance — saves ~20s on cold boot                                                   |

### KVM

`/dev/kvm` should be world-writable (`0666`) and not gated behind the `kvm` group on Arch. Verify:

```bash
ls -l /dev/kvm
# crw-rw-rw-+ 1 root kvm 10, 232 May 21 18:00 /dev/kvm
```

If it's gated behind a group, either add yourself to `kvm` and re-login, or write a udev rule (`/etc/udev/rules.d/65-kvm.rules` → `KERNEL=="kvm", GROUP="kvm", MODE="0666"`).

---

## Audio routing (PipeWire + PulseAudio)

The emulator pipes audio into a PulseAudio sink named `qemu-system-x86_64`. PipeWire's pulse-server shim handles this transparently, but **stream-restore** can save a previous session's volume at zero:

```bash
$ pactl list sink-inputs | grep -A 20 qemu
Sink Input #218
    Driver: protocol-native.c
    Owner Module: 13
    Client: 87
    Sink: 53
    Sample Specification: s16le 2ch 44100Hz
    ...
    Volume: front-left:        0 / 0% / -inf dB
            front-right:       0 / 0% / -inf dB
```

`-inf dB` means muted. Fix:

```bash
pactl set-sink-input-volume <id> 100%
```

Where `<id>` is the `Sink Input #N` number from the listing.

The **guest** also boots with `STREAM_MUSIC` at 3/15. Bump it via ADB:

```bash
for i in {1..10}; do adb shell input keyevent KEYCODE_VOLUME_UP; done
```

The system volume bar will appear and the level should climb to max.

---

## DNS resolution gotcha

On the API 36 Android TV image:

```
$ adb shell getprop net.dns1
                    (empty)
$ adb shell ping -c 1 1.1.1.1
1 packets transmitted, 1 received, 0% packet loss
$ adb shell nslookup google.com
;; connection timed out; no servers could be reached
```

IP routing works, DNS does not. This is fixed at emulator launch with `-dns-server` (above) — there is **no way to patch it after boot** that I have found; setprop is read-only on user builds, and there is no /etc/resolv.conf in the standard sense.

Symptom from inside the app: every Piped call fails with `java.net.UnknownHostException: Unable to resolve host`.

---

## Player HTTP stack — must use `OkHttpDataSource`

Once DNS works, the next emulator-specific failure mode is:

```
androidx.media3.datasource.HttpDataSource$HttpDataSourceException:
    java.net.ProtocolException: unexpected end of stream
        at FragmentedMp4Extractor.sniff(...)
```

This happens against Cloudflare-fronted googlevideo URLs (everything Piped serves) when ExoPlayer uses `DefaultHttpDataSource`. The same URLs return HTTP 206 fine from `curl` on the host.

**Fix is in code, not in the emulator:** use `OkHttpDataSource.Factory(OkHttpClient())` everywhere. See [`architecture.md`](architecture.md#1-okhttpdatasource-not-defaulthttpdatasource).

---

## mDNS unverifiable on emulator

QEMU's NAT mode does not pass LAN multicast through to the host network. You cannot test `_grod._tcp.local.` discovery from the host with `dns-sd -B`, `avahi-browse -a`, or any other tool. Two options:

1. **Two emulators on the same bridged network.** Possible but fiddly.
2. **Test on real hardware.** Recommended.

The mDNS registration succeeds on the emulator (you can see the `NsdManager.onServiceRegistered` callback fire); it just can't be discovered from outside the emulator's NAT.

---

## ADB port forwarding for HTTP testing

The emulator's `10.0.2.15` is unreachable from the host. To hit the Ktor server from `curl`:

```bash
adb forward tcp:7878 tcp:7878
curl http://127.0.0.1:7878/status
```

The forward dies when the emulator restarts; re-run after every cold boot.

---

## Common emulator pitfalls

### "Failed to start Emulator console for 5554"

Warning, not error. The `gradle connectedAndroidTest` flow doesn't need the console; ignore.

### Emulator window flickers on Hyprland

Wayland scaling + GTK X11 fallback. Add to `~/.config/hypr/conf/env.conf`:

```
env = QT_QPA_PLATFORM,xcb
env = _JAVA_AWT_WM_NONREPARENTING,1
```

### "Snapshot was created in an unsupported version" on cold boot

Wipe the AVD's `snapshots/` directory:

```bash
rm -rf ~/.config/.android/avd/grod_tv_api36.avd/snapshots
```

### `am start` returns instantly but no UI appears

The emulator hasn't finished booting. Poll until `getprop sys.boot_completed` returns `1`:

```bash
while [[ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]]; do sleep 1; done
```

---

## Tear-down

```bash
adb -s emulator-5554 emu kill
```

Or close the emulator window. There is no persistent state to clean up beyond the AVD directory itself.

---

## References

- Emulator command-line reference: https://developer.android.com/studio/run/emulator-commandline
- AVD configuration: https://developer.android.com/studio/run/managing-avds
- KVM acceleration on Linux: https://developer.android.com/studio/run/emulator-acceleration#vm-linux
