# Welle

DAB/DAB+ and web radio for Android car head units, built for an ADAYO AC822X
(Android 4.2.2, API 17, 1920 x 720) with a USB DAB dongle, and running on Android 4.1
through 16.

Plain Java, platform Views and Canvas. No AndroidX, no Play Services, no account, no web
runtime. DAB works offline.

**Status in one line:** web radio, UI, persistence and everything around them are built and
were exercised on Android 4.1 and Android 16 emulators. DAB runs on IRT's open-source driver
for exactly this dongle and was reported working on the head unit by its owner. MP3 web
streams failed there for lack of a usable decoder; a fix is in, **not yet confirmed on
the radio**. See [docs/REPORT.md](docs/REPORT.md) for the full matrix and the
steps to verify it on the radio.

## Build

Requirements, all pinned in the project:

| Tool | Version | Why |
|---|---|---|
| JDK | 21 (the one bundled with Android Studio works) | Gradle daemon |
| Gradle | 9.5.0 (wrapper) | |
| Android Gradle Plugin | 9.3.3 | |
| Android SDK platform | 36.1, build-tools 36.0.0 | compileSdk / targetSdk 36 |
| NDK | **23.2.8568313** | last NDK that can target API 16; newer ones start at 21 |
| CMake | 3.22.1 | |

```bash
./gradlew assembleDebug assembleRelease
```

```bash
./gradlew lintDebug testDebugUnitTest
```

Outputs:

- `app/build/outputs/apk/debug/app-debug.apk` (debug key, native diagnostics in logcat)
- `app/build/outputs/apk/release/app-release.apk`

Both are signed with v1 (JAR) and v2. Android 4.x only understands v1.

The release build is signed with the key described in `keystore.properties` (not in git):
the path of the key file, its alias and the two passwords. **Back up the key file and the
passwords**, because updates must be signed with the same key. Without
`keystore.properties`, release builds fall back to the debug key. A debug and a release
build cannot replace each other; uninstall first.

## Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the APK to a USB stick and open it with the head unit's file manager
("unknown sources" must be allowed).

## USB tuner

Supported receiver: the dongle that reports USB id `16c0:05dc`, product string
"DAB USB Dongle" (a Raontech demodulator behind a USB bridge). Other dongles are not
supported.

1. Plug the dongle in and start Welle. Android asks whether Welle may use the device.
2. Run a station scan (round-arrow icon).

To avoid the permission question after every ignition cycle, turn on
*Settings > General > Start when the USB tuner is detected*, replug the dongle and tick
"use by default" in the system dialog. That makes the permission permanent.

The id `16c0:05dc` is shared by many hobbyist devices. Welle therefore checks the
interface layout and the tuner's power-up handshake, and shows "Adapter not supported" for
anything else.

## Web radio

- Search: any Radio Browser server (`https://de1.api.radio-browser.info` by default), with
  country, genre and name.
- Playlists: `.m3u`, `.m3u8`, `.pls` from a file or a URL. Relative entries are resolved
  against the playlist URL.
- Streams: MP3 and AAC / HE-AAC (ADTS) over HTTP or HTTPS, Icecast/Shoutcast titles, and
  HLS with MPEG-TS or packed-audio segments.
- Not supported, and reported as such: Ogg/Vorbis/Opus, FLAC, HLS with fMP4 segments,
  encrypted HLS.

## Behaviour worth knowing

- **Pause on DAB** stops decoding. There is no time-shift; play resumes live.
- **Back**: with "Close with back" off (default) the app goes to the background and keeps
  playing; with it on, back stops the radio and closes the app. The button at the right of
  the menu bar does one of the two, as chosen under "Minimise button".
- **System bars**: "Keep notch clear" (default) hides both bars and keeps the content off the
  display cutout; "Fullscreen" also uses the cutout area. Android 4.1-4.3 can only hide the
  status bar for good, so there only "Normal" looks different.
- **Audio focus**
  - Navigation prompt: plays at "Volume during navigation prompts"; silent instead if
    "Mute on audio focus loss" is on.
  - Phone call: silent. The level only returns when Android hands focus back.
  - Another player takes over for good: the app exits if "Exit on audio focus loss" is on
    (default); otherwise it keeps running silently if "Mute on audio focus loss" is on;
    otherwise it pauses.
- **Scan modes**: the saved list is only replaced when a scan reaches the last channel.
  Mode 1 keeps stations that sit on a preset even if they were not found (marked "not in
  last scan"). Mode 2 replaces everything and clears the presets of vanished DAB stations.
- **Service following** switches to another ensemble that the scan found the same service
  on, after 6 s without reception and at most every 20 s.
- **Media keys** (next, previous, play, pause) work through the system's media-button
  mechanism while the radio plays, and directly when they reach a Welle screen as key
  events. While the radio is on, Welle also obeys "next" and "previous" sent as
  `com.android.music.musicservicecommand` broadcasts, the way the stock Android 4 music
  player did. A head unit that reports its steering-wheel keys in some other private way
  needs its own mapping.
- **Dashboards** can show what plays on Android 4.x too: Welle sends its state as the sticky
  broadcast `me.ri3d.welle.STATE` (source, station, DLS/stream title, playing, position in the
  station list that next/previous step through, preset) and serves the picture it shows
  (slideshow if enabled, else the logo) read-only at `content://me.ri3d.welle.art/...`, named in
  the broadcast's `art` extra. OpenDashboard uses both.
- **Presets** are one bank of 60 slots for DAB and web stations alike. Changing "presets per
  page" or "preset pages" only changes what is shown. With 4 per page they are one row of
  big buttons.
- **Theme "Auto (GPS)"** uses the last position the device already knows; it never turns
  the GPS on. Without permission or position it assumes 50° N on the time-zone meridian.
- **Theme "Auto (system)"** follows the system dark mode on Android 10+. Older versions have
  none, so it falls back to the sunset calculation there.

## File formats

**Logo pack** (`welle-logos.zip`, exported to `Documents/Welle/`): PNG files plus

```json
{"format":"welle-logo-pack","version":1,
 "logos":[{"id":"dab:d220","name":"radioeins","file":"dab_d220.png","manual":false}]}
```

`id` is the station identity (`dab:` + service id in hex, or `web:` + URL hash).

**Scene** (Settings > Slideshow & scene > Import scene): a JSON file, or simply a PNG/JPEG.

```json
{"welleScene":1, "background":"#101418", "image":"<base64 PNG or JPEG>",
 "imageOpacity":0.6, "visualizationColor":"#4CD6E8"}
```

## Permissions

| Permission | Used for |
|---|---|
| INTERNET, ACCESS_NETWORK_STATE | web radio, station search, RadioDNS logos |
| FOREGROUND_SERVICE(_MEDIA_PLAYBACK), POST_NOTIFICATIONS, WAKE_LOCK | playback in the background |
| ACCESS_COARSE_LOCATION | sunset for "Auto (GPS)" and dimming; optional |
| SYSTEM_ALERT_WINDOW | DLS overlay; optional |
| WRITE/READ_EXTERNAL_STORAGE (old Android only) | logo pack export, built-in file browser |

USB host is declared optional, so the app installs on devices without it.

## Troubleshooting

| Symptom | Check |
|---|---|
| "No adapter found" | Dongle plugged into a host-capable port? `adb shell dumpsys usb` should list `16c0:05dc`. Tap "Search again". |
| "Adapter not supported" | The text in brackets says why (endpoints or handshake). Send the `WelleUsb` logcat line. |
| Scan finds nothing | Antenna connected and near glass or outside; not in a garage. Debug build: `adb logcat -s std` shows lock state per channel. |
| Station tunes but stays silent | `adb logcat -s std OMXCodec ACodec SoftAAC2`. See "open risks" in the report. |
| Web stream fails | The reason is shown under the station name. For decoder trouble: `adb logcat -s WelleAudio`. |
| Does not start by itself, or keys do nothing | Debug build: `adb shell cat /data/data/me.ri3d.welle/files/events.log` lists starts, USB events, audio focus changes and the key codes that reached the app. |

Debug builds route the tuner library's diagnostics to logcat under the tag `std`.

## Layout check without a tuner (debug builds only)

```bash
adb shell am broadcast -n me.ri3d.welle/.debug.DemoReceiver -a me.ri3d.welle.DEMO -e mode player
```

Shows made-up stations and a made-up "tuner ready" state, labelled "Demo", with no audio.
`-e mode clear` removes them. `-e mode fakeusb` (Android 4.x only) runs the tuner
library's attach, permission and handshake code against a made-up dongle and logs the
result under `WelleDemo`; the handshake must fail. This code is not in the release APK.

## Licences

Welle's native DAB library is IRT's `libirtdab` (LGPL-2.1), built as a separate shared
library from the sources in `app/src/main/cpp/irtdab`; local changes are listed in
`WELLE-PATCHES.md` there. Fonts are under the SIL OFL. Conscrypt is Apache-2.0. Full texts:
`licenses/` and in the app under Settings > About.
