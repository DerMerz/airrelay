# Enthaltene Software von Dritten

AirRelay steht als Ganzes unter der GNU General Public License, Version 3 (siehe `LICENSE`).
Diese Liste nennt, was darin steckt, unter welcher Lizenz es steht und wo der Quelltext liegt.
Die Submodule sind auf feste Commits gesetzt (`git submodule status`), die Lizenztexte liegen
jeweils im Ordner der Bibliothek unter `receiver/src/main/cpp/third_party/`.

| Bestandteil | Version | Lizenz | Quelltext | Lizenztext im Repository |
|---|---|---|---|---|
| android-airplay-server (jqssun): JNI-Rückrufe, dns-sd-Ersatz, Audio-Engine, UxPlay-Patches | Stand c8defdd (0.0.31) | GPL-3.0 | https://github.com/jqssun/android-airplay-server | `LICENSE` |
| UxPlay: AirPlay-Protokoll, Pairing, Mirror-RTP | Submodul `third_party/UxPlay`, Commit 4621533 | GPL-3.0 | https://github.com/FDH2/UxPlay | `third_party/UxPlay/LICENSE` |
| RPiPlay (Teile in UxPlay) | wie UxPlay | GPL-3.0 | https://github.com/FD-/RPiPlay | `third_party/UxPlay/LICENSE` |
| ShairPlay (Teile in UxPlay) | wie UxPlay | LGPL-2.1 oder neuer | https://github.com/juhovh/shairplay | Kopfzeilen der Quelldateien in `third_party/UxPlay/lib` |
| playfair (FairPlay-Teil in UxPlay) | wie UxPlay | GPL (Text: GPL-3.0) | in UxPlay unter `lib/playfair` | `third_party/UxPlay/lib/playfair/LICENSE.md` |
| llhttp (HTTP-Parser in UxPlay) | wie UxPlay | MIT | https://github.com/nodejs/llhttp | `third_party/UxPlay/lib/llhttp/LICENSE-MIT` |
| libplist | Submodul `third_party/libplist`, Commit f41b1ea | LGPL-2.1 oder neuer | https://github.com/libimobiledevice/libplist | `third_party/libplist/COPYING.LESSER` |
| OpenSSL | 3.4.4, gebaut über openssl-cmake | Apache-2.0 | https://github.com/openssl/openssl | wird beim Bau von openssl-cmake geholt |
| openssl-cmake (Bauskripte) | Submodul `third_party/openssl-cmake`, Commit 4edd36a | MIT | https://github.com/viaduck/openssl-cmake | `third_party/openssl-cmake/LICENSE` |
| FFmpeg, nur der ALAC-Decoder (`--disable-all --enable-decoder=alac`, ohne `--enable-gpl`) | n8.1.2, Submodul `third_party/ffmpeg` | LGPL-2.1 oder neuer | https://github.com/FFmpeg/FFmpeg | `third_party/ffmpeg/COPYING.LGPLv2.1` |
| Oboe (Audioausgabe) | 1.9.3, Submodul `third_party/oboe`, statisch gebunden | Apache-2.0 | https://github.com/google/oboe | `third_party/oboe/LICENSE` |
| libc++ aus dem Android NDK 27 | statisch gebunden | Apache-2.0 mit LLVM-Ausnahme | https://developer.android.com/ndk | im NDK |
| Amazon Appstore SDK (`com.amazon.device:amazon-appstore-sdk`) | 3.0.9, Maven Central | Amazon, proprietär | wird beim Bau geladen, nicht Teil dieses Repositorys | |

Nicht enthalten, obwohl das Vorbild sie benutzt: ExoPlayer (Media3) und die Android-Jetpack-Bibliotheken.
AirRelay hat keine AndroidX-Abhängigkeit, das Bild läuft direkt über `MediaCodec`.

Die Patches an UxPlay liegen als Dateien in `receiver/src/main/cpp/patches/UxPlay/` und werden beim
Bau angewendet. Der UxPlay-Quelltext selbst bleibt unverändert im Submodul.

## Marken

AirPlay, iPhone, iPad und Mac sind Marken von Apple Inc. Fire TV und Amazon Appstore sind Marken von
Amazon.com, Inc. oder verbundenen Unternehmen. AirRelay steht in keiner Verbindung zu Apple oder Amazon.
