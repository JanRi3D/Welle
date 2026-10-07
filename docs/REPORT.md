# Welle: hardware report, coverage and verification

Date of this report: 2026-10-04. App version 1.0.0.

Legend used throughout:

- **Verified**: ran and was observed working, on the platform named.
- **Tested logic**: covered by unit tests on the build machine.
- **Built, not exercised**: implemented and compiles/lints clean, but was not run in any test.
- **Needs the radio**: implemented; can only be confirmed with the dongle on the head unit.

DAB status: on 2026-10-04 the owner reported that DAB works on the head unit with the
fixed build ("everything works now except web radio"). The only evidence seen here is a
log fragment with live FIC data (ensemble date and time decoded once per second). The
acceptance steps in section 8 were not reported one by one, so they stay open individually.
See section 6 for both runs.

---

## 1. Hardware report

### Read from `20261003-143028.zip`

| Item | Source file | Value |
|---|---|---|
| Device | getprop.txt | ADAYO AC822X, board ac8317, Autochips ac83xx, eng build 2023-10-09 |
| Android | getprop.txt | 4.2.2, `ro.build.version.sdk=17` |
| CPU | cpuinfo.txt, cpufreq.txt | 4 cores, ARMv7 part 0xc07 (Cortex-A7), NEON/VFPv4; 1001000 kHz at capture time |
| ABI | getprop.txt | armeabi-v7a, secondary armeabi |
| Memory | meminfo.txt | MemTotal 828744 kB, MemFree 19276 kB, Cached 264444 kB, no swap |
| Dalvik | getprop.txt | heapgrowthlimit 256m, heapsize 512m (heap settings, not a budget) |
| Display | dumpsys-windows, surfaceflinger | 1920 x 720, 240 dpi, 60 Hz; OEM status bar 88 px; second display 720 x 480 (HDMI) |
| GPU | surfaceflinger | Mali-400 MP; SurfaceFlinger string says ES-CM 1.1, `ro.opengles.version` says 2.0 |
| Audio decoders | media_codecs.xml | `OMX.google.aac.decoder`, `OMX.google.mp3.decoder`, `OMX.mtk.audio.decoder.mpeg`, `OMX.mtk.audio.decoder.aac` |
| USB dongle | dumpsys-usb, usb-sysfs | 16c0:05dc, device class ff, one interface ff/ff/ff, strings "wshtyr@126.com" / "DAB USB Dongle" |
| Audio focus | getprop.txt | `sys.audiofocus.pkgname=com.adayo.service.sourcemanager`: an OEM source manager holds focus |
| Other software | top, windows | ES File Explorer installed (answers the file-picker intent on API 17) |
| Captured app | dumpsys-package, meminfo-app | me.ri3d.openauto 0.1.0, targetSdk 36, PSS about 11.7 MB, ran fullscreen at 1920 x 720 |

The `-3` files are empty captures and were treated as missing, not as evidence.

### Not in the ZIP (assumptions until seen on the device)

- ~~The dongle's endpoint descriptors.~~ Seen on the head unit on 2026-10-04:
  `16c0:05dc class ff | if0 ff/ff/ff ep81 t2 ep02 t2 ep82 t2`, so bulk OUT 0x02 and bulk
  IN 0x82 exist as the driver expects (plus a bulk IN 0x81 it does not use).
- Any tuner traffic, so nothing about firmware behaviour.
- Whether the firmware broadcasts `USB_DEVICE_ATTACHED` to running apps.
- How the OEM source manager uses audio focus (permanent vs transient loss).

### Consequences drawn

- API 17 rules out current AndroidX, Compose and Media3 (minSdk 21 or higher). Platform
  classes only.
- The GL version is ambiguous, so all drawing is Canvas.
- The captured app ran fullscreen at the full 1920 x 720, so Welle uses the fullscreen theme
  and lays out for 720 units of height.
- `OMX.google.aac.decoder` is present, which is the decoder the reference implementation
  uses for DAB+.

---

## 2. USB receiver: evidence and limits

| Question | Answer | Evidence |
|---|---|---|
| Is there a real driver for 16c0:05dc? | Yes | [hradio/omri-usb](https://github.com/hradio/omri-usb) commit `12f4b8e`: `native-lib.cpp` creates `RaonTunerInput` for `0x16C0/0x05DC`; `res/xml/device_filter.xml` lists 5824/1500 |
| Licence | LGPL-2.1-or-later (IRT GmbH), Reed-Solomon code LGPL-2.1 (KA9Q) | `app/src/main/cpp/irtdab/LICENSE`, `thirdparty/fec/LICENSE` |
| Transport | Bulk OUT 0x02, bulk IN 0x82; register read/write frames `21 00 00 02 reg val` / `22 00 01 00 reg` | `raontunerinput.cpp/.h` |
| What the dongle delivers | FIC blocks and the raw MSC sub-channel. Audio is the broadcast's compressed stream (HE-AAC or MPEG Layer II), **not PCM** | `readData()`, `dabplusservicecomponentdecoder.cpp` |
| Scan, service list, DLS, slideshow, signal | In the library: Band III scan, FIG parser, PAD/DLS, MOT slideshow, lock and error-rate based level | same sources |
| Proof for *this* unit | **None yet.** 16c0:05dc is a shared V-USB id. | The app checks the interface for bulk 0x02/0x82 and the power-up handshake (register 0x7D on the host page) and reports "Adapter not supported" otherwise |

The library is vendored in `app/src/main/cpp/irtdab` and built as `libirtdab.so`. Eight small
changes are listed in `WELLE-PATCHES.md` (scan resume index, failure callback, bounded
handshake, an out-of-bounds table read in upstream's "detune", one AAC access unit per
callback, EDI input removed).

---

## 3. Architecture

```
ui/*Activity  ── bind ──>  RadioService (owns playback, focus, scan, notification)
                              ├─ dab/DabTuner ── JNI ──> libirtdab.so ── USB host API ──> dongle
                              │     └─ dab/DabAudio (compressed audio ─> MediaCodec)
                              ├─ web/StreamPlayer (HTTP ─> frames ─> MediaCodec)
                              └─ audio/AudioEngine (DSP ─> AudioTrack)   <- single output
core/  Settings, Station, StationStore, FocusPolicy, UsbLink, ServiceFollower, SunClock, DabChannels
logos/ Dns, RadioDns, LogoStore, LogoJob
```

- USB transport, tuner protocol and DAB parsing: `DabTuner` + `libirtdab`.
- Audio processing: `audio/Dsp` on the decoded PCM of whichever source is active.
- Persistence: `StationStore` (one JSON file, written beside and renamed), `Settings`.
- Only one writer can be audible: sources take a session token from `AudioEngine`, and
  writes with an old token are dropped.

### Dependencies and toolchain

| Component | Version | Min API | Bytecode / ABI | How checked |
|---|---|---|---|---|
| Android Gradle Plugin | 9.3.3 | builds minSdk 16 | n/a | build succeeds |
| NDK | 23.2.8568313 | 16 | armeabi-v7a, arm64-v8a, x86, x86_64 | library loaded on API 16 emulator |
| libirtdab (vendored source) | omri-usb 12f4b8e | 16 (upstream minSdk 16) | C++14, static libc++ | `readelf`: ELF32 ARM, needs only liblog/libm/libdl/libc |
| Conscrypt | 2.5.3 | 9 (2.7.0 needs 21) | Java 7; 32-bit libraries packaged only | AAR manifest and `javap`; TLS handshake on API 16 |
| org.json (tests only) | 20240303 | n/a | n/a | not in the APK |
| JUnit (tests only) | 4.13.2 | n/a | n/a | not in the APK |

compileSdk and targetSdk are 36; minSdk is 16. Newer APIs are guarded by version checks
(notification channel, media session, foreground service type, receiver flags, PendingIntent
mutability, runtime permissions, overlay type, document picker, MediaStore, back callback,
letter spacing). Lint's `NewApi` check passes.

---

## 4. Feature coverage

### A. Player, stations, presets

| Feature | State |
|---|---|
| DAB+ / WEB RADIO source tabs, status indicator, clock, links | Verified (API 16, API 36) |
| Station name, DLS/stream title, type, bitrate in web mode | Verified with live web streams |
| Ensemble, DAB/DAB+, stereo/mono, signal bars, service-following status, channel, frequency | Layout verified with demo data; live values need the radio |
| Channel ruler with ensemble dots and needle | Layout verified with demo data at 1280 and 1920 wide, both themes |
| Previous / play-pause / next | Verified (web); DAB needs the radio |
| Slideshow image, logo fallback, monogram fallback | Logo and monogram verified; slideshow needs the radio |
| Preset grid: tap, hold to save, empty slots, paging by swipe or tap | Verified (API 36); replace/remove dialog built, not exercised (its dialog component is verified) |
| Station list: count, search, ensemble filter, current and preset marks, scan shortcut | Rendering verified; search/filter typing built, not exercised |
| Hold a station to assign a preset | Built, not exercised |
| No hard-coded stations | Lists come only from scans or the web manager; demo data exists only in debug builds |

### B. DAB scan

| Feature | State |
|---|---|
| Band III scan through the receiver, live channel, progress, found list | Needs the radio; screen states verified with demo data |
| Mode 1 / mode 2, favourites kept, vanished services | Tested logic |
| Start, stop, continue, discard, scan again | State machine built; needs the radio |
| List untouched if stopped, unplugged or interrupted | By design: only a completed scan commits. Tested logic for the commit; the interruption paths need the radio |
| Empty result and reception guidance | Tested logic; text built |

### C. Web radio

| Feature | State |
|---|---|
| API search with URL, country, genre, name | Verified (API 16 over TLS, API 36) |
| File import of .m3u/.m3u8/.pls | Tested logic (parser); picking a file built, not exercised |
| M3U URL, relative entries, HLS vs station list | Verified (API 16) and tested logic |
| Manual entry, "Add & play" | Verified (API 16) |
| My web stations: persist, test, remove | Add and test verified; remove built, not exercised |
| MP3, AAC-LC, HE-AAC v2, HLS | Verified on API 16; MP3 also on API 36 |
| Redirects, playlist indirection, ICY titles | Verified |
| Reconnect with backoff, bounded retries | Built, not exercised with a failing network |
| Unsupported formats declared | Verified (Ogg) |
| One audio pipeline at a time | By construction (session token); observed when switching stations |
| TLS on Android 4.x with validation intact | Verified: Conscrypt + extra roots; a server the system stack rejected now works |

### D. Station logos

| Feature | State |
|---|---|
| RadioDNS lookup, SI.xml, download, progress, per-station state | Verified on API 16 with the real identifiers of three Deutschlandradio services |
| Import / export logo pack | Built, not exercised |
| Tap to choose a logo file, hold to remove | Built, not exercised |
| Manual overrides preserved, offline cache, bounded decoding | Built; store verified through downloads |

### E. Missing receiver

| Feature | State |
|---|---|
| "No adapter found" screen, Search again, Add web radios | Verified (API 16, API 36) |
| "Do not search on future starts", re-enable in Settings | Built, not exercised |
| No device / permission pending / denied / unsupported / starting / disconnected / no stations | Tested logic (state machine); only "no device" seen on screen |
| Unplug and replug during playback or scan | Tested logic; needs the radio |

### F to J. Settings

| Setting | State |
|---|---|
| Language Auto / English / Deutsch | Verified (API 16) |
| Start when the USB tuner is detected; start in the background | Built, needs the radio |
| Close with back | Default (off) verified on API 36; on built, not exercised |
| Exit on audio focus loss; mute on focus loss; navigation volume | Tested logic; real focus events need the radio |
| Service following | Tested logic; needs the radio |
| Prevent stuttering (deeper buffer, refill before resuming) | Built, not exercised |
| Menu bar on top, presets per page | Verified (API 16) |
| Clock, name/frequency, DLS on top, preset pages, hide presets | Built, not exercised individually |
| DLS overlay | Built, not exercised |
| Show slideshow, dim after sunset, brightness | Dimming verified; slideshow needs the radio |
| Slideshow transparency | Setting verified; visual effect on a picture not inspected |
| Visualization (7 kinds) | Spectrum verified on real audio; the other six built, not exercised |
| Import scene | Built, not exercised; format documented in the README |
| Volume | Tested logic |
| AGC, noise suppression | Tested logic; not judged by ear |
| Theme Night / Day | Verified |
| Theme Auto (GPS) / Auto (system) | Sunset calculation tested; the modes built, not exercised |
| Accent colours with live preview | Preview verified; other accents built, not exercised |
| About: version, licences, privacy policy | Version verified; pages built, licences page opened once |

---

## 5. Decisions where the reference was silent

- Presets are shared between DAB and web stations.
- Selector settings step to the next option on tap, as in the prototype.
- The big station name uses the largest of the design's sizes that fits the width.
- On wide windows the lists get more columns and the artwork panel grows up to 400 x 300.
- Service following only uses alternatives the scan actually received with the same
  service id. FIG 0/21 frequency lists are not used.
- "Suppress noise" is a downward expander on quiet passages. It does not repair a bad
  signal.
- Dimming applies to pictures, scene and visualization, not to the plain monogram.
- The transparency setting applies to the logo as well as the slideshow.
- A paused scan can be discarded with an extra button; the prototype has no such control.

---

## 6. Build and test results

| Check | Result |
|---|---|
| `assembleDebug`, `assembleRelease` | pass |
| `lintDebug` | 0 errors, 0 warnings (ten checks disabled with reasons in `app/build.gradle.kts`) |
| Unit tests | 64 pass: station identity and persistence, scan modes, USB state machine, focus policy, service follower, playlist/frame/TS parsers, DSP, AAC config, DNS, RadioDNS, sunset, channel table, JNI contract |
| APK | minSdk 16, target 36; v1 and v2 signatures verify for API 16; ABIs armeabi-v7a, arm64-v8a, x86, x86_64 |
| Release APK size | 5.2 MB |
| Demo hook | absent from the release APK |

### Emulator runs

| Platform | What ran |
|---|---|
| Android 4.1.2 (API 16), x86, 1920 x 720 and 1280 x 720 | everything marked "API 16" above; native library load; background playback; recovery after `kill -9` |
| Android 16 (API 36), x86_64, 2992 x 1344 | launch, notification permission, search, add, playback, on-screen keyboard, presets, back, foreground service, media session |
| Android 4.1.2 (API 16), x86, made-up dongle (`fakeusb` debug hook) | libirtdab attach, permission, interface claim, endpoint lookup, bulk transfers from native threads, failed handshake reported as FAILED, detach with thread joins. No hardware, so no register traffic |
| Android 4.2.2 (API 17) | **not available**: no API 17 system image is installed |
| The head unit itself | one run, from a log sent by the owner; see below |

### First run on the head unit (2026-10-04, debug build)

The app started, found the dongle, loaded libirtdab and died with SIGSEGV in
`JUsbDevice::JUsbDevice` ("Invalid indirect reference ... in decodeIndirectRef") before any
USB transfer.

Cause: the library looked up `UsbDeviceConnection.bulkTransfer(UsbEndpoint, byte[], int,
int, int)`, which exists from API 18. On API 17 the lookup failed and left a
`NoSuchMethodError` pending; Dalvik then returns raw pointers from the next object call
and aborts. The unit test for the JNI contract skipped platform classes, and no emulator
run had a USB device, so nothing reached that code.

Fix: the library now uses the four-argument overload from API 12 (the offset was always
0). The unit test now rejects any `android.hardware.usb` lookup that is not in API 12.
The same abort was reproduced on the API 16 emulator with the `fakeusb` hook and is gone
with the fix.

### Second run on the head unit (2026-10-04, debug build with the fix)

Reported by the owner: DAB works. Web radio does not: an MP3 stream ended with "this
device has no decoder for MP3".

What is known: the unit lists three MP3-capable decoders (`OMX.google.mp3.decoder` and
`OMX.mtk.audio.decoder.mpeg` for `audio/mpeg`, `OMX.mtk.audio.decoder.mp3` for
`audio/mp3`). The app asked the system for its default `audio/mpeg` decoder only, and
threw the failure reason away, so **the cause is not known**.

Change: MP3 and AAC now try the software decoder by name first (the way DAB+ already
did, which works on the unit), then the system default, then for MP3 every other decoder
the device lists. Each failure is logged under `WelleAudio` and the reason is shown in the
error text. A decoder that takes about ten seconds of audio without returning PCM is
reported instead of loading forever. Checked on the API 16 emulator with an MP3 and an AAC
stream; not re-run on API 36. **Not yet re-run on the head unit**, and it is not certain
that any of the unit's MP3 decoders works through MediaCodec. If none does, the remedy is
bundling a small MP3 decoder in the native library.

### Third run on the head unit (2026-10-04)

Reported by the owner: web radio works with that build (which decoder was chosen was not
reported). Two things do not work:

1. **Start with the tuner.** "Start when the USB tuner is detected" is on and the system's
   question was accepted, but the app does not start by itself. Cause unknown. Candidates:
   the unit sends no USB attach event at ignition (wake from standby, or a boot in which the
   attach is handled before apps may start); the "use by default" choice was not stored;
   or the app starts and leaves again because the unit's own audio source takes audio
   focus ("Exit on audio focus loss" is on by default).
2. **Steering-wheel next/previous.** No effect in Welle; they work in some other apps.
   What the unit sends for them is unknown.

Change: debug builds now keep a small event list (`files/events.log`: process start, USB
attach intent, service commands, tuner state, audio focus changes, exit, keys that reach
the app). Media keys that reach a Welle screen as ordinary key events now act on the radio
directly; before, they only worked through the system's media-button slot, which the last
app to claim it owns. Checked on the API 16 emulator: `input keyevent 87/88` changes the
station with Welle in front (one step per press) and in the background.

### Fourth run on the head unit (2026-10-04, event list and key log from the owner)

**Start with the tuner: it works, in the background.** The event list shows two cold boots
(uptime 15 s and 9 s). In both, the USB attach intent arrived, the service got AUTOSTART and
the tuner was ready within about a second. "Start in the background" was switched on, so
the player screen was not opened; that is what the owner saw as "does not start". `dumpsys
usb` printed an empty "Device preferences" list at that moment although the box had been
ticked, yet the attach intent arrived at the next boots, so the choice evidently took effect.
Whether the radio was audible after those boots was not reported, and the list did not
record play state yet (it does now, together with screens shown and hidden).

**Steering-wheel keys: the unit does not use Android's media keys.** `adb shell input
keyevent 87` changed the station, and `dumpsys audio` shows Welle as the only media-button
receiver and on top of the audio focus stack (above `com.adayo.service.sourcemanager`). The
wheel keys take another route: MCU -> `AdayoKeyEventManager` -> "Third App" ->
`sendSpeechBroadcast` and `sendNextPrevBroadcast`, an ordinary broadcast sent once per press.
Its action and extras are not in the log, and nothing reached Welle. **Open**: the action
has to be read from `dumpsys activity broadcasts` right after a key press.

Change in this build: a media-button broadcast that only carries the key release is acted
on (covered by a unit test), and every such broadcast is written to the event list.

### Fifth exchange (2026-10-04)

**Wheel keys.** `dumpsys activity broadcasts` taken after a key press lists no key-related
broadcast for the whole boot session. Android only records a broadcast that has at least
one receiver, so the unit's signal had none while Welle played; its action is still
unknown. Added on a guess that fits the evidence but is **not confirmed**: Welle now obeys
"next" and "previous" in the form of the stock Android 4 music player
(`com.android.music.musicservicecommand`, extra `command`, or the `.next` / `.previous`
actions), while the radio is on. Checked on the API 16 emulator with `am broadcast`. The
decisive capture is the same `dumpsys` while an app in which the wheel keys work is playing.

**Autostart is silent.** With "Start in the background" off the owner still hears nothing
after ignition. The earlier event list shows the process alive with the tuner ready and no
audio-focus loss in the first seconds, so "focus taken away, app exits" is not supported by
what was logged. Open candidates: the unit's audio path is not switched to Android audio
because Welle asked for audio focus before the unit's source manager was up; playback never
left "loading"; or a crash after the logged lines. Needs the event list (which now records
play state) and the audio focus stack after a silent boot.

### Measured (emulators, so indicative only)

| Situation | PSS | CPU |
|---|---|---|
| API 16, MP3 stream, player and manager open | 13.2 MB | 2-3 % |
| API 16, MP3 stream, app in background, Conscrypt loaded | 16.9 MB | 3-4 % |
| API 36, MP3 stream, player open | 59 MB | not measured |

The 64 MiB target is met on both. CPU on x86 says little about a Cortex-A7. No timer runs
while paused; while playing there is one 1 Hz tick and, if a visualization is on screen,
one redraw every 50 ms.

Screenshots are in `docs/screenshots/`.

---

## 7. Open risks that need the radio

1. ~~Is it this chip?~~ Reported working by the owner on 2026-10-04.
2. **DAB+ decoding.** (Owner reported DAB audio working on 2026-10-04; whether the
   stations heard were DAB+ was not stated, and no decoder log was seen.)
   DAB+ uses the 960-sample AAC transform. The app configures
   `OMX.google.aac.decoder` the way the reference implementation does, but whether the
   Android 4.2.2 build of that decoder accepts it is unconfirmed. If stations tune, show
   text, but stay silent, this is the cause, and the fix is bundling an AAC decoder.
3. **DAB (MPEG Layer II).** Android 4.2 has no standard Layer II decoder. The app tries
   MediaTek's `OMX.mtk.audio.decoder.mpeg`. Unconfirmed, and irrelevant where every service
   is DAB+.
4. **Country code for RadioDNS.** Logos need the ensemble's ECC from the scan. If the
   library reports 0, DAB logos cannot be looked up.
5. **Plug-in detection.** If the firmware does not broadcast device attachment, the app
   still finds the dongle at start, on return to the player, and through "Search again".
6. **OEM audio focus.** With "Exit on audio focus loss" on, a head unit that takes focus
   for its own sources will close the radio. That is the reference default; turn it off if
   it surprises.
7. **CPU load** of the FIC/MSC polling loop in the library on this SoC.

---

## 8. Acceptance steps on the radio

Use the debug APK for the first run; it logs the tuner library under the tag `std`.
The owner reported DAB working on 2026-10-04 without going through these steps one by
one; treat each step as open until it has been seen.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

```bash
adb logcat -c
```

```bash
adb logcat -v time -s WelleUsb std WelleNet WelleLogos AndroidRuntime SoftAAC2 OMXCodec ACodec AudioTrack > welle-log.txt
```

1. Start Welle with the dongle plugged in. Allow USB access.
   Expect in the log: a `WelleUsb` line with `16c0:05dc ... ep02 t2 ep82 t2`, then
   `PowerUp okay!`. The status dot turns green ("USB TUNER").
2. Open the scan, press "Start scan". Expect the needle to step through the channels and
   stations to appear. Press "Stop", then "Continue".
3. After "Scan complete", tap a station. Expect the name, the ensemble line, signal bars,
   DLS text after a few seconds, and audio.
4. Leave it on one station for five minutes. Listen for dropouts.
5. Save a preset by holding a slot. Tap another station, then the preset.
6. Unplug the dongle while playing; plug it back in. Expect "Adapter disconnected", then
   playback again without restarting the app.
7. Start navigation guidance or a phone call. Expect ducking, or silence during the call,
   and no audio until it ends.
8. Switch ignition off and on. Expect the last station to return.
9. Station logos: "Download via RadioDNS" with the head unit online.

Resource evidence while a DAB station plays:

```bash
adb shell dumpsys meminfo me.ri3d.welle
```

```bash
adb shell top -n 5 -d 2 -m 8
```

```bash
adb shell top -t -n 1 -m 30
```

```bash
adb shell dumpsys usb
```

What to send back if something fails: `welle-log.txt`, the screen it stopped on, and the
`dumpsys usb` output.
