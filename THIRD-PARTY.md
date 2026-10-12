# Bundled third-party software

AirRelay as a whole is licensed under the GNU General Public License, version 3 (see `LICENSE`).
This list names what is inside, under which license it is distributed, and where its source code is.
The submodules are pinned to fixed commits (`git submodule status`), and the license texts are in the
folder of each library under `receiver/src/main/cpp/third_party/`.

| Component | Version | License | Source code | License text in the repository |
|---|---|---|---|---|
| android-airplay-server (jqssun): JNI callbacks, dns-sd replacement, audio engine, UxPlay patches | as of c8defdd (0.0.31) | GPL-3.0 | https://github.com/jqssun/android-airplay-server | `LICENSE` |
| UxPlay: AirPlay protocol, pairing, mirror RTP | submodule `third_party/UxPlay`, commit 4621533 | GPL-3.0 | https://github.com/FDH2/UxPlay | `third_party/UxPlay/LICENSE` |
| RPiPlay (parts in UxPlay) | as UxPlay | GPL-3.0 | https://github.com/FD-/RPiPlay | `third_party/UxPlay/LICENSE` |
| ShairPlay (parts in UxPlay) | as UxPlay | LGPL-2.1 or later | https://github.com/juhovh/shairplay | headers of the source files in `third_party/UxPlay/lib` |
| playfair (FairPlay part in UxPlay) | as UxPlay | GPL (text: GPL-3.0) | in UxPlay under `lib/playfair` | `third_party/UxPlay/lib/playfair/LICENSE.md` |
| llhttp (HTTP parser in UxPlay) | as UxPlay | MIT | https://github.com/nodejs/llhttp | `third_party/UxPlay/lib/llhttp/LICENSE-MIT` |
| libplist | submodule `third_party/libplist`, commit f41b1ea | LGPL-2.1 or later | https://github.com/libimobiledevice/libplist | `third_party/libplist/COPYING.LESSER` |
| OpenSSL | 3.4.4, built via openssl-cmake | Apache-2.0 | https://github.com/openssl/openssl | fetched by openssl-cmake during the build |
| openssl-cmake (build scripts) | submodule `third_party/openssl-cmake`, commit 4edd36a | MIT | https://github.com/viaduck/openssl-cmake | `third_party/openssl-cmake/LICENSE` |
| FFmpeg, only the ALAC decoder (`--disable-all --enable-decoder=alac`, without `--enable-gpl`) | n8.1.2, submodule `third_party/ffmpeg` | LGPL-2.1 or later | https://github.com/FFmpeg/FFmpeg | `third_party/ffmpeg/COPYING.LGPLv2.1` |
| Oboe (audio output) | 1.9.3, submodule `third_party/oboe`, statically linked | Apache-2.0 | https://github.com/google/oboe | `third_party/oboe/LICENSE` |
| libc++ from the Android NDK 27 | statically linked | Apache-2.0 with LLVM exception | https://developer.android.com/ndk | in the NDK |
| Amazon Appstore SDK (`com.amazon.device:amazon-appstore-sdk`) | 3.0.9, Maven Central | Amazon, proprietary | downloaded during the build, not part of this repository | |

Not included, although the upstream project uses them: ExoPlayer (Media3) and the Android Jetpack libraries.
AirRelay has no AndroidX dependency, the picture goes straight through `MediaCodec`.

The patches to UxPlay are files in `receiver/src/main/cpp/patches/UxPlay/` and are applied during the
build. The UxPlay source code itself stays unchanged in the submodule.

## Trademarks

AirPlay, iPhone, iPad and Mac are trademarks of Apple Inc. Fire TV and Amazon Appstore are trademarks
of Amazon.com, Inc. or its affiliates. AirRelay is not affiliated with Apple or Amazon.
