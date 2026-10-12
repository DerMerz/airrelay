# AirRelay

AirRelay is a lean AirPlay screen mirroring receiver for Fire TV (Fire OS, Android). It shows the
screen of an iPhone, iPad or Mac on the TV, with sound, and nothing else: no video casting, no
settings, no picture in picture, operated with the remote only. The source code is free software
under the GNU General Public License, version 3.

Schlanker Empfänger für die Bildschirmsynchronisierung von iPhone, iPad und Mac auf Fire TV.
Nur Spiegelung plus Ton, ohne Einstellungen, bedienbar mit der Fernbedienung.

AirPlay, iPhone, iPad und Mac sind Marken von Apple Inc. Fire TV und Amazon Appstore sind Marken von
Amazon.com, Inc. oder verbundenen Unternehmen. AirRelay steht in keiner Verbindung zu Apple oder Amazon.

## Store-Fassung und Abo

Die fertige App gibt es im Amazon Appstore für Fire TV. Die Store-Fassung enthält ein Abo
(Amazon In-App Purchasing): 7 Tage gratis, danach 2,49 € im Monat oder 14,99 € im Jahr. Ohne aktives
Abo oder Probezeit spiegelt sie nicht. Der Quelltext dieser Fassung ist vollständig hier
veröffentlicht, so wie es die GPL verlangt. Die Kaufprüfung läuft nur auf dem Gerät über das Amazon
Appstore SDK, es gibt keinen eigenen Server und die App sammelt keine Daten.

Ein selbst gebauter Release-Build wird von Amazon nicht als Store-App erkannt: der Appstore
antwortet auf Preis- und Kaufanfragen mit `FAILED`, die App zeigt dann "Amazon ist nicht erreichbar".
Der Debug-Build hat Schalter, mit denen sich alle Zustände ansehen lassen (siehe "Debug-Build").

## Lizenz

GPL-3.0, der Text steht in [`LICENSE`](LICENSE). Die App zeigt die Lizenz und den Link auf diesen
Quelltext auf dem Wartebildschirm unter "Lizenzen". Alle enthaltenen Bibliotheken mit Version,
Lizenz und Fundort stehen in [`THIRD-PARTY.md`](THIRD-PARTY.md). Kurzfassung:

| Teil | Quelle | Lizenz |
|---|---|---|
| Protokoll, Pairing, FairPlay, Mirror-RTP | [UxPlay](https://github.com/FDH2/UxPlay) (Submodul) mit Teilen aus RPiPlay und ShairPlay sowie playfair | GPL-3.0, LGPL-2.1+, GPL-3.0 |
| JNI-Rückrufe, dns-sd-Ersatz, Audio-Engine, UxPlay-Patches | [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) (Stand c8defdd, 0.0.31) | GPL-3.0 |
| HTTP-Parser | llhttp (liegt in UxPlay) | MIT |
| Property Lists | [libplist](https://github.com/libimobiledevice/libplist) (Submodul) | LGPL-2.1+ |
| Kryptografie | OpenSSL 3.4.4 über [openssl-cmake](https://github.com/viaduck/openssl-cmake) (Submodul) | Apache-2.0 (OpenSSL), MIT (openssl-cmake) |
| ALAC-Decoder | [FFmpeg](https://github.com/FFmpeg/FFmpeg) n8.1.2 (Submodul), nur ALAC | LGPL-2.1+ |
| Audioausgabe | [Oboe](https://github.com/google/oboe) 1.9.3 (Submodul, statisch) | Apache-2.0 |
| Abo-Abwicklung | Amazon Appstore SDK 3.0.9 (Maven Central, nicht Teil dieses Repositorys) | Amazon, proprietär |

Der erste Commit enthält die übernommenen Dateien aus dem Vorbild unverändert, jede spätere
Änderung steht als eigener Diff da. Neu geschrieben ist alles in Kotlin (Dienst, Decoder,
Oberfläche, Abo) und `native_bridge.cpp`. ExoPlayer (Vorbild) ist in AirRelay nicht enthalten:
das Bild läuft direkt über `MediaCodec`.

Hinweis aus UxPlay: der rechtliche Status des FairPlay-Teils (playfair) ist ungeklärt.

## Bauen

Voraussetzungen: JDK 17, Android SDK mit Platform 36, Build-Tools 36.0.0, NDK 27.0.12077973,
CMake 3.22.1, Perl und Python 3 (für OpenSSL). Getestet auf macOS. Die Bibliotheken liegen als
Git-Submodule vor, `--depth 1` genügt.

```
git clone https://github.com/DerMerz/airrelay.git && cd airrelay
git submodule update --init --depth 1
export JAVA_HOME=<Pfad zu JDK 17>
export ANDROID_HOME=<Pfad zum Android SDK>
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :receiver:assembleRelease      # erster Lauf ca. 10 min (OpenSSL, FFmpeg je ABI)
./gradlew --stop
```

Ergebnis: `receiver/build/outputs/apk/release/receiver-release.apk`. Ohne eigenen Upload-Schlüssel wird
mit dem lokalen Debug-Schlüssel signiert: installierbar zum Ausprobieren, nicht für einen Store.
Einen eigenen Schlüssel liest Gradle aus `AIRRELAY_UPLOAD_KEYSTORE`, `AIRRELAY_UPLOAD_KEYSTORE_PASSWORD`
oder aus `local.properties` (`storeFile`, `storePassword`, nie einchecken). Mit
`AIRRELAY_REQUIRE_UPLOAD_KEY=1` bricht der Bau ab, wenn der Schlüssel fehlt, statt auf den Debug-Schlüssel
zurückzufallen. `tools/build_release.sh` ist das Skript des Autors für den Store-Build und holt das
Passwort aus dem macOS-Schlüsselbund; für andere Umgebungen anpassen.

`packageDebug` und `packageRelease` hängen an den Unit-Tests. Ist ein Test rot, auch der
Kompatibilitätsvertrag, entsteht keine APK.

Tests: `./gradlew :receiver:testDebugUnitTest` (115 JVM-Tests). Gegenprobe, bei der jede
Absicherung einmal herausgenommen wird und die Tests rot werden müssen:
`python3 tools/mutation_check.py` (72 Absicherungen).

Die Datei `receiver/src/main/assets/AppstoreAuthenticationKey.pem` ist der öffentliche Schlüssel
der App aus der Amazon-Entwicklerkonsole (`BEGIN PUBLIC KEY`). Das Appstore SDK prüft damit die
signierten Antworten. Ein eigener Store-Eintrag braucht den eigenen Schlüssel.

## Aufbau

```
receiver/src/main/cpp/            native Bibliothek libairrelay_native.so
  native_bridge.cpp               JNI, feste Konfiguration
  android_raop_callbacks.c        8 Rückrufe nach Kotlin, Rest nativ beantwortet
  android_dnssd_shim.c            TXT-Records für NsdManager (jqssun)
  audio_*.h/.cpp, timeline_buffer.h  Audio-Engine (jqssun), plus Software-Lautstärke
  patches/UxPlay/                 Patches an UxPlay, beim Bau angewendet
receiver/src/main/java/de/merzkevin/airrelay/
  core/                           reine Logik ohne Android, alles mit JVM-Tests
  billing/                        Abo über Amazon In-App Purchasing
  ReceiverService.kt              Vordergrunddienst: Server, dns-sd, Zustand
  video/VideoRenderer.kt          MediaCodec direkt auf die SurfaceView
  MainActivity.kt                 der eine Bildschirm
receiver/contract/compat-contract.txt   eingefrorener Kompatibilitätsvertrag
tools/                            Messskript, Gegenprobe der Tests, Store-Build
```

Entscheidungen mit mehr als zwei Fällen sind reine Funktionen mit einer von Hand geschriebenen
Tabelle: `screenFor` (was wird angezeigt, 80 Fälle), `backActionFor` (Zurück-Taste, 36 Fälle) und
`accessFor` (Freischaltung, 64 Fälle). Die Tabellen liegen in `receiver/src/test/resources/`.

## Verhalten

- Name im iPhone: Gerätename des Fire TV (`Settings.Global` `device_name`), sonst "Fire TV".
  Wird der Gerätename geändert, meldet sich der Empfänger sofort unter dem neuen Namen an.
  Lange Namen werden auf 50 Byte gekürzt, an einer Wortgrenze.
- Angekündigt: `_airplay._tcp` und `_raop._tcp`, features `0x5A7FFEE6,0x0`, Audio `cn=0,2,3` (AAC),
  HLS aus, nur H.264, 1920x1080, 60 fps.
- Port 7000. Ist er belegt (zweite Empfänger-App), nimmt der Empfänger einen freien Port; Sender
  lesen ihn aus dns-sd.
- Spiegelung beginnt: der Dienst holt die App nach vorn, Vollbild, Bild seitenrichtig eingepasst,
  schwarze Ränder. Endet sie: Wartebildschirm.
- Zurück beim Warten: App schließt, der Empfänger läuft im Hintergrund weiter (Benachrichtigung).
  Zurück beim Spiegeln: Hinweis, ein zweites Zurück innerhalb von 3 s beendet die Spiegelung.
- Home beim Spiegeln: Ton läuft weiter, der Decoder parkt. Beim Zurückkehren ist das Bild sofort wieder da.
- Kein WLAN, Port-Problem, Startfehler: eigener Bildschirm mit "Erneut versuchen".
- Start beim Booten: ja.
- Gespeichert wird genau eine Datei: der zuletzt von Amazon bestätigte Abo-Status
  (`filesDir/entitlement.txt`). Keine Einstellungen. Die AirPlay-Identität wird aus `ANDROID_ID` abgeleitet.

## Abo (Amazon In-App Purchasing)

Ein Abo, zwei Laufzeiten, je 7 Tage Probezeit über Amazon. Die Kennungen stehen in
`core/Subscription.kt` (`Skus`) und im eingefrorenen Vertrag: `de.merzkevin.airrelay.pro` (Eltern),
`.pro.monthly` und `.pro.yearly`. Eine umbenannte SKU würde jeden bestehenden Abonnenten verlieren.

- Beim Start und bei jedem Zurückkehren fragt die App Amazon nach der vollständigen Belegliste. Der
  Status wird daraus abgeleitet, Belege werden nicht gespeichert.
- Ein von Amazon bestätigtes Abo gilt offline bis zu 3 Tage ab der letzten Bestätigung. Eine Uhr, die
  mehr als 10 Minuten vor der Bestätigung steht, gewährt keine Frist.
- Ablehnung ohne Abo: UxPlay fragt beim ersten SETUP `report_client_request`; Patch `0007` antwortet dann
  `403 Forbidden`. Das iPhone zeigt seine eigene Fehlermeldung, auf dem Fernseher erscheint der Abo-Kasten.
  Eine zugelassene Sitzung läuft zu Ende, auch wenn das Abo währenddessen ausläuft.
- Die gespeicherte Datei hat einen toleranten, handgeschriebenen Decoder. Ein Lesefehler gilt als
  "unbekannt" (dann fragt die App Amazon), nie als "kein Abo". Eine kaputte Datei wird beiseitegelegt,
  nie gelöscht. Eingefrorene Altdateien: `receiver/src/test/resources/.../fixtures/`.

## Debug-Build

Nur im Debug-Build, ohne Amazon (die Preise dort sind erfunden):

```
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_subscription ACTIVE
# ACTIVE | EXPIRED | EXPIRED_NO_TRIAL | EXPIRED_NO_PRICES | CHECKING | UNREACHABLE | OVERDUE | OVERDUE_IN_GRACE
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_message PURCHASE_CANCELLED
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_locale en   # "system" zurück
```

Zuspielung ohne iPhone: eine H.264-Datei (Annex B mit AUD) läuft durch genau den Weg eines Senders.

```
ffmpeg -f lavfi -i testsrc2=size=1920x1080:rate=60 -t 30 -c:v libx264 -profile:v high \
  -b:v 10M -x264-params aud=1:bframes=0:keyint=100000:scenecut=0 -f h264 land60.h264
adb shell mkdir -p /sdcard/Android/data/de.merzkevin.airrelay/files
adb push land60.h264 /sdcard/Android/data/de.merzkevin.airrelay/files/
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_feed land60.h264 --ei debug_fps 60
```

Kauf testen ohne Store: der Amazon App Tester arbeitet nur mit dem Debug-Build; die Testdatei liegt in
`tools/amazon/amazon.sdktester.json` und landet bewusst nicht in der Release-APK (`tools/build_release.sh`
prüft das).

## Messwerte (Fire TV Stick Lite, Fire OS 7.7, 30.09.2026)

| | Vorbild (jqssun 0.0.31, Release) | AirRelay |
|---|---|---|
| APK-Datei | 25,9 MB (3 ABIs) | 5,5 MB (2 ABIs) |
| PSS, App im Vordergrund, nach 60 s | 37,3 MB | 13,3 MB |
| CPU im Leerlauf | | 1 Tick (10 ms) in 180 s |

Der größte Posten war die hardwarebeschleunigte Oberfläche: der Wartebildschirm wird in Software
gezeichnet (`hardwareAccelerated="false"`), das spart etwa 20 MB. Das Videobild läuft davon unberührt
über die SurfaceView. Mit 60 fps und 30 fps (x264, 10 Mbit/s, 1080p) gibt es keine Aussetzer.

## Fire OS 8 (Android 11)

Ab Android 10 ignoriert das System Starts von Aktivitäten aus dem Hintergrund. Der Dienst zeigt deshalb
zusätzlich eine Benachrichtigung mit Full-Screen-Intent. UNGEPRÜFT: es gab bisher kein Fire-OS-8-Gerät.
Ob die Fire-TV-Oberfläche den Full-Screen-Intent als Vollbild startet, weiß erst ein Test auf dem Gerät.

## Bekannte Grenzen

- Der UxPlay-HTTP-Server wacht einmal pro Sekunde auf (`select`-Timeout in `httpd.c`), gemessen
  1 CPU-Tick in 180 s. Nicht verändert, weil Stopp und Verbindungsabbau darauf beruhen.
- Geschützte Dienste (zum Beispiel Netflix, Disney+, Apple TV+) bleiben beim Spiegeln schwarz.
- minSdk 24 (Fire OS 6 und neuer). Fire OS 5 ist ausgelassen.
- Hält eine zweite Empfänger-App Port 7000, weicht diese App aus.

## Kontakt

Kevin Merz, [merzkevin.de](https://merzkevin.de), MerzKevin@me.com
