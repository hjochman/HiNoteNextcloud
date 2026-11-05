# Eigene Notiz-App für das Huawei Paper: Neubau-Plan

Stand: 2026-11-05. Dieses Dokument löst Teilaufgabe 7 aus `hinote-nextcloud-plan.md` ab. Befunde und Belege stehen in `tmp/FINDINGS.md`. Was dort nicht belegt ist, steht hier als **Annahme** oder **offen**.

## 1. Ziel

Eine eigene App für das Huawei Paper (HMW-W09, Android 10, kein Root), die HiNote im Alltag ersetzt:

- gleicher Funktionsumfang und ähnliche Bedienung wie HiNote,
- flüssiger Stift durch die E-Ink-Klassen des Geräts (`HWEditView`),
- Sync mit Nextcloud, PDF-Export, ein Nextcloud-Plugin zum Anzeigen,
- einmalige Übernahme der 33 vorhandenen HiNote-Notizen.

Entscheidung des Nutzers: "wir schreiben selber". xnotes und Saber sind keine Basis (laut Nutzer auf dem Paper sehr träge).

## 2. Was technisch belegt ist

| Thema | Befund | Beleg |
|---|---|---|
| Schreiben | `HWEditView` läuft in einer Fremd-App ohne Root, ohne `<uses-library>`, mit `targetSdk 29`. Latenz laut Nutzer wie HiNote. | Spike 1 |
| Eigene Ebene | `HWEditView` liegt transparent über einer eigenen View. | Spike 2 |
| Speichern | Tracks (Typ, Breite, Farbe, Punkte mit x, y, Zeit, Druck) lassen sich verlustfrei als JSON speichern und wieder laden. | Spike 2 |
| Radierer | Schnell nur mit `OnEraserPrepareListener` und `FBPicture.bindColor(WHITE)`. Dann fehlt der Radierkreis. | Spike 2, Gegenprobe |
| Lasso, Undo, Redo | Funktionieren. Lasso laut Nutzer etwas träge, wie HiNote. | Spike 2 |
| Handschrifterkennung | Die Huawei-Engine lädt, lehnt die App aber ab: `The app isn't in white list`. | Spike 3 |

Einschränkungen von `HWEditView`:
- `setTrackList()` und `clear()` leeren den Undo-Verlauf. Die App führt ihren eigenen Verlauf.
- Kein Zoom, kein Scrollen. Die App rechnet die Tracks um und setzt sie neu. Das Gefühl bei vielen Strichen ist nicht gemessen.
- `HWEraserView` ruft `setZOrderOnTop(true)` auf. Dialoge und Menüs über der Schreibfläche sind nicht getestet.
- Der Radierkreis fehlt im Schnellpfad. Die App müsste ihn selbst zeichnen.

## 3. Offene Entscheidungen für den Nutzer

1. **Name und Paketname** der App. Vorschlag für die Arbeit: `local.papernotes`, Anzeigename "PaperNotes". Beides ist leicht änderbar, solange nichts veröffentlicht ist.
2. **Kotlin oder Java.** Vorschlag: Kotlin. Die Spikes sind Java, die Stubs funktionieren mit beidem.
3. **Speicherort.** Variante A: app-privat (`getExternalFilesDir`), Export in einen sichtbaren Ordner. Variante B: freier Ordner wie bei HiNote (`MANAGE_EXTERNAL_STORAGE`). Vorschlag: A, weil weniger Rechte nötig sind und der Sync ohnehin die Kopie in Nextcloud erzeugt.
4. **Übergangszeit:** HiNote bleibt installiert, bis die Übernahme geprüft ist. Vorschlag: ja.
5. **Schrifterkennung:** siehe Abschnitt 8. Vorschlag: zunächst weglassen, später serverseitig prüfen.
6. **Eigene Ideen** (Abschnitt 7): welche sollen rein?

## 4. Architektur (Vorschläge, noch nicht umgesetzt)

- **UI:** klassische Views, keine Animationen, großer Kontrast, kein Compose. Grund: E-Ink und `setZOrderOnTop` beim Radierer. Das ist eine Einschätzung und wird in Phase 1 an Dialogen geprüft.
- **Listen:** Seitenwechsel statt Scrollen, wie in HiNote ("Vorherige Seite 2/2 Nächste Seite"). Das schont das E-Ink-Display.
- **Schreibfläche:** `HWEditView` über einer Hintergrund-View (Vorlage). Ein eigener `PageController` hält Seite, Zoom, Verlauf.
- **Undo/Redo:** eigener Verlauf pro Seite (Liste von Aktionen auf Track-Ebene), weil `setTrackList` den Framework-Verlauf leert.
- **Zoom:** Tracks bei Zoom umrechnen und neu setzen. Zu klären: Verhalten bei 500 oder mehr Strichen.
- **E-Ink-Refresh:** HiNote setzt im Manifest `hw_eink.app.refresh.mode=3`. Wirkung in der eigenen App ist zu prüfen.
- **Stifttypen:** Welcher `setAhwPenType`-Wert zu Füllfeder, Kugelschreiber, Bleistift und Marker gehört, ist unklar. Die Zuordnung steht in einer Hilfsklasse in `EinkEngine` (Dekompilat) und wird dort nachgelesen, nicht kopiert. Alternativ durch Ausprobieren auf dem Gerät.
- **Compile-Stub:** `eink-stub` bleibt `compileOnly`. Er wird nicht in die APK gepackt.

### Dateiformat (Vorschlag)
Eine Notiz = ein ZIP (Endung `.pnote`):

```
manifest.json        Version, Titel, Ordnerpfad, Erstellt, Geändert, UUID, Favorit, Fixiert, Seitenreihenfolge
pages/<uuid>.json    Vorlage, Größe, Tracks [Typ, Breite, Farbe, Punkte], Textboxen, Bilder (Referenzen), Lesezeichen
media/<uuid>.<ext>   Bilder, Audio
preview.png          Vorschau der ersten Seite (für Liste und Nextcloud)
```

- Warum ZIP mit JSON: das Nextcloud-Plugin kann es ohne Android-Code lesen, und Änderungen sind per SHA-256 pro Datei erkennbar.
- Punktformat wie in Spike 2 (x, y, Zeit, Druck). Das ist belegt verlustfrei.
- Ordner sind Verzeichnisse. Die Zuordnung steht zusätzlich im `manifest.json`, damit Verschieben ohne Umkopieren geht.
- Sicherheit beim Lesen: Größenlimits, keine Pfade mit `..`, Prüfung der Version.

### Sync (Vorschlag)
- Anmeldung per Nextcloud Login Flow v2. Der App-Token liegt im Android Keystore, nicht im Code und nicht im Repository.
- Transport per WebDAV über HTTPS (TLS 1.2 oder höher, Zertifikate werden geprüft).
- Entscheidung Bibliothek oder eigener Client (PROPFIND, PUT, GET, ETag): offen. Stand der Prüfung: `nextcloud/android-library` ist gepflegt (2.26.1), die Lizenz meldet GitHub als "NOASSERTION" und muss gelesen werden. `sardine-android` und `PdfBox-Android` fallen durch die Regel "Commit in den letzten 6 Monaten". Ein eigener WebDAV-Client wäre klein genug.
- Konflikte: Änderungszeit plus ETag. Bei Konflikt beide Fassungen behalten.

### PDF (Vorschlag)
- `android.graphics.pdf.PdfDocument` (Teil von Android, keine Fremdbibliothek). Striche als Vektorpfade, Breite aus dem Druck selbst berechnet. Nicht getestet.
- Für Plugin und PC-Tool dieselbe Logik in einer anderen Sprache (siehe Phase 6).

## 5. Phasen

Jede Phase endet mit einem Test auf dem Tablet und einer Rückmeldung des Nutzers zum Gefühl.

### Phase 1: Schreiben und Speichern
- Neues Gradle-Projekt (`app/` im Projekt, Spike bleibt in `tmp/penspike`). Basis: Stub und Erkenntnisse aus den Spikes.
- Eine Notiz mit mehreren Seiten, Stift (4 Typen, 3 Farben, Größe), Undo, Redo, Seitenwechsel.
- Speichern und Laden im eigenen Format, Absturzfestigkeit (Speichern bei `onPause`, atomares Schreiben).
- Prüfen: Dialoge und Menüs über der Schreibfläche, Verhalten bei Drehung.
- Abnahme: 20 Minuten schreiben, App beenden, neu öffnen, nichts fehlt.

### Phase 2: Notizliste und Ordner
- Startseite wie HiNote: Ordnerkacheln mit vier Mini-Vorschauen, Notizkarten mit Vorschau, Titel, Datum, Kachel "+ Kurznotiz", Seitenwechsel statt Scrollen, Raster und Liste.
- Menü pro Notiz: Umbenennen, Favorit, Oben fixieren, Kopieren, Verschieben, Teilen, Exportieren, Löschen. "Verschließen" (Schloss) später.
- Suche nach Titel.
- Papierkorb statt sofortigem Löschen (Vorschlag).

### Phase 3: Radierer, Lasso, Vorlagen, Seitenleiste
- Radierer in drei Stilen (Standard, Fläche, Striche) mit gebundenem Weiß, eigener Radierkreis.
- Lasso: auswählen, verschieben, skalieren, löschen.
- Vorlagen: Leer, Linien breit/schmal, Gepunktet, Millimeter, Gitter, To-do, Wochenplan und weitere. Die Auswahl von HiNote ist in `FINDINGS.md` beschrieben. Eigene Zeichnung, keine Huawei-Bilder.
- Seitenleiste mit Vorschauen, Neue Seite, Löschen, Lesezeichen, Sprungfeld.
- Zoom und Verschieben durch Umrechnen der Tracks.

### Phase 4: Text, Bild, Audio
- Textbox, Bild einfügen (Kamera und Dateiauswahl), Audioaufnahme. Zugehörige Berechtigungen nur bei Bedarf anfordern.
- Unterleisten von Lasso, Textbox und Bild in HiNote ansehen, bevor sie gebaut werden (noch nicht erfasst).

### Phase 5: Übernahme der HiNote-Notizen
- Zwei Wege, einer genügt:
  - **A:** Parser für `.hinote`/`.bin` (Teilaufgabe 2) und Import in das eigene Format. Der Prototyp `tmp/export_all.py` exportierte bisher 11 von 33 Notizen automatisch.
  - **B:** Nur Vorschau-Bilder und PDF übernehmen. Schneller, aber nicht mehr editierbar.
- Verschlossene Notizen sind nicht abgedeckt. Der Nutzer entscheidet, ob sie nötig sind.
- Abnahme: Vorschau jeder Notiz mit dem Original vergleichen.

### Phase 6: PDF, Sync, Plugin
- PDF-Export in der App (siehe oben) und als PC-Werkzeug `hinote2pdf` (Teilaufgabe 3) für das alte Format.
- Sync nach Nextcloud (Teilaufgabe 5), danach das Plugin (Teilaufgabe 6). Plugin: Dateiaktion für `.pnote`, Anzeige im Browser, PDF-Download. Sicherheit: Größenlimits und Pfadprüfung beim Entpacken.
- Die Nextcloud-Server-Version und die App-API werden zu Beginn der Phase geprüft.

### Phase 7: Schrifterkennung (optional)
- Siehe Abschnitt 8.

## 6. Bezug zum alten Plan

| Teilaufgabe | Neuer Stand |
|---|---|
| 1 Spike | erledigt |
| 2 Parser `.bin` | bleibt, nur für Phase 5 nötig |
| 3 PDF-Tool | bleibt, auch als Grundlage für das Plugin |
| 4 Export automatisieren | entfällt, außer als Einmal-Export für Phase 5 |
| 5 Sync | geht in Phase 6 auf |
| 6 Plugin | Phase 6 |
| 7 Eigene App | dieses Dokument |

## 7. Eigene Ideen (zur Auswahl)

- **Zuletzt geöffnet** als oberste Zeile der Startseite.
- **Tags** zusätzlich zu Ordnern.
- **Suche über Handschrift**, sobald eine Erkennung vorliegt (Abschnitt 8).
- **Automatische Sicherung** vor jedem Sync, damit ein Konflikt nichts zerstört.
- **PDF-Export als Standard-Teilen-Aktion** mit Seitenauswahl.
- **Lesemodus** ohne Stift (Gesten zum Blättern).

## 8. Schrifterkennung

**Stand (belegt):** Die Engine `PenKitRecg-master.apk` liegt auf dem Gerät und ist lesbar. Sie lässt sich laden, aber `init` scheitert mit `The app isn't in white list`. Die genaue Prüfung ist nicht ausgewertet.

**Entscheidung:** Ich umgehe diese Sperre nicht. Die Engine ist proprietär, und die Prüfung ist offenbar gewollt. Wer sie mit falschem Paketnamen oder gefälschter Signatur austrickst, bricht die Lizenzbedingungen und ist beim nächsten Update wieder draußen.

**Was bleibt (alles nicht geprüft):**
1. Freie oder lizenzierbare Erkennung in der App. Google Play Services sind auf dem Paper nicht installiert (`pm list packages` zeigt weder `gms` noch `vending`), ML Kit scheidet damit vermutlich aus. Zu prüfen wären Open-Source-Engines für Handschrift (Pflege, Lizenz, Qualität für Deutsch).
2. Serverseitig im Nextcloud-Plugin, damit die Suche ohne Rechenlast auf dem Tablet geht. Die Striche liegen im JSON vor.
3. Beim Import alter Notizen den HiNote-Text mitnehmen, falls HiNote ihn speichert. Ob die Datenbank erkannten Text enthält, ist nicht geprüft.

## 9. Risiken

| Risiko | Wirkung | Gegenmaßnahme |
|---|---|---|
| Framework ändert sich bei einem Systemupdate | Schreiben bricht | Gerät vorerst nicht aktualisieren, Fehler früh abfangen |
| Dialoge unter `setZOrderOnTop` | Menüs nicht sichtbar | in Phase 1 testen, ggf. Zeichenfläche vorübergehend verstecken |
| Zoom bei vielen Strichen träge | Unbrauchbar bei langen Notizen | in Phase 3 mit 1000 Strichen messen |
| Datenverlust | Notizen weg | atomares Speichern, Sicherung vor Sync, HiNote bleibt zunächst |
| Fremdbibliotheken ungepflegt | Sicherheitsregel verletzt | Pflegestand vor Aufnahme prüfen, bevorzugt Android-eigene APIs |

## 10. Arbeitsregeln

- Der Huawei-Code wird nur als Anleitung gelesen, nicht kopiert.
- Keine Zugangsdaten im Code oder Repository (Android Keystore, `.gitignore`).
- Abhängigkeiten aktuell halten und vor der Aufnahme auf Pflege und Lizenz prüfen.
- Behauptungen über das Gerät werden vor dem Eintrag auf dem Tablet geprüft.
