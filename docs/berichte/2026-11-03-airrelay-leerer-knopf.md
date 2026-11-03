# AirRelay: leerer weißer Knopf auf dem Startbildschirm (03.11.2026)

Zweig: `airrelay-leerer-knopf`. Kein Release, kein Upload zu Amazon.

## Befund

Foto vom Fernseher: Startbildschirm "BEREIT ZUM SPIEGELN", unten rechts ein weißer,
pillenförmiger Knopf ohne sichtbare Beschriftung.

Der Knopf ist `licenses_button` ("Lizenzen" / "Licences"),
`receiver/src/main/res/layout/activity_main.xml:291-302` (Stand vor dem Fix). Er ist auf dem
Bereit-Bildschirm der einzige Knopf und bekommt dort immer den Fokus
(`MainActivity.kt:349-350`). Weiß ist er nur im fokussierten Zustand.

## Ursache

Hintergrund und Textfarbe des Knopfs hingen an zwei getrennten Selektoren, die nur zusammen
lesbar waren:

| Datei:Zeile (vor dem Fix) | Inhalt |
|---|---|
| `res/drawable/tv_button.xml:3-8` | fokussiert: Füllung `focus_fill` = `#F4F7FC` (weiß) |
| `res/drawable/tv_button.xml:9-14` | sonst: Füllung `chip_fill` = 12 % Weiß auf dunkelblau |
| `res/color/tv_button_text.xml:3` | fokussiert: Text `focus_text` = `#0A1020` (fast schwarz) |
| `res/color/tv_button_text.xml:4` | sonst: Text `text_secondary` = `#C3CCDD` (hellgrau) |
| `res/values/themes.xml:39,46` | Stil `TvButton` verbindet beide |

Die Beschriftung war nur lesbar, solange beide Selektoren denselben Zustand zeigen. Jede
Kreuzpaarung ist praktisch unsichtbar:

| Text | auf Füllung | Kontrast |
|---|---|---|
| `text_secondary` `#C3CCDD` (nicht fokussiert) | weiß `#F4F7FC` (fokussiert) | **1,50:1** |
| `focus_text` `#0A1020` (fokussiert) | `chip_fill` auf Hintergrund (nicht fokussiert) | 1,38 bis 2,11:1 |

Die erste Zeile ist genau das Fotobild: weiße Pille, hellgrauer Text darauf, auf dem
Foto nicht zu sehen. Leerer String, fehlende deutsche Übersetzung und Abschneiden sind
ausgeschlossen: `values-de/strings.xml:34` "Lizenzen", `values/strings.xml:37` "Licences",
Breite `wrap_content`, ScreenFitTest prüft das Abschneiden.

Ehrlich offen: Welcher Auslöser auf dem echten Stick die beiden Selektoren auseinanderlaufen
lässt, ist ohne Gerät nicht nachgewiesen. Statisch passen sie zusammen; Robolectric zeichnet
nicht wie der Stick (die Activity läuft mit `android:hardwareAccelerated="false"`,
`AndroidManifest.xml:38`, Fire-OS-Eigenheiten). Der Fix beseitigt deshalb nicht einen
vermuteten Auslöser, sondern die Abhängigkeit selbst: Die Textfarbe hängt an keinem Zustand
mehr, also kann keine Zustandskombination die Beschriftung verstecken.

Zusätzlich lag schon der nicht fokussierte Zustand knapp unter 4,5:1:
`text_secondary` auf `chip_fill` über der hellsten Stelle des Hintergrunds (Glow) 4,47:1.

## Fix

- Textfarbe in jedem Zustand gleich: `TvButton` jetzt `text_primary` `#F4F7FC`
  (`themes.xml:46`).
- Fokus wird durch einen 3 dp Ring `focus_ring` gezeigt, die Füllung bleibt `chip_fill`
  (`drawable/tv_button.xml`). Gedrückt: wie fokussiert bzw. normal, Text unverändert.
- Abo-Kärtchen (`drawable/offer_option.xml`) nach demselben Prinzip: fokussiert Ring statt
  weißer Fläche, vorausgewählt (activated) weiterhin Akzent-Rand.
- Texte in den Kärtchen zustandslos: Titel `text_primary`, Preiszeile `text_on_chip`
  `#D5DCE8`, Vergleichszeile `accent_on_chip` `#AFD4FF` (`values/colors.xml`,
  `themes.xml:70,80`, `activity_main.xml:91`).
- Entfernt, weil unbenutzt: `color/tv_button_text.xml`, `color/offer_option_title.xml`,
  `color/offer_option_detail.xml`, `color/offer_option_compare.xml`, Farben `focus_fill`,
  `focus_text`, `focus_accent`.

Kontraste nachher (Minimum über `bg_top`, `bg_bottom`, beide mit Glow, im Abo-Kasten
zusätzlich mit `offer_fill`), in jedem Zustand gleich:

| Text | Kontrast |
|---|---|
| `text_primary` auf `chip_fill` | 6,73:1 |
| `text_on_chip` auf `chip_fill` | 5,24:1 |
| `accent_on_chip` auf `chip_fill` | 4,71:1 |

Nicht geändert: keine persistierten Schlüssel, Dateinamen, SKUs oder Netzwerkprotokoll-Werte.
Nur Ressourcen (Farben, Drawables, Stile) und ein neuer Test.

## Musterliste (alle Knöpfe und Fokuszustände)

| Stelle | Ergebnis |
|---|---|
| `licenses_button` (Layout 291-302, TvButton) | **betroffen** (gemeldeter Knopf), behoben |
| `retry_button` (Layout 240-243, TvButton) | gleiches Muster, behoben |
| `network_button` (Layout 245-249, TvButton) | gleiches Muster, behoben |
| `restore_button` (Layout 110-118, TvButton) | gleiches Muster, behoben |
| `offer_retry` (Layout 121-126, TvButton) | gleiches Muster, behoben |
| `option_yearly` mit Titel, Preis, Vergleich (Layout 73-93, OfferOption) | gleiches Muster (weiße Fokusfläche + Text-Selektoren über `duplicateParentState`), behoben; außerdem nicht fokussiert Vergleichszeile 3,50:1 und Preiszeile 4,47:1, behoben |
| `option_monthly` (Layout 95-108, OfferOption) | wie `option_yearly`, behoben |
| `licenses_panel` (ScrollView, Layout 360-372) | fokussierbar, aber kein Knopf und ohne Fokusstil, Text fest auf festem Hintergrund: nicht betroffen |
| `stop_hint` (Layout 327-341) | nicht fokussierbar, fester Text auf fester Pille: nicht betroffen |
| `status_label` (`color/status_label.xml`) | Selektor nur `state_activated`, kein Knopf, kein Hintergrund-Selektor: nicht betroffen |
| `StepNumber` (`themes.xml:16-25`) | nicht fokussierbar: nicht betroffen |
| `wifi_hint`, `wifi_hint_video` | `focusable=false`: nicht betroffen |
| `HlsActivity` | keine Knöpfe (nur Videofläche) |
| Toasts, Amazon-Kaufdialog | System bzw. Amazon, nicht unsere Ressourcen, nicht geprüft |

## Test

Neu: `receiver/src/test/java/de/merzkevin/airrelay/ui/ButtonLabelTest.kt` (Robolectric, echtes
Layout, echte Strings, Deutsch und Englisch):

- findet alle fokussierbaren, klickbaren Views und pinnt die Menge (7 Knöpfe), ein neuer
  oder verschwundener Knopf fällt auf,
- Beschriftung nicht leer,
- jede Textfarbe in jedem Zustand gegen jede Füllung in jedem Zustand (über Kreuz, 9
  Zustände inklusive fokussiert, gedrückt, activated, Fenster ohne Fokus) über vier
  Hintergrundstellen: mindestens 4,5:1. Damit ist "Text = Hintergrund im fokussierten
  Zustand" ebenso abgedeckt wie das Auseinanderlaufen der Selektoren,
- Fokus sichtbar: fokussierter Hintergrund unterscheidet sich vom unfokussierten,
- Selbsttest des Detektors mit der alten Paarung (1,50:1 muss als Fehler erkannt werden).

**Gradle: nicht gebaut.** Die Cloud-Umgebung hat kein Android-SDK, und `dl.google.com` wird
vom Proxy abgelehnt (403), das SDK lässt sich also nicht nachladen. Es gibt keine Testzahl
aus einem Lauf. Ersatzweise wurde die Kontrastlogik des Tests mit denselben Farben in Python
nachgerechnet: alter Stand 48 von 88 Paarungen unter 4,5:1 (darunter
`licenses_button` 1,50:1), neuer Stand 0 von 24. Das ist keine Ausführung des Tests.

Gegenprobe (auszuführen mit SDK): Fix zurücknehmen
(`git revert` des Fix-Commits ohne die Testdatei, oder nur `drawable/tv_button.xml` und
`themes.xml:46` zurück auf `focus_fill`/`tv_button_text`), dann
`./gradlew :receiver:testDebugUnitTest --tests '*ButtonLabelTest*'`. Erwartet rot mit u. a.
`de licenses_button/licenses_button: text normal on fill focused 1,50:1`. Mit Fix grün.

Lauf vor dem Mergen:
`./gradlew :receiver:testDebugUnitTest` (alle Unit-Tests, auch ScreenFitTest wegen des
3-dp-Rings, der innerhalb der bestehenden Polsterung liegt).

## Nicht geprüft

- Echter Fire TV Stick: weder der alte Fehler reproduziert noch der neue Fokus-Ring auf dem
  Fernseher angesehen (Sichtbarkeit des Rings aus Sofaabstand, Fire OS 7 und 8).
- Der konkrete Auslöser auf dem Stick, der die beiden Selektoren auseinanderlaufen ließ.
- Der Build und alle Unit-Tests (kein SDK in der Cloud).
- Amazon-Kaufdialog und System-Toasts.
