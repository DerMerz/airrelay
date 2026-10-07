# AirRelay

Schlanker Empfänger für die Bildschirmsynchronisierung von iPhone und iPad auf Fire TV
(Fire OS, Android). Nur Spiegelung plus Ton. Kein Video-Weiterreichen, keine Einstellungen,
kein Bild im Bild. Bedienung nur mit der Fernbedienung.

Paketname `de.merzkevin.airrelay` (Variable `appId` in `receiver/build.gradle.kts`).
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

Ergebnis: `receiver/build/outputs/apk/release/receiver-release.apk`. Ohne Upload-Schlüssel
wird mit dem lokalen Debug-Schlüssel signiert: installierbar zum Messen, nie für den Store.

Store-Build: `tools/build_release.sh`. Das Skript holt das Keystore-Passwort aus dem
macOS-Schlüsselbund, baut mit `AIRRELAY_REQUIRE_UPLOAD_KEY=1` (ohne Upload-Schlüssel bricht
Gradle ab, kein Rückfall auf den Debug-Schlüssel) und prüft danach mit `apksigner`, dass die
APK mit dem Upload-Zertifikat signiert ist, dass keine App-Tester-Datei darin liegt und dass
`AppstoreAuthenticationKey.pem` darin liegt (fehlt sie, bricht es ab; nur für Testbuilds
übergehbar mit `AIRRELAY_ALLOW_MISSING_PEM=1`).

Letzter Store-Build (07.10.2026, Version 0.1.0, versionCode 1): `receiver-release.apk`,
5.532.575 Byte (5,53 MB), SHA-256 `39972613c41fa7feaf47b9167880bf25592d87a9b8533620e1b276e29bd42ab4`,
signiert mit dem Upload-Zertifikat (`apksigner`: v2, ein Signierer, RSA 4096), ABIs `arm64-v8a`
und `armeabi-v7a`, minSdk 24, targetSdk 36, `assets/AppstoreAuthenticationKey.pem` darin,
keine App-Tester-Datei. Auf dem Stick Lite installiert und gestartet (Dienst läuft auf Port 7000).

Upload-Schlüssel (RSA 4096, 30 Jahre, Alias `airrelay`, Zertifikat SHA-256
`9b305bbd…e449c8`):
- Keystore: `~/Keys/airrelay/airrelay-upload.jks`, Kopie in iCloud Drive unter
  `Keys/airrelay/airrelay-upload.jks`.
- Passwort: macOS-Schlüsselbund, Dienst `airrelay-upload-keystore`, Account `kevin`
  (`security find-generic-password -s airrelay-upload-keystore -a kevin -w`).
- Gradle liest das Passwort aus `AIRRELAY_UPLOAD_KEYSTORE_PASSWORD` oder aus
  `local.properties` (`storePassword`, nicht im Repo), nie aus einer Datei im Repo.

Achtung Testgerät: bisher installierte Builds sind mit dem Debug-Schlüssel signiert. Eine mit
dem Upload-Schlüssel signierte APK lässt sich darüber nicht installieren, vorher
`adb uninstall de.merzkevin.airrelay`. Das löscht nur die zwischengespeicherte Abo-Datei.

`packageDebug`/`packageRelease` hängen an den Unit-Tests. Ist ein Test rot (auch der
Kompatibilitätsvertrag), entsteht keine APK. Ein Build direkt aus Android Studio mit
"Run" geht denselben Weg; nur ein von Hand gepacktes APK liefe daran vorbei.

Tests: `./gradlew :receiver:testDebugUnitTest`. Gegenprobe (jede Absicherung einmal
herausnehmen, Tests müssen rot werden): `python3 tools/mutation_check.py`.

## Aufbau

```
receiver/src/main/cpp/            native Bibliothek libairrelay_native.so
  native_bridge.cpp               JNI, feste Konfiguration (neu)
  android_raop_callbacks.c        8 Rückrufe nach Kotlin, Rest nativ beantwortet
  android_dnssd_shim.c            TXT-Records für NsdManager (jqssun)
  audio_*.h/.cpp, timeline_buffer.h  Audio-Engine (jqssun), plus Software-Lautstärke
  patches/UxPlay/                 6 Patches aus jqssun, beim Bau angewendet
receiver/src/main/java/de/merzkevin/airrelay/
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

Freischaltung: `accessFor` in `core/Subscription.kt` (64 Fälle, Tabelle
`access-table.txt`) entscheidet aus Abo-Status, Erreichbarkeit von Amazon, Herkunft und Alter
des gespeicherten Status und einem abgewiesenen Sender. `LicenseGate` hält das Ergebnis für
die nativen Threads, `screenFor` zeigt bei `LOCKED` den Abo-Kasten.

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
- Gespeichert wird genau eine Datei: der zuletzt von Amazon bestätigte Abo-Status
  (`filesDir/entitlement.txt`, siehe "Abo"). Keine Einstellungen. Die AirPlay-Identität wird
  aus `ANDROID_ID` abgeleitet (stabil pro Installation).

## Abo (Amazon In-App Purchasing)

Ein Abo, zwei Laufzeiten, je 7 Tage Probezeit über Amazon (einmal pro Kunde, Amazon setzt
das durch). Ohne aktives Abo oder Probezeit keine Spiegelung. Kein eigener Server: die
Kaufprüfung läuft nur auf dem Gerät (Appstore SDK prüft die signierten Antworten mit
`AppstoreAuthenticationKey.pem`), keine Receipt Verification Service.

| | SKU | Preis |
|---|---|---|
| Eltern-SKU | `de.merzkevin.airrelay.pro` | |
| Laufzeit monatlich | `de.merzkevin.airrelay.pro.monthly` | 2,49 €, 7 Tage gratis |
| Laufzeit jährlich | `de.merzkevin.airrelay.pro.yearly` | 14,99 €, 7 Tage gratis |

Die SKUs stehen in `core/Subscription.kt` (`Skus`) und im eingefrorenen Vertrag: eine
umbenannte SKU verliert jeden bestehenden Abonnenten.

SDK: `com.amazon.device:amazon-appstore-sdk:3.0.9` (Maven Central, 460 KB jar, keine
Abhängigkeiten, kein eigenes Manifest). R8-Regeln wie von Amazon verlangt
(`-keep class com.amazon.** {*;}`), das kostet 0,4 MB dex. Manifest: `ResponseReceiver`,
`PurchasingService`, `<queries>` für Appstore und App Tester.

Ablauf:
- `registerListener` beim ersten Start (Dienst oder Activity), `getUserData`,
  `getProductData` (Eltern-SKU plus Laufzeiten) und `getPurchaseUpdates(true)` bei jedem
  `onResume`. Immer `reset = true`: der Status wird aus der vollständigen Belegliste
  abgeleitet, Belege werden nicht gespeichert. Nicht während einer laufenden Spiegelung
  (kostet auf dem Stick Lite bis zu 113 ms Hauptthread beim Nach-vorn-holen), dann wird es
  nach dem Ende nachgeholt.
- Kauf mit `purchase(termSku)`. Belege werden nach Beleg-ID zusammengeführt, eine
  stornierte Kopie gewinnt, `cancelDate` in der Zukunft läuft noch.
  `notifyFulfillment(receiptId, FULFILLED)` einmal pro Beleg und Prozess; andere Werte sind
  verboten (sie erstatten sofort, `GuardsExistTest` prüft das).
- "Kauf wiederherstellen" ist `getPurchaseUpdates(true)` mit sichtbarem Ergebnis.
- Amazon antwortet nicht binnen 15 s: gilt als nicht erreichbar, mit "Erneut versuchen".
  Eine spätere Antwort wird trotzdem übernommen.
- "7 Tage gratis" steht nur da, wenn Amazon diesem Kunden die Probezeit anbietet
  (`Product.getFreeTrialPeriod()` kommt nur dann). Knopftext ist Leistung plus Preis, kein
  Kaufbefehl. Preise kommen von Amazon (Marktplatzformat); "1,25 € im Monat, 49 %
  günstiger" wird daraus berechnet und abgerundet, bei unlesbarem Preis weggelassen.
- Offline: ein von Amazon bestätigtes Abo gilt bis zu 3 Tage ab der letzten Bestätigung,
  danach Sperre mit Hinweis. Eine Uhr, die mehr als 10 min vor der Bestätigung steht,
  gewährt keine Frist.

Ablehnung ohne Abo: UxPlay fragt beim ersten SETUP (nach Pairing und FairPlay, bevor ein
Stream existiert) `report_client_request`; `android_raop_callbacks.c` fragt Kotlin
(`onClientRequest`, schlägt im Zweifel zu), Patch `0007` antwortet dann `403 Forbidden`
statt eines leeren `200 OK`. Das iPhone zeigt seine eigene Fehlermeldung, auf dem Fernseher
kommt die App mit dem Abo-Kasten und dem Satz "Ein iPhone oder iPad wollte gerade …" nach
vorn. Eine zugelassene Sitzung läuft zu Ende, auch wenn das Abo währenddessen ausläuft.

Gespeichert (`filesDir/entitlement.txt`, Schlüssel=Wert, handgeschriebener toleranter
Decoder): `schemaVersion`, `status` (`active`/`trial`/`expired`/`unknown`),
`confirmedAtMs`. Unbekannte Schlüssel einer neueren Version bleiben beim Schreiben erhalten.
Schreiben: temporäre Datei, sync, alte Datei als `.bak`, umbenennen, zurücklesen. Ein
Lesefehler gilt als "unbekannt" (dann wird Amazon gefragt), nie als "kein Abo"; eine
kaputte Datei wird nach `entitlement.txt.corrupt-<zeit>` verschoben (höchstens 3), nie
gelöscht. Eingefrorene Altdateien: `src/test/resources/.../fixtures/`.

Debug-Build ohne Amazon (Zustände und Meldungen ansehen, Preise sind erfunden):
```
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_subscription EXPIRED
# ACTIVE | EXPIRED | EXPIRED_NO_TRIAL | EXPIRED_NO_PRICES | CHECKING | UNREACHABLE | OVERDUE | OVERDUE_IN_GRACE
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_message PURCHASE_CANCELLED
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_locale en   # "system" zurück
```
Die Debug-Zuspielung fragt vorher dieselbe Freischaltung wie ein iPhone-SETUP.

### Amazon-Konto, Stand 07.10.2026

1. Erledigt: in der Konsole unter der App "AirRelay" das Abo, Eltern-SKU
   `de.merzkevin.airrelay.pro`, Laufzeiten `de.merzkevin.airrelay.pro.monthly` (2,49 €) und
   `de.merzkevin.airrelay.pro.yearly` (14,99 €), jeweils 7 Tage Probezeit, Status Approved.
   Die SKUs müssen Zeichen für Zeichen so heißen wie im Code (`Skus`).
2. Erledigt: `AppstoreAuthenticationKey.pem` (öffentlicher Schlüssel, 451 Byte) liegt in
   `../store/amazon/` und, vom Skript `tools/build_release.sh` übernommen, in
   `receiver/src/main/assets/` (im Repo, die APK enthält sie: `unzip -l` zeigt
   `assets/AppstoreAuthenticationKey.pem`).
3. Offen: die Preise sehen und kaufen lassen. Eine per `adb install` geladene APK kennt
   Amazon nicht: Der Appstore antwortet auf `getUserData`, `getProductData` und
   `getPurchaseUpdates` mit `FAILED` (am 07.10.2026 auf dem Stick Lite gemessen), die App zeigt
   dann "Amazon ist nicht erreichbar" mit "Erneut versuchen". Preise und Kauf gibt es nur über
   Live App Testing (der Appstore kennt die App dann) oder, mit dem Debug-Build, über App Tester
   (siehe unten).

### Kauf testen mit dem Amazon App Tester

Kein Entwicklerkonto nötig, aber der Stick verlangt für die Installation die
Kindersicherungs-PIN (am 30.09.2026 daran gescheitert). App Tester arbeitet nur mit dem
Debug-Build:
```
# App Tester im Appstore des Sticks installieren (PIN), dann:
adb shell appops set com.amazon.sdktestclient SYSTEM_ALERT_WINDOW allow
adb shell setprop debug.amazon.sandboxmode debug
adb push tools/amazon/amazon.sdktester.json /sdcard/amazon.sdktester.json
adb install -r receiver/build/outputs/apk/debug/receiver-debug.apk
# danach: adb shell setprop debug.amazon.sandboxmode none
```
`tools/amazon/amazon.sdktester.json` liegt bewusst außerhalb von `src/`, `build_release.sh`
prüft, dass sie nicht in der APK landet. Sobald die Abo-Artikel in der Konsole stehen,
durch den JSON-Export der Konsole ersetzen (der Wert `term` für die Jahreslaufzeit ist
nicht gegen Amazon geprüft).

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

Mit Abo (30.09.2026, Release mit Upload-Schlüssel, Stick Lite): APK 5,53 MB (dex 0,46 MB,
davon rund 0,4 MB Appstore SDK), PSS im Vordergrund nach 60 s 13,3 MB (Stand davor am selben
Tag 13,0 MB). Zeit bis zum ersten Bild aus dem Hintergrund (Debug-Zuspielung, je 7 Läufe):
Median 1,83 s mit Abo-Code, 1,91 s mit dem Stand davor; die Streuung (1,06 bis 2,67 s) hat
der Stick an diesem Tag unabhängig davon.

Tests: 115 JVM-Tests. Gegenprobe mit `tools/mutation_check.py`: 72 Absicherungen einzeln
entfernt, jede macht die Tests rot (dabei gefunden und geschlossen: ein Dienst, der jeden
Sender zulässt, fiel dem Wächter nicht auf). Build-Sperre in beiden Richtungen geprüft: mit
gebrochenem Vertrag (Abo-Datei in anderem Verzeichnis) endet `assembleRelease` mit 1 und es
entsteht keine APK, mit heilem mit 0.

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
adb shell mkdir -p /sdcard/Android/data/de.merzkevin.airrelay/files
adb push land60.h264 /sdcard/Android/data/de.merzkevin.airrelay/files/
# App im Vordergrund:
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_feed land60.h264 --ei debug_fps 60
# App im Hintergrund (prüft das Nach-vorn-holen):
adb shell am broadcast -a de.merzkevin.airrelay.DEBUG_FEED -n de.merzkevin.airrelay/.DebugFeedReceiver \
  --es debug_feed land60.h264 --ei debug_fps 60
# Fehlerbildschirm zeigen, ohne das Netz zu trennen:
adb shell am start -n de.merzkevin.airrelay/.MainActivity --es debug_status NO_NETWORK
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
auf dem Stick geprüft. Ab Android 10 ignoriert das System solche Starts aus dem Hintergrund.

Eingebaut (`needsFullScreenAlert`, ab API 29): der Dienst versucht den Start wie bisher und
zeigt zusätzlich eine Benachrichtigung mit Full-Screen-Intent (`USE_FULL_SCREEN_INTENT`,
Kanal `open_request`, Kategorie CALL, verschwindet nach 60 s). Kommt die App doch nach vorn,
nimmt sie die Benachrichtigung sofort weg. Gilt für "iPhone spiegelt" und für "iPhone ohne Abo
abgewiesen". Auf Fire OS 7 ändert sich nichts: kein Kanal, keine Benachrichtigung.

UNGEPRÜFT: es gibt kein Fire-OS-8-Gerät. Ob die Fire-TV-Oberfläche einen Full-Screen-Intent
als Vollbild startet oder nur als Hinweis zeigt, weiß erst ein Test auf dem Gerät. Im
schlechtesten Fall läuft der Ton, und die Benachrichtigung öffnet das Bild. Weitere Wege,
falls nötig: "Über anderen Apps einblenden" (`SYSTEM_ALERT_WINDOW`, per adb
`appops set <appId> SYSTEM_ALERT_WINDOW allow`, für Kundinnen und Kunden kaum erreichbar).
Ab Android 11 gilt außerdem: Vordergrunddienste brauchen den Typ `connectedDevice` (gesetzt).

## Fire-TV-Einreichungskriterien (Amazon Test Criteria, Stand 18.09.2026)

| Kriterium | Stand |
|---|---|
| `uses-feature android.hardware.touchscreen required=false` | erfüllt |
| `LEANBACK_LAUNCHER`, `uses-feature android.software.leanback required=false` | erfüllt |
| Banner (`android:banner`) | erfüllt (`drawable-xhdpi/banner.png`), Größe nicht gegen Amazon geprüft |
| Nur mit D-Pad bedienbar, sichtbarer Fokus, keine Fokusfalle | erfüllt, auf dem Stick durchgegangen (Abo-Kasten, Lizenzen, Fehler) |
| Keine Touch-Elemente | erfüllt |
| Zurück: kein Absturz, keine Schleife | erfüllt (`backActionFor`, 36 Fälle) |
| Home führt zum Launcher, App hängt nicht | erfüllt (Home beim Spiegeln: Ton läuft weiter) |
| Keine Google-Dienste | erfüllt, keine Google-Bibliothek, keine AndroidX |
| Keine verbotenen Berechtigungen (Telefonstatus, Standort, Mikrofon, Kamera) | erfüllt |
| Querformat, 90 % Fläche, Textgröße ab 14 sp | erfüllt (kleinster Text 15 sp) |
| Ladeanzeige über 15 s | erfüllt ("Abo wird geprüft …", nach 15 s Ausgang "Erneut versuchen") |
| Daten und Käufe überstehen ein Update | erfüllt (Kauf liegt bei Amazon, Status-Datei tolerant, Vertragstest) |
| Schreiblast unter 2 MB/h im Hintergrund | erfüllt (eine Datei unter 100 Byte bei Statusänderung) |
| targetSdk (Fire OS 8 verlangt 30) | erfüllt (36) |
| 64 bit: arm64-v8a und armeabi-v7a | erfüllt |
| APK-Signaturschema v2 | erfüllt (`apksigner verify`: v2; v1 entfällt bei minSdk 24) |
| minSdk 22 für alle Fire-TV-Geräte | NICHT erfüllt: minSdk 24 (Fire OS 6 und neuer). Fire OS 5 (Fire TV 1./2. Generation) bewusst ausgelassen |
| IAP: Preis und Produkt aus der IAP-API, kein eigener Bestätigungsdialog vor dem Kauf | erfüllt (keine eigene Kaufbestätigung, nur Wiederherstellung und Abbruch zeigen eigene Meldungen) |
| `AppstoreAuthenticationKey.pem` in `assets/` | erfüllt (451 Byte, in der APK nachgewiesen) |

## Testliste für Kevin (iPhone, nur so prüfbar)

Stand 07.10.2026. Auf deinem Stick liegt die Store-Fassung 0.1.0 (mit dem Upload-Schlüssel
signiert, die Fassung, die zu Amazon geht). Der Gerätename des Sticks ist wieder
"Kevins 2. FireTVStick". Die Preise siehst du erst, wenn Amazon die App kennt (Punkt 4).

1. App auf dem Stick öffnen. Erst steht oben ein Kasten "Abo wird bei Amazon geprüft …",
   nach etwa 15 Sekunden "Amazon ist nicht erreichbar" mit dem Knopf "Erneut versuchen".
   Darunter der Name deines Sticks und die drei Schritte, unten "Lizenzen". Das ist
   richtig, solange die App nicht über Amazon installiert wurde. Es dürfen keine Preise und
   kein Absturz zu sehen sein.
2. Am iPhone Kontrollzentrum, "Bildschirmsynchronisierung", deinen Stick wählen. Das iPhone
   meldet binnen weniger Sekunden, dass es nicht verbinden konnte (kein halbes Bild, kein
   Hängen). Am Fernseher steht im Kasten "Ein iPhone oder iPad wollte gerade auf diesen
   Fernseher spiegeln. Dafür brauchst du ein aktives Abo." Einmal mit der App im Vordergrund,
   einmal nachdem du Home gedrückt hast (dann kommt die App von selbst nach vorn).
3. Dasselbe mit Musik statt Spiegeln (AirPlay-Ton): ebenfalls abgewiesen, kein Ton aus dem
   Fernseher.
4. Über Live App Testing installieren (Beta Hub auf dem Stick). Der Kasten oben zeigt jetzt
   "7 Tage gratis testen" und zwei Wahlknöpfe: "Jährlich, 7 Tage gratis, dann 14,99 €/Jahr"
   (darunter "1,25 € im Monat, 49 % günstiger") und "Monatlich, 7 Tage gratis, dann
   2,49 €/Monat", dazu "Kauf wiederherstellen". Kaufen (im Test kostet es nichts). Danach
   verschwindet der Kasten, der Schirm zeigt "Bereit zum Spiegeln", und Spiegeln vom iPhone
   klappt mit Bild und Ton wie gewohnt.
5. Abo aktiv, den Stick vom Internet trennen (WLAN ohne Internet): Spiegeln geht weiter.
   Nach mehr als 3 Tagen ohne Bestätigung steht dort "Dein Abo muss bestätigt werden".
6. Beim Spiegeln zweimal Zurück: erst erscheint der Hinweis "Zum Beenden der Spiegelung noch
   einmal Zurück drücken", dann ist die Spiegelung zu Ende und der Wartebildschirm da. Beim
   Spiegeln Home drücken: der Ton läuft weiter, und beim Zurückkehren ist das Bild sofort wieder da.

## Bekannte Grenzen

- Der UxPlay-HTTP-Server wacht einmal pro Sekunde auf (`select`-Timeout in `httpd.c`).
  Gemessen: 1 CPU-Tick (10 ms) in 180 s Leerlauf. Nicht verändert, weil Stopp und
  Verbindungsabbau darauf beruhen.
- Nach dem Start ruft Android einmal selbst einen GC auf (nach etwa 60 s), danach im
  Leerlauf keiner mehr (180 s beobachtet).
- Hält eine zweite Empfänger-App Port 7000, weicht diese App aus; beide werden angezeigt.
