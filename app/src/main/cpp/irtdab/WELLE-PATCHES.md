# Local changes to IRT's DAB library

Upstream: https://github.com/hradio/omri-usb, commit `12f4b8ede51509226da102952646b97cb3c5752f`
("Initial release", 2020-02-07), directory `omriusb/src/main/cpp`. Licence: LGPL-2.1
(`LICENSE`, and `thirdparty/fec/LICENSE` for KA9Q's Reed-Solomon code).

The library is built as its own shared object (`libirtdab.so`) and only talked to through
JNI, so it can be rebuilt or replaced independently of the app.

Every change is marked `Welle patch` in the source.

| File | Change | Why |
|---|---|---|
| `platformspecific/android/ediinput.*` | removed, and its JNI entry points in `native-lib.cpp` | EDI-over-IP input is not used |
| `native-lib.cpp` | `startServiceScan` takes a start index | resume a stopped scan |
| `native-lib.cpp` | `std::cout` set to a failed state unless `DEBUGOUTPUT` | upstream formats log lines even when nobody reads them |
| `dabusbtunerinput.h`, `raontunerinput.*` | scan starts at the given table index; `scanProgress` reports the table index (41 = finished) instead of a percentage | the UI shows the channel being scanned |
| `raontunerinput.cpp` | `initializeSync` reports `TUNER_CALLBACK_FAILED` when the power-up handshake fails | upstream stayed silent, so an incompatible device looked like a hang |
| `raontunerinput.cpp` | `tunerPowerUp` uses 300 ms transfers and gives up after 5 I/O errors (`readRegisterChecked`) | VID 16c0 / PID 05dc is a shared id; reject foreign devices in seconds |
| `raontunerinput.cpp` | `setFrequency` ignores frequencies outside Band III | upstream detunes with `0xFFFFFFFF` and then read far past its lookup tables |
| `jusbdevice.*` | bulk transfers use `bulkTransfer(UsbEndpoint, byte[], int, int)` (API 12); the lookup of the API 18 overload with an offset is gone | on Android 4.2.2 the failed lookup left an exception pending and Dalvik aborted the process |
| `dabplusservicecomponentdecoder.cpp` | audio callback fires once per AAC access unit instead of once per superframe | MediaCodec expects one access unit per input buffer |

Debug builds define `DEBUGOUTPUT`, which sends the library's diagnostics to logcat under the
tag `std`.
