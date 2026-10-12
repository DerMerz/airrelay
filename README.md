# AirRelay

AirRelay is a lean AirPlay screen mirroring receiver for Fire TV (Fire OS, Android). It shows the
screen of an iPhone, iPad or Mac on the TV, with sound, and nothing else: no video casting, no
settings, no picture in picture, operated with the remote only. The source code is free software
under the GNU General Public License, version 3.

AirPlay, iPhone, iPad and Mac are trademarks of Apple Inc. Fire TV and Amazon Appstore are trademarks
of Amazon.com, Inc. or its affiliates. AirRelay is not affiliated with Apple or Amazon.

## Store version and subscription

The finished app is available in the Amazon Appstore for Fire TV. The store version contains a
subscription (Amazon In-App Purchasing): 7 days free, then €2.49 per month or €14.99 per year. Without
an active subscription or trial period it does not mirror. The source code of this version is published
here in full, as the GPL requires. Purchases are checked on the device only, through the Amazon
Appstore SDK. There is no server of our own, and the app collects no data.

A release build you compile yourself is not recognised by Amazon as a store app: the Appstore answers
price and purchase requests with `FAILED`, and the app then shows "Amazon can’t be reached".
The debug build has switches that let you look at every state (see "Debug build").

## License

GPL-3.0, the text is in [`LICENSE`](LICENSE). The app shows the license and the link to this source
code on the waiting screen under "Licences". All bundled libraries with version, license and location
are listed in [`THIRD-PARTY.md`](THIRD-PARTY.md). In short:

| Part | Source | License |
|---|---|---|
| Protocol, pairing, FairPlay, mirror RTP | [UxPlay](https://github.com/FDH2/UxPlay) (submodule) with parts of RPiPlay and ShairPlay as well as playfair | GPL-3.0, LGPL-2.1+, GPL-3.0 |
| JNI callbacks, dns-sd replacement, audio engine, UxPlay patches | [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) (as of c8defdd, 0.0.31) | GPL-3.0 |
| HTTP parser | llhttp (part of UxPlay) | MIT |
| Property lists | [libplist](https://github.com/libimobiledevice/libplist) (submodule) | LGPL-2.1+ |
| Cryptography | OpenSSL 3.4.4 via [openssl-cmake](https://github.com/viaduck/openssl-cmake) (submodule) | Apache-2.0 (OpenSSL), MIT (openssl-cmake) |
| ALAC decoder | [FFmpeg](https://github.com/FFmpeg/FFmpeg) n8.1.2 (submodule), ALAC only | LGPL-2.1+ |
| Audio output | [Oboe](https://github.com/google/oboe) 1.9.3 (submodule, static) | Apache-2.0 |
| Subscription handling | Amazon Appstore SDK 3.0.9 (Maven Central, not part of this repository) | Amazon, proprietary |

The first commit contains the files taken over from the upstream project unchanged, and every later
change is its own diff. Everything in Kotlin (service, decoder, user interface, subscription) and
`native_bridge.cpp` is newly written. ExoPlayer (used upstream) is not part of AirRelay: the picture
goes straight through `MediaCodec`.

Note from UxPlay: the legal status of the FairPlay part (playfair) is unclear.

## Building

Requirements: JDK 17, Android SDK with platform 36, build tools 36.0.0, NDK 27.0.12077973,
CMake 3.22.1, Perl and Python 3 (for OpenSSL). Tested on macOS. The libraries come as Git submodules,
`--depth 1` is enough.

```
git clone https://github.com/DerMerz/airrelay.git && cd airrelay
git submodule update --init --depth 1
export JAVA_HOME=<path to JDK 17>
export ANDROID_HOME=<path to the Android SDK>
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :receiver:assembleRelease      # first run takes about 10 min (OpenSSL, FFmpeg per ABI)
./gradlew --stop
```

Result: `receiver/build/outputs/apk/release/receiver-release.apk`. Without your own upload key it is
signed with the local debug key: installable for trying it out, not for a store.
Gradle reads your own key from `AIRRELAY_UPLOAD_KEYSTORE`, `AIRRELAY_UPLOAD_KEYSTORE_PASSWORD`
or from `local.properties` (`storeFile`, `storePassword`, never check it in). With
`AIRRELAY_REQUIRE_UPLOAD_KEY=1` the build aborts if the key is missing instead of falling back to
the debug key. `tools/build_release.sh` is the author's script for the store build and fetches the
password from the macOS keychain; adapt it for other environments.

`packageDebug` and `packageRelease` depend on the unit tests. If a test is red, the compatibility
contract included, no APK is produced.

Tests: `./gradlew :receiver:testDebugUnitTest` (115 JVM tests). Counter-check in which every
safeguard is removed once and the tests must turn red:
`python3 tools/mutation_check.py` (72 safeguards).

The file `receiver/src/main/assets/AppstoreAuthenticationKey.pem` is the app's public key from the
Amazon developer console (`BEGIN PUBLIC KEY`). The Appstore SDK uses it to check the signed
responses. Your own store listing needs your own key.

## Structure

```
receiver/src/main/cpp/            native library libairrelay_native.so
  native_bridge.cpp               JNI, fixed configuration
  android_raop_callbacks.c        8 callbacks into Kotlin, the rest answered natively
  android_dnssd_shim.c            TXT records for NsdManager (jqssun)
  audio_*.h/.cpp, timeline_buffer.h  audio engine (jqssun), plus software volume
  patches/UxPlay/                 patches to UxPlay, applied during the build
receiver/src/main/java/de/merzkevin/airrelay/
  core/                           pure logic without Android, all covered by JVM tests
  billing/                        subscription via Amazon In-App Purchasing
  ReceiverService.kt              foreground service: server, dns-sd, state
  video/VideoRenderer.kt          MediaCodec straight to the SurfaceView
  MainActivity.kt                 the one screen
receiver/contract/compat-contract.txt   frozen compatibility contract
tools/                            measuring script, counter-check of the tests, store build
```

Decisions with more than two cases are pure functions with a hand-written table:
`screenFor` (what is shown, 80 cases), `backActionFor` (Back button, 36 cases) and
`accessFor` (unlocking, 64 cases). The tables are in `receiver/src/test/resources/`.

## Behavior

- Name on the iPhone: device name of the Fire TV (`Settings.Global` `device_name`), otherwise "Fire TV".
  If the device name is changed, the receiver re-registers under the new name immediately.
  Long names are cut to 50 bytes, at a word boundary.
- Announced: `_airplay._tcp` and `_raop._tcp`, features `0x5A7FFEE6,0x0`, audio `cn=0,2,3` (AAC),
  HLS off, H.264 only, 1920x1080, 60 fps.
- Port 7000. If it is taken (a second receiver app), the receiver uses a free port; senders
  read it from dns-sd.
- When mirroring starts: the service brings the app to the front, full screen, picture fitted the
  right way up, black borders. When it ends: waiting screen.
- Back while waiting: the app closes, the receiver keeps running in the background (notification).
  Back while mirroring: a hint, and a second Back within 3 s ends the mirroring.
- Home while mirroring: sound keeps playing, the decoder parks. On return the picture is back immediately.
- No Wi-Fi, port problem, start failure: a screen of its own with "Try again".
- Start on boot: yes.
- Exactly one file is stored: the subscription status last confirmed by Amazon
  (`filesDir/entitlement.txt`). No settings. The AirPlay identity is derived from `ANDROID_ID`.

## Subscription (Amazon In-App Purchasing)

One subscription, two terms, each with a 7-day trial through Amazon. The identifiers are in
`core/Subscription.kt` (`Skus`) and in the frozen contract: `de.merzkevin.airrelay.pro` (parent),
`.pro.monthly` and `.pro.yearly`. A renamed SKU would lose every existing subscriber.

- On start and on every return to the app, the app asks Amazon for the complete receipt list. The
  status is derived from it, receipts are not stored.
- A subscription confirmed by Amazon is valid offline for up to 3 days from the last confirmation. A
  clock that is more than 10 minutes ahead of the confirmation grants no grace period.
- Refusal without a subscription: on the first SETUP, UxPlay asks `report_client_request`; patch `0007`
  then answers `403 Forbidden`. The iPhone shows its own error message, and the TV shows the
  subscription box. An admitted session runs to its end, even if the subscription expires meanwhile.
- The stored file has a tolerant, hand-written decoder. A read error counts as "unknown" (the app
  then asks Amazon), never as "no subscription". A broken file is set aside, never deleted.
  Frozen old files: `receiver/src/test/resources/.../fixtures/`.

## Debug build

Debug build only, without Amazon (the prices there are made up):

```
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_subscription ACTIVE
# ACTIVE | EXPIRED | EXPIRED_NO_TRIAL | EXPIRED_NO_PRICES | CHECKING | UNREACHABLE | OVERDUE | OVERDUE_IN_GRACE
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_message PURCHASE_CANCELLED
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_locale en   # "system" to go back
```

Feeding video without an iPhone: an H.264 file (Annex B with AUD) runs through exactly the path of a sender.

```
ffmpeg -f lavfi -i testsrc2=size=1920x1080:rate=60 -t 30 -c:v libx264 -profile:v high \
  -b:v 10M -x264-params aud=1:bframes=0:keyint=100000:scenecut=0 -f h264 land60.h264
adb shell mkdir -p /sdcard/Android/data/de.merzkevin.airrelay/files
adb push land60.h264 /sdcard/Android/data/de.merzkevin.airrelay/files/
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_feed land60.h264 --ei debug_fps 60
```

Testing a purchase without the store: the Amazon App Tester works only with the debug build; the test
file is `tools/amazon/amazon.sdktester.json` and deliberately does not end up in the release APK
(`tools/build_release.sh` checks this).

## Measurements (Fire TV Stick Lite, Fire OS 7.7, 2026-09-30)

| | Upstream (jqssun 0.0.31, release) | AirRelay |
|---|---|---|
| APK file | 25.9 MB (3 ABIs) | 5.5 MB (2 ABIs) |
| PSS, app in the foreground, after 60 s | 37.3 MB | 13.3 MB |
| CPU when idle | | 1 tick (10 ms) in 180 s |

The biggest item was the hardware-accelerated user interface: the waiting screen is drawn in software
(`hardwareAccelerated="false"`), which saves about 20 MB. The video picture is unaffected and runs
through the SurfaceView. With 60 fps and 30 fps (x264, 10 Mbit/s, 1080p) there are no dropouts.

## Fire OS 8 (Android 11)

From Android 10 on, the system ignores activity starts from the background. The service therefore
also shows a notification with a full-screen intent. UNTESTED: there has been no Fire OS 8 device so far.
Whether the Fire TV interface starts the full-screen intent as full screen is something only a test on
the device can tell.

## Known limitations

- The UxPlay HTTP server wakes up once per second (`select` timeout in `httpd.c`), measured
  1 CPU tick in 180 s. Not changed, because stopping and tearing down connections rely on it.
- Protected services (for example Netflix, Disney+, Apple TV+) stay black when mirrored.
- minSdk 24 (Fire OS 6 and newer). Fire OS 5 is left out.
- If a second receiver app holds port 7000, this app picks another port.

## Contact

Kevin Merz, [merzkevin.de](https://merzkevin.de), MerzKevin@me.com
