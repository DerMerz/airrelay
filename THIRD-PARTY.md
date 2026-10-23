# Bundled third-party software

AirRelay as a whole is licensed under the GNU General Public License, version 3 or later (see
`LICENSE`). The code by Kevin Merz carries an additional permission to link with the Amazon Appstore
SDK (see `LICENSE-EXCEPTION.md`, which also lists the files it covers). This list names what is
inside, under which license it is distributed, where its source code is, and in which file of the
APK it ends up (`libairrelay_native.so` is the app's library, `libairrelay_playfair.so` a separate
program).
The submodules are pinned to fixed commits (`git submodule status`), and the license texts are in the
folder of each library under `receiver/src/main/cpp/third_party/`.

| Component | Version | License | Source code | License text in the repository |
|---|---|---|---|---|
| AirRelay's own code: Kotlin app, `crypto/` (replaces UxPlay `lib/crypto.c`), `playfair/playfair_pipe.*`, `playfair_bridge.c`, `playfair_helper.c` | this repository | GPL-3.0-or-later with the additional permission in `LICENSE-EXCEPTION.md` (`playfair_helper.c` without it) | this repository | `LICENSE`, `LICENSE-EXCEPTION.md` |
| android-airplay-server (jqssun): JNI callbacks, dns-sd replacement, audio engine, UxPlay patches 0001 to 0006, in `libairrelay_native.so` | as of c8defdd (0.0.31) | GPL-3.0 (no additional permission from its authors) | https://github.com/jqssun/android-airplay-server | `LICENSE` |
| UxPlay `lib/`: AirPlay protocol, pairing, mirror RTP (with parts of ShairPlay and RPiPlay), in `libairrelay_native.so` | submodule `third_party/UxPlay`, commit 4621533 | LGPL-2.1 or later (file headers; the repository as a whole is GPL-3.0) | https://github.com/FDH2/UxPlay, https://github.com/juhovh/shairplay, https://github.com/FD-/RPiPlay | headers of the source files in `third_party/UxPlay/lib`, `third_party/UxPlay/LICENSE` |
| UxPlay `lib/crypto.c`, `lib/crypto.h` (GPL-3.0 or later) | as UxPlay | not used: not compiled since 0.1.1, replaced by AirRelay's own `crypto/` (written without reading it) | | |
| playfair (FairPlay part in UxPlay), only in the separate program `libairrelay_playfair.so` | as UxPlay | GPL (text: GPL-3.0) | in UxPlay under `lib/playfair` | `third_party/UxPlay/lib/playfair/LICENSE.md` |
| llhttp (HTTP parser in UxPlay) | as UxPlay | MIT | https://github.com/nodejs/llhttp | `third_party/UxPlay/lib/llhttp/LICENSE-MIT` |
| libplist | submodule `third_party/libplist`, commit f41b1ea | LGPL-2.1 or later | https://github.com/libimobiledevice/libplist | `third_party/libplist/COPYING.LESSER` |
| OpenSSL | 3.4.4, built via openssl-cmake | Apache-2.0 | https://github.com/openssl/openssl | fetched by openssl-cmake during the build |
| openssl-cmake (build scripts) | submodule `third_party/openssl-cmake`, commit 4edd36a | MIT | https://github.com/viaduck/openssl-cmake | `third_party/openssl-cmake/LICENSE` |
| FFmpeg, only the ALAC decoder (`--disable-all --enable-decoder=alac`, without `--enable-gpl`) | n8.1.2, submodule `third_party/ffmpeg` | LGPL-2.1 or later | https://github.com/FFmpeg/FFmpeg | `third_party/ffmpeg/COPYING.LGPLv2.1` |
| Oboe (audio output) | 1.9.3, submodule `third_party/oboe`, statically linked | Apache-2.0 | https://github.com/google/oboe | `third_party/oboe/LICENSE` |
| libc++ from the Android NDK 27 | statically linked | Apache-2.0 with LLVM exception | https://developer.android.com/ndk | in the NDK |
| Amazon Appstore SDK (`com.amazon.device:amazon-appstore-sdk`) | 3.0.9, Maven Central | Amazon, proprietary | downloaded during the build, not part of this repository | |
| AndroidX Media3 (ExoPlayer), only `media3-exoplayer` and `media3-exoplayer-hls` with what they pull in: `media3-common`, `media3-container`, `media3-database`, `media3-datasource`, `media3-decoder`, `media3-extractor`; plays HLS video handed over by a sender (since 0.1.5), in `classes.dex` | 1.8.0, Maven (Google) | Apache-2.0 | https://github.com/androidx/media | downloaded during the build, text: https://www.apache.org/licenses/LICENSE-2.0 |
| Libraries Media3 depends on, in `classes.dex` where R8 keeps them: AndroidX `annotation`, `annotation-experimental`, `collection`, `concurrent-futures`, `core`, `core-common`, `core-runtime`, `exifinterface`, `lifecycle-common`, `lifecycle-runtime`, `versionedparcelable`; Guava (`guava`, `failureaccess`, `listenablefuture`); annotations `jsr305`, `error_prone_annotations`, `j2objc-annotations` | as resolved by Gradle (`./gradlew :receiver:dependencies --configuration releaseRuntimeClasspath`), Maven | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support, https://github.com/google/guava, https://github.com/google/error-prone, https://github.com/google/j2objc | downloaded during the build |
| Checker Framework qualifiers (`checker-qual`), annotations only | as resolved by Gradle, Maven | MIT | https://github.com/typetools/checker-framework | downloaded during the build |
| Kotlin standard library (`kotlin-stdlib`) with the JetBrains annotations (`annotations`) | as resolved by Gradle, Maven | Apache-2.0 | https://github.com/JetBrains/kotlin, https://github.com/JetBrains/java-annotations | downloaded during the build |

Mirroring does not use Media3: the mirrored picture goes straight through `MediaCodec`. Media3 is
only loaded when a sender hands over a video stream. Not included: `media3-ui`, Jetpack Compose and
the other Jetpack libraries the upstream project uses.

The patches to UxPlay are files in `receiver/src/main/cpp/patches/UxPlay/` and are applied during the
build. The UxPlay source code itself stays unchanged in the submodule. The build copies UxPlay's `lib/`
into the build directory without `crypto.c` and `crypto.h`, and a check after linking
(`cmake/CheckLicenseSplit.cmake`) fails the build if playfair or UxPlay's `crypto.c` is found in
`libairrelay_native.so`.

## Known limits of the licence split

- The jqssun parts in `libairrelay_native.so` are GPL-3.0 without an additional permission. The
  permission in `LICENSE-EXCEPTION.md` can only cover Kevin Merz's code.
- This is how the authors read the licences, not legal advice. Whether separate programs that talk
  over a pipe count as separate works is, in the end, for courts to decide (FSF GPL FAQ,
  "MereAggregation").

## Trademarks

AirPlay, iPhone, iPad and Mac are trademarks of Apple Inc. Fire TV and Amazon Appstore are trademarks
of Amazon.com, Inc. or its affiliates. AirRelay is not affiliated with Apple or Amazon.
