# Spiegel (Arbeitsname)

Schlanker Empfänger für die Bildschirmsynchronisierung von iPhone und iPad auf Fire TV
(Fire OS, Android). Nur Spiegelung plus Ton. Kein Video-Weiterreichen, keine Einstellungen,
kein Bild im Bild. Bedienung nur mit der Fernbedienung.

Paketname vorläufig `de.merzkevin.spiegel` (Variable `appId` in `receiver/build.gradle.kts`).
Der Name wird noch geprüft.

## Herkunft und Lizenz

GPL-3.0 (siehe `LICENSE`). Grundlage ist
[jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) (GPL-3.0,
Stand c8defdd, Version 0.0.31). Übernommen ist nur der native Kern:

| Teil | Quelle | Lizenz |
|---|---|---|
| Protokoll, Pairing, FairPlay, Mirror-RTP | [UxPlay](https://github.com/FDH2/UxPlay) (Submodul) mit RPiPlay/ShairPlay und playfair | GPL-3.0, LGPL-2.1+, GPL |
| JNI-Rückrufe, dns-sd-Ersatz, Audio-Engine, 6 UxPlay-Patches | jqssun/android-airplay-server | GPL-3.0 |
| Property Lists | [libplist](https://github.com/libimobiledevice/libplist) (Submodul) | LGPL-2.1+ |
| Kryptografie | OpenSSL 3.4.4 über [openssl-cmake](https://github.com/viaduck/openssl-cmake) | Apache-2.0 |
| ALAC-Decoder | [FFmpeg](https://github.com/FFmpeg/FFmpeg) n8.1.2 (Submodul), nur ALAC | LGPL-2.1+ |
| Audioausgabe | [Oboe](https://github.com/google/oboe) 1.9.3 (Submodul, statisch) | Apache-2.0 |

Der erste Commit enthält die übernommenen Dateien unverändert, jede spätere Änderung steht
als eigener Diff da. Neu geschrieben ist alles in Kotlin (Dienst, Decoder, Oberfläche) und
`native_bridge.cpp`.

Hinweis aus UxPlay: der rechtliche Status des FairPlay-Teils (playfair) ist ungeklärt. Für
einen Verkauf ist das ein Risiko, das vorher bewertet werden muss.

Die Lizenzseite der App verlinkt den Quelltext mit der Platzhalter-URL `sourceUrl` in
`receiver/build.gradle.kts`. Vor der Weitergabe einer APK muss dort die echte Adresse stehen.

## Bauen

Voraussetzungen (so auf Kevins Mac vorhanden): JDK 17, Android SDK mit Platform 36,
Build-Tools 36.0.0, NDK 27.0.12077973, CMake 3.22.1, Perl und Python 3 (für OpenSSL).

```
git clone <repo> && cd <repo>
git submodule update --init --depth 1
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :receiver:assembleRelease      # erster Lauf ca. 10 min (OpenSSL, FFmpeg je ABI)
./gradlew --stop
```

Ergebnis: `receiver/build/outputs/apk/release/receiver-release.apk`. Ohne Release-Schlüssel
in `local.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) wird mit dem
lokalen Debug-Schlüssel signiert: installierbar zum Testen, nie für den Store.

`packageDebug`/`packageRelease` hängen an den Unit-Tests. Ist ein Test rot (auch der
Kompatibilitätsvertrag), entsteht keine APK. Ein Build direkt aus Android Studio mit
"Run" geht denselben Weg; nur ein von Hand gepacktes APK liefe daran vorbei.

Tests: `./gradlew :receiver:testDebugUnitTest`. Gegenprobe (jede Absicherung einmal
herausnehmen, Tests müssen rot werden): `python3 tools/mutation_check.py`.

## Aufbau

```
receiver/src/main/cpp/            native Bibliothek libspiegel_native.so
  native_bridge.cpp               JNI, feste Konfiguration (neu)
  android_raop_callbacks.c        8 Rückrufe nach Kotlin, Rest nativ beantwortet
  android_dnssd_shim.c            TXT-Records für NsdManager (jqssun)
  audio_*.h/.cpp, timeline_buffer.h  Audio-Engine (jqssun), plus Software-Lautstärke
  patches/UxPlay/                 6 Patches aus jqssun, beim Bau angewendet
receiver/src/main/java/de/merzkevin/spiegel/
  core/                           reine Logik ohne Android, alles mit JVM-Tests
  ReceiverService.kt              Vordergrunddienst: Server, dns-sd, Zustand
  video/VideoRenderer.kt          MediaCodec direkt auf die SurfaceView
  MainActivity.kt                 der eine Bildschirm
receiver/contract/compat-contract.txt   eingefrorener Kompatibilitätsvertrag
tools/measure.sh                  Messung während einer echten Spiegelung
```

Entscheidungen mit mehr als zwei Fällen sind reine Funktionen mit Tabelle:
`screenFor` (was wird angezeigt, 80 Fälle) und `backActionFor` (Zurück-Taste, 36 Fälle),
Tabellen in `receiver/src/test/resources/`.

Freischaltung (Phase 2): `LicenseGate.current()` in `core/ScreenDecision.kt` ist die eine
Stelle. `screenFor` zeigt bei `LOCKED` den Sperrbildschirm, der Dienst holt die App dann
nicht mehr nach vorn.

## Verhalten

- Name im iPhone: Gerätename des Fire TV (`Settings.Global` `device_name`), sonst "Fire TV".
  Wird der Gerätename geändert, meldet sich der Empfänger sofort unter dem neuen Namen an.
  Lange Namen werden auf 50 Byte gekürzt, an einer Wortgrenze.
- Angekündigt: `_airplay._tcp` und `_raop._tcp`, features `0x5A7FFEE6,0x0` (Bits 0 und 4
  aus, 9 an, 42 aus), Audio `cn=0,2,3` (AAC), HLS aus, nur H.264, 1920x1080, 60 fps.
- Port 7000. Ist er belegt (zweite Empfänger-App), nimmt der Empfänger einen freien Port;
  Sender lesen ihn aus dns-sd. Auf dem Stick geprüft.
- Spiegelung beginnt: der Dienst holt die App nach vorn, Vollbild, Bild seitenrichtig
  eingepasst, schwarze Ränder. Endet sie: Wartebildschirm.
- Zurück beim Warten: App schließt, Empfänger läuft im Hintergrund weiter (Benachrichtigung).
  Zurück beim Spiegeln: Hinweis, zweites Zurück innerhalb von 3 s beendet die Spiegelung.
- Home beim Spiegeln: Ton läuft weiter, der Decoder parkt. Beim Zurückkehren ist das Bild
  sofort wieder da.
- Kein WLAN, Port-Problem, Startfehler: eigener Bildschirm mit "Erneut versuchen"
  (bei Netzwerk zusätzlich "Netzwerkeinstellungen"). Netzänderungen starten ihn selbst neu.
- Start beim Booten: ja (abschaltbar ist Phase 2).
- Gespeichert wird nichts: keine Einstellungen, keine Dateien. Die AirPlay-Identität wird
  aus `ANDROID_ID` abgeleitet (stabil pro Installation).

## Messwerte (Fire TV Stick Lite AFTSS, Fire OS 7.7, 30.09.2026)

| | alt (jqssun 0.0.31, Release) | neu |
|---|---|---|
| APK-Datei | 25,9 MB (3 ABIs) | 5,4 MB (2 ABIs) |
| native Bibliothek armeabi-v7a | 5,9 MB (4 Dateien) | 2,1 MB (1 Datei) |
| dex | 2,0 MB | 0,06 MB |
| PSS, App im Vordergrund, nach 60 s | 37,3 MB | 15,5 MB |
| PSS, nach Home, nach 60 s | 30,0 MB | 24,2 MB |
| PSS, nach Zurück (nur Dienst), nach 60 s | | 16,4 bis 17,7 MB |
| CPU im Leerlauf | | 1 Tick (10 ms) in 180 s |

Der größte Posten war die hardwarebeschleunigte Oberfläche: der Wartebildschirm wird in
Software gezeichnet (`hardwareAccelerated="false"`), das spart etwa 20 MB (GL-Treiber und
Fensterpuffer). Das Videobild läuft davon unberührt über die SurfaceView.

Zeit bis zum ersten Bild (Debug-Zuspielung, nicht iPhone): App im Vordergrund 0,54 s,
App im Hintergrund (inklusive Nach-vorn-holen) 1,03 s.

Tests: 68 JVM-Tests. Gegenprobe mit `tools/mutation_check.py`: 39 Absicherungen einzeln
entfernt, alle 39 machen die Tests rot (zwei Lücken wurden dabei gefunden und geschlossen).
Build-Sperre in beiden Richtungen geprüft: mit gebrochenem Vertrag endet `assembleRelease`
mit 1 und es entsteht keine APK, mit heilem mit 0.

## Bildrate

Gemessen auf dem Stick Lite mit der Debug-Zuspielung (x264, High Profile, 10 Mbit/s,
1920x1080, ohne B-Frames): 60 fps und 30 fps werden beide ohne Aussetzer dekodiert
(1795 von 1800 bzw. 897 von 900 Bildern angezeigt, der Rest steckte beim Stopp noch im
Decoder, 0 Neustarts). CPU-Summe App + Codec-Dienst + SurfaceFlinger ca. 53 % bei 60 fps
und ca. 31 % bei 30 fps (von 400 %). Deshalb 60 fps. Ob das iPhone-Bild bei 60 fps
flüssiger wirkt, lässt sich nur mit einem iPhone beurteilen; die Konstante
`ReceiverConfig.MAX_FPS` ist die Stellschraube.

## Debug-Zuspielung (ohne iPhone)

Nur im Debug-Build. Spielt eine H.264-Datei (Annex B mit AUD, z. B. x264 `aud=1`) durch
genau den Weg eines Senders:

```
ffmpeg -f lavfi -i testsrc2=size=1920x1080:rate=60 -t 30 -c:v libx264 -profile:v high \
  -b:v 10M -x264-params aud=1:bframes=0:keyint=100000:scenecut=0 -f h264 land60.h264
adb shell mkdir -p /sdcard/Android/data/de.merzkevin.spiegel/files
adb push land60.h264 /sdcard/Android/data/de.merzkevin.spiegel/files/
# App im Vordergrund:
adb shell am start -n de.merzkevin.spiegel/.MainActivity --es debug_feed land60.h264 --ei debug_fps 60
# App im Hintergrund (prüft das Nach-vorn-holen):
adb shell am broadcast -a de.merzkevin.spiegel.DEBUG_FEED -n de.merzkevin.spiegel/.DebugFeedReceiver \
  --es debug_feed land60.h264 --ei debug_fps 60
# Fehlerbildschirm zeigen, ohne das Netz zu trennen:
adb shell am start -n de.merzkevin.spiegel/.MainActivity --es debug_status NO_NETWORK
```

## Messen mit iPhone

```
adb connect 192.168.8.11:5555
adb install -r receiver/build/outputs/apk/release/receiver-release.apk
tools/measure.sh 60 5        # dann am iPhone spiegeln
```

Das Skript stoppt die alte App (`io.github.jqssun.airplay`), wartet auf "Client connected",
protokolliert PSS und CPU alle 5 s in eine CSV und gibt am Ende die Zeit von
"Client connected" und von "Mirroring started" bis "First frame rendered" aus.

## Fire OS 8 (Android 11)

Auf Fire OS 7 (Android 9) darf der Vordergrunddienst die App selbst nach vorn holen,
auf dem Stick geprüft. Ab Android 10 sperrt das System Activity-Starts aus dem Hintergrund.
Wege, die dort bleiben:

1. Berechtigung "Über anderen Apps einblenden" (`SYSTEM_ALERT_WINDOW`). Mit ihr sind
   Hintergrundstarts erlaubt. Fire OS hat dafür keine gut erreichbare Einstellung, per adb
   geht `adb shell appops set <appId> SYSTEM_ALERT_WINDOW allow`. Für Kundinnen und Kunden
   ungeeignet, außer Fire OS 8 bietet den Schalter an (am Gerät prüfen).
2. Benachrichtigung mit Full-Screen-Intent (`USE_FULL_SCREEN_INTENT`, Kategorie CALL oder
   ALARM). Auf Telefonen der übliche Weg; ob die Fire-TV-Oberfläche sie als Vollbild startet,
   ist ungeprüft.
3. Fallback ohne Sonderrechte: solange die App im Vordergrund ist, läuft alles wie auf
   Fire OS 7. Liegt sie im Hintergrund, spielt der Ton, und die Benachrichtigung öffnet
   das Bild. Der Wartebildschirm sollte dann darauf hinweisen, die App vor dem Spiegeln
   zu öffnen.

Empfehlung: auf einem Fire-OS-8-Gerät zuerst Weg 2 testen, dann Weg 1; bis dahin bleibt
Weg 3 der sichere Stand. Außerdem gilt ab Android 11: `NetworkInterface`-MAC ist gesperrt
(hier ohnehin nicht benutzt) und Vordergrunddienste brauchen den Typ `connectedDevice`
(gesetzt).

## Bekannte Grenzen

- Der UxPlay-HTTP-Server wacht einmal pro Sekunde auf (`select`-Timeout in `httpd.c`).
  Gemessen: 1 CPU-Tick (10 ms) in 180 s Leerlauf. Nicht verändert, weil Stopp und
  Verbindungsabbau darauf beruhen.
- Nach dem Start ruft Android einmal selbst einen GC auf (nach etwa 60 s), danach im
  Leerlauf keiner mehr (180 s beobachtet).
- Hält eine zweite Empfänger-App Port 7000, weicht diese App aus; beide werden angezeigt.
