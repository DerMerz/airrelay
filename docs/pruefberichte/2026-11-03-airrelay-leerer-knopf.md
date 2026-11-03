# Prüfbericht: AirRelay, leerer weißer Knopf (03.11.2026)

Geprüfter Zweig: `airrelay-leerer-knopf`, Commit `0bea9f5`.
Vergleichsbasis: `git merge-base origin/main 0bea9f5` = `ebed3e6`. Geprüft wurde genau
`git diff ebed3e6 0bea9f5` (11 Dateien, nur Ressourcen, ein neuer Test, ein Bericht; kein
Kotlin-Code in `src/main`).

**Statisch geprüft, nicht gebaut.** In der Cloud gibt es Gradle (`/opt/gradle`) und Java,
aber kein Android-SDK (`ANDROID_HOME` leer). Es wurde kein Gradle-Lauf und kein Test
ausgeführt, es gibt keine Testzahl. Swift ist nicht beteiligt (Kotlin-Projekt). Die
Kontrastwerte wurden mit einem eigenen Python-Skript aus den Farbwerten in `colors.xml`
nachgerechnet; das ist eine Nachrechnung, keine Ausführung des Tests.

## URTEIL

**BEREIT ZUM BUILD**, mit Pflichtpunkten für den lokalen Lauf (Abschnitt "Nur am
Build/Gerät prüfbar"). Kein HOCH-Befund. Die MITTEL-Befunde blockieren den Build nicht,
zwei davon (M1, M3) müssen aber am echten Fire TV entschieden werden, bevor ein Release
daraus wird.

## Befunde

### HOCH

Keine.

### MITTEL

**M1: Ursache nur als Abhängigkeit belegt, der Auslöser auf dem Gerät nicht.**
Belegt mit Datei:Zeile ist, dass Füllung und Textfarbe an zwei getrennten Selektoren hingen
(`ebed3e6:receiver/src/main/res/drawable/tv_button.xml:3-8` weiß bei Fokus,
`ebed3e6:receiver/src/main/res/color/tv_button_text.xml:3-4` dunkel bei Fokus, hellgrau
sonst). Statisch sind beide Selektoren aber synchron (`state_focused` auf demselben View),
die Kreuzpaarung 1,50:1 entsteht im Zustandsmodell nicht von selbst. Der Fix-Bericht sagt
das selbst (`docs/berichte/2026-11-03-airrelay-leerer-knopf.md:36-42`).
Fehlerszenario: War der wahre Auslöser nicht "heller Text auf weißer Fläche", sondern "Text
gar nicht gezeichnet" (Software-Rendering, `AndroidManifest.xml:38`
`hardwareAccelerated="false"`, Fire-OS-Eigenheit), dann zeigt der Stick nach dem Fix eine
dunkle Pille mit Ring und weiterhin ohne Text. Der Fix hilft sicher nur gegen die
Farbhypothese. Gegenprobe am Gerät ist Pflicht (siehe unten, Punkt G1).

**M2: `focusIsVisible` kann bei fehlendem Ring nicht rot werden.**
`receiver/src/test/java/de/merzkevin/airrelay/ui/ButtonLabelTest.kt:76-82` vergleicht nur die
Objektidentität (`d.current === normal`). Jedes `<item>` eines Selektors ist nach
`mutate()` ein eigenes `GradientDrawable`-Objekt.
Fehlerszenario: Zeile `receiver/src/main/res/drawable/tv_button.xml:9` (der `<stroke>`)
oder `drawable/offer_option.xml:9` löschen. Fokussiert und unfokussiert sehen dann identisch
aus (beide nur `chip_fill`), der Test bleibt grün. Er wird nur rot, wenn das ganze
Fokus-Item (`tv_button.xml:5-11`) entfernt wird. Die Zusicherung "Fokus sichtbar" ist damit
nicht wirklich abgesichert. Abhilfe für den Entwickler: Füllfarbe und Strichbreite/-farbe des
fokussierten Zustands mit dem unfokussierten vergleichen statt der Objektidentität.

**M3: Abo-Kärtchen, Fokus und Vorauswahl sind schwer zu unterscheiden (Kaufpfad).**
Vorher war Fokus eine volle weiße Fläche, jetzt ein 3 dp Ring `focus_ring` `#F4F7FC`
(`drawable/offer_option.xml:5-11`). Die Vorauswahl "Jährlich" ist dauerhaft
`isActivated = true` (`MainActivity.kt:195`) und zeigt einen 2 dp Ring `accent` `#7DB8FF`
(`drawable/offer_option.xml:12-18`). Kontrast zwischen den beiden Ringfarben: 1,92:1.
Fehlerszenario: Fokus auf "Monatlich". Auf dem Fernseher sind dann zwei umrandete Kärtchen
nebeneinander, hellblau 2 dp und fast weiß 3 dp. Aus Sofaabstand ist unklar, welches den
Fokus hat; der Kunde drückt OK in der Annahme "Jährlich" und landet im Kaufdialog für
"Monatlich" (Amazon zeigt den Preis noch einmal, also kein Fehlkauf ohne Bestätigung, aber
ein Abbruch- und Vertrauensrisiko). Gleiches gilt allgemein: ein dünner Ring ist auf dem TV
deutlich weniger auffällig als die alte weiße Fläche. Nur am Gerät entscheidbar (G2).

**M4: Gegenprobe nicht im Mutations-Harness.**
`tools/mutation_check.py:20-336` (Liste `MUTATIONS`) enthält keinen Eintrag für die neuen
Absicherungen (weder `tv_button.xml`, `offer_option.xml` noch `themes.xml`). Die Gegenprobe
steht nur als Text im Fix-Bericht (`docs/berichte/...:113-117`).
Fehlerszenario: Ein späterer Umbau macht `ButtonLabelTest` wirkungslos (etwa Filter in
`ButtonLabelTest.kt:142`), niemand merkt es, weil der lokale Mutationslauf diese Stelle
nicht anfasst. Empfehlung: mindestens die zwei Mutanten aus Abschnitt "Testbewertung"
eintragen.

### NIEDRIG

**N1: Zustand "deaktiviert" nicht modelliert.** Alle Zustände in
`ButtonLabelTest.kt:56-66` enthalten `state_enabled`. In der App wird kein Knopf deaktiviert
(`grep isEnabled` in `src/main`: 0 Treffer), und die Textfarben sind jetzt zustandslos
(`themes.xml:46,70,80`, `activity_main.xml:91`), deaktiviert ist also gleich lesbar. Wird
später ein `isEnabled = false` eingeführt mit eigenem Selektor, prüft der Test ihn nicht.

**N2: Kontrastzahlen im Fix-Bericht nicht exakt reproduzierbar.** Bericht
(`docs/berichte/...:67-71`): 6,73 / 5,24 / 4,71. Eigene Nachrechnung mit derselben Formel
und denselben vier Hintergrundstellen, Minimum im Abo-Kasten (`offer_fill` darunter):
`text_primary` 6,81, `text_on_chip` 5,30, `accent_on_chip` 4,76; außerhalb des Kastens 8,42 /
6,56 / 5,89. Alle über 4,5:1, die Abweichung ist harmlos. `accent_on_chip` hat mit 4,76 nur
wenig Reserve.

**N3: Hintergrundmodell ist eine Näherung.** Der Test nimmt den Glow
(`drawable/bg_waiting.xml`, radial, `#402F6BFF`) mit voller Stärke an jeder Stelle an
(`ButtonLabelTest.kt:101`). Das ist die hellste mögliche Stelle, also konservativ, aber
Fire-OS-Overlays oder ein abweichendes Gamma des Fernsehers bildet es nicht ab.

**N4: Fehlermeldung abhängig von der JVM-Locale.** `ButtonLabelTest.kt:122` formatiert mit
der Standard-Locale; die im Fix-Bericht erwartete Meldung "1,50:1" erscheint nur bei
deutscher JVM-Locale, sonst "1.50:1". Kein Funktionsfehler.

## Korrektheit und Kompilierbarkeit (nach Augenschein)

- Gelöschte Ressourcen (`color/tv_button_text.xml`, `color/offer_option_*.xml`, Farben
  `focus_fill`, `focus_text`, `focus_accent`): `grep` über das ganze Repo außerhalb `docs/`
  findet keine Referenz mehr. Kein `R.color.focus_*` in Kotlin oder Tests.
- Neue Farben `focus_ring`, `text_on_chip`, `accent_on_chip` sind in `colors.xml:9,14,15`
  definiert und überall verwendet, wo sie referenziert werden.
- Test: alle Imports werden benutzt (`allWarningsAsErrors = true`,
  `receiver/build.gradle.kts:105`, gilt auch für den Testcode). Signaturen passen:
  `GradientDrawable.getColor()` ab API 24, Test läuft mit `sdk = [34]`.
  `unitTests.isIncludeAndroidResources = true` ist gesetzt (`build.gradle.kts:118`).
- Knopfmenge: `buttonsOf` (`ButtonLabelTest.kt:142`) filtert `isFocusable && isClickable`.
  `licenses_panel` ist fokussierbar, aber als `ScrollView` nicht klickbar, und fällt korrekt
  heraus; die 7 erwarteten IDs stimmen mit dem Layout überein (`activity_main.xml:74, 96,
  111, 122, 241, 246, 292`).
- Randfälle leer, riesig, Abbruch, zweiter Start, offline, Nebenläufigkeit: nicht
  betroffen, der Diff ändert keinen Code und keinen Zustand, nur statische Ressourcen.
- Fokus-Ring und Polsterung: 3 dp Ring liegt innerhalb der Polsterung (Lizenz-Knopf 8 dp
  oben/unten, `activity_main.xml:297-298`; Kärtchen 8 dp, `themes.xml:62-63`). Text wird
  nicht überdeckt.

## Musterprüfung aller Knöpfe

Die Musterliste im Fix-Bericht (`docs/berichte/...:76-92`) wurde gegen das Layout
nachgeprüft, alle Zeilenangaben stimmen. Alle 7 klickbaren Views nutzen `TvButton` oder
`OfferOption` und sind damit vom Fix erfasst. Weitere Selektoren in `res/color`: nur
`status_label.xml` (`state_activated`, kein Knopf, kein Hintergrund-Selektor). Kein
`setTextColor` oder `setBackground*` in `MainActivity.kt`; `HlsActivity.kt:23` setzt nur
Schwarz als Hintergrund, keine Knöpfe. Ergebnis: kein übersehener Knopf mit dem alten Muster.

## Kompatibilität (Pflichtfrage)

**Daten auf den Geräten:** Die einzige persistierte Struktur ist `filesDir/entitlement.txt`
mit den Schlüsseln `schemaVersion`, `status`, `confirmedAtMs` und den Rohwerten
`active/trial/expired/unknown` (`receiver/contract/compat-contract.txt:14-21`). Der Diff
berührt weder `EntitlementCache` noch irgendeinen Kotlin-Code in `src/main`; es gibt keine
SharedPreferences, keine neuen Dateinamen, keine geänderten Enum-Rohwerte. Bestehende Daten
werden unverändert gelesen.

**Installierte Builds:** Ein Update ersetzt nur Ressourcen (Farben, Drawables, Stile). Ressourcen
sind Teil des APK und werden komplett ausgetauscht; gelöschte Ressourcen-IDs haben keine
Außenwirkung, weil keine andere App sie referenziert. `applicationId`, Komponenten,
Notification-Channels, DNS-SD-Typen, AirPlay-Geräte-ID und SKUs sind unverändert.

**Vertragsdatei:** `git diff --quiet ebed3e6 0bea9f5 -- receiver/contract/compat-contract.txt`
liefert Exit 0, die Datei steht nicht in `git diff --name-status`.

Vertrag unveraendert

## Zahlender Kunde

- Kaufpfad: kein Code geändert. `optionYearly/optionMonthly.setOnClickListener` mit
  `Skus.YEARLY/MONTHLY` unverändert (`MainActivity.kt:189-190`).
- Debug-Weichen: keine neue Weiche. `src/debug` und `src/release` (`DebugHooks.kt`) nicht im
  Diff.
- Produkt-IDs: `iap-sku`-Zeilen im Vertrag unverändert (`compat-contract.txt:22-24`).
- `.storekit`: nicht anwendbar (Amazon Appstore IAP, keine StoreKit-Datei im Repo).
- Einziges Risiko für den Kunden: Erkennbarkeit des Fokus im Abo-Angebot, siehe M3.

## Oberfläche

- Texte: keine Strings geändert; `licenses` deutsch "Lizenzen" (`values-de/strings.xml:34`),
  englisch "Licences" (`values/strings.xml:37`). Alle Knopftexte in beiden Sprachen vorhanden.
- Keine Gedankenstriche im Diff (`git diff ebed3e6 0bea9f5 | grep` auf Halbgeviert- und
  Geviertstrich: 0 Treffer). Keine neuen Ortsangaben in UI-Texten (der Fix-Bericht enthält
  "unten rechts" nur als Fotobeschreibung, kein UI-Text).
- Abschneiden: keine Schriftgröße, Breite oder Polsterung geändert, nur der 3 dp Ring
  innerhalb der Polsterung. Der Begriff "kleines SE" passt hier nicht (Fire TV, feste
  Leinwand 960x540 dp); das Gegenstück ist `ScreenFitTest` mit Schriftgrößen bis 2,0
  (`ScreenFitTest.kt:83`), der im lokalen Lauf mitlaufen muss.

## Testbewertung

`ButtonLabelTest` hat vier Tests. Zusicherungen und ob sie rot werden können:

| Zusicherung | Test | Kann rot werden |
|---|---|---|
| Beschriftung nicht leer | `labelsReadableInGerman/English` (`:107-109`) | ja |
| Kontrast mindestens 4,5:1 in jeder Zustandskombination, über Kreuz | `labelsReadable*` (`:111-127`) | ja |
| Knopfmenge fest (7) | `labelsReadable*` (`:96`) | ja |
| Fokus sichtbar | `focusIsVisible` (`:72-85`) | nur bei Löschen des ganzen Fokus-Items, siehe M2 |
| Detektor erkennt die alte Paarung | `detectorSeesWhiteOnWhite` (`:87-91`) | ja, prüft aber nur die Formel, keine App-Ressource |

Konkrete Gegenproben (statisch hergeleitet, nicht ausgeführt):

1. **`receiver/src/main/res/drawable/tv_button.xml:8`** von `@color/chip_fill` auf eine weiße
   Füllung (`#FFF4F7FC`, die alte `focus_fill`) setzen. Dann liegt `text_primary` `#F4F7FC`
   im Fokuszustand auf weißer Fläche, Kontrast 1,00:1 < 4,5. `labelsReadableInGerman` und
   `labelsReadableInEnglish` fallen mit u. a. `licenses_button/licenses_button: text normal
   on fill focused 1.00:1`.
2. **`receiver/src/main/res/values/themes.xml:46`** `@color/text_primary` durch einen
   Selektor mit dunklem Fokustext (alter Stand `tv_button_text` mit `#0A1020`) ersetzen. Die
   Kreuzprüfung legt den dunklen Text auf `chip_fill` über dem dunklen Hintergrund, rund
   1,4 bis 2,1:1. Beide `labelsReadable*` fallen für alle fünf `TvButton`-Knöpfe.
3. **`receiver/src/main/res/layout/activity_main.xml:91`** `accent_on_chip` zurück auf
   `accent` `#7DB8FF`: auf `chip_fill` im Abo-Kasten über Glow 3,54:1 (eigene Rechnung)
   < 4,5. Beide `labelsReadable*` fallen für `option_yearly/option_yearly_compare` in allen
   Zuständen.
4. **`receiver/src/main/res/drawable/tv_button.xml:9`** (Ring) löschen: Test bleibt grün.
   Das ist Befund M2.

Kein Überspringen bei fehlender Fixture: der Test nutzt keine Fixture, kein `assume`/`@Ignore`.

## Nur am Build oder Gerät prüfbar

Für den lokalen Lauf, in dieser Reihenfolge:

- **B1** `./gradlew :receiver:testDebugUnitTest` komplett grün, Zahl der Tests nennen;
  `ButtonLabelTest` muss "7 buttons" in beiden Sprachen ausgeben (`ButtonLabelTest.kt:129`).
- **B2** Kompilierung mit `allWarningsAsErrors` für den neuen Test (Warnungen wären Fehler).
- **B3** `lint` (`abortOnError = true`, `build.gradle.kts:108-112`) nach dem Löschen der vier
  Farb-Selektoren.
- **B4** Gegenproben 1 und 2 aus der Testbewertung tatsächlich fahren und rot sehen; danach
  zurücksetzen.
- **B5** `ScreenFitTest` grün (3 dp Ring, Schriftgröße 2,0).
- **G1** Echter Fire TV Stick, Startbildschirm "Bereit zum Spiegeln": Lizenz-Knopf mit Fokus
  zeigt lesbaren Text "Lizenzen". Vorher möglichst mit dem alten Build den weißen leeren
  Knopf reproduzieren, sonst bleibt M1 offen.
- **G2** Abo-Angebot am Fernseher aus Sofaabstand: Fokus auf "Monatlich" eindeutig von der
  Vorauswahl "Jährlich" unterscheidbar (M3); Fokus allgemein gut sichtbar mit nur 3 dp Ring.
- **G3** Fire OS 7 und Fire OS 8, 1080p und 4K-Ausgabe; Fenster ohne Fokus (z. B. während
  des Amazon-Kaufdialogs oder des Fire-OS-8-Hinweises) prüfen, ob der Text lesbar bleibt.
- **G4** Gedrückter Zustand (OK auf der Fernbedienung halten) und Lizenzansicht öffnen und
  schließen: Fokus kehrt sichtbar auf den Lizenz-Knopf zurück (`MainActivity.kt:566-567`).
