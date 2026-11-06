# Eigene Notiz-App für das Huawei Paper: Neubau-Plan

Stand: 2026-11-05. Dies ist der Hauptplan für die App. Der Plan `hinote-nextcloud-plan.md` behandelt nur noch den HiNote-Altbestand (Analyse, Parser, Übernahme) und gehört zu Phase 5. Befunde und Belege stehen in `tmp/FINDINGS.md`. Was dort nicht belegt ist, steht hier als **Annahme** oder **offen**.

**Fortschritt (2026-11-06, Release 0.2.0):** Phase 1 und Phase 2 sind umgesetzt und auf dem Tablet geprüft (Schreiben mit Stift, Editor-Leisten, Vorlagen, Startseite mit Ordnern, Kacheln, Liste, Suche, Notizmenü, Sortierung). Phase 2b (Auswahlmodus, Papierkorb-Ansicht, Einstellungen, Über-Seite, `LICENSE`) ist umgesetzt und als 0.2.0 gebaut; auf dem Tablet per adb nur oberflächlich geprüft (Über-Seite beim ersten Start, Startseite, Menü, Einstellungen, leerer Papierkorb, Auswahlmodus mit Auswahl und Titel), Gerätetest der Sammelaktionen (Teilen, Verschieben, Löschen), von Papierkorb mit Inhalt und von Englisch steht aus. Als Nächstes folgt Phase 3. Die App ist von Anfang an zweisprachig (Deutsch und Englisch, siehe Abschnitt 10).

### Statusübersicht (Stand 2026-11-06)

| Bereich | Stand | Bemerkung |
|---|---|---|
| Projektgerüst, Signierung, Tests | fertig | Gradle 9.8.2, AGP 9.4.1, `:app` und `:eink-stub` (compileOnly), 219 Tests (`@Test`, Gradle-Bericht: 219 ausgeführt, 0 Fehler), Lint 0 Fehler (nur Warnungen), Version 0.2.0 |
| Speicherformat `.pnote` und Speicherschicht | fertig | ZIP + JSON, atomar, Größenlimits; Ordner, Papierkorb `.trash/` in `Documents/PaperNotes` |
| Editor: Schreiben, Undo/Redo, Seiten hinzufügen/löschen | fertig | Stift über `HWEditView`, Speichern nach 5 s und bei `onPause` |
| Editor: Leisten (Kopfzeile, Werkzeuge, Stift/Radierer-Popup mit Pin) | fertig | Stifttyp-Zuordnung (4/2/3/6) aus dem Dekompilat, nicht belegt. Radierer in drei Stilen (Standard, Fläche, Striche) mit Größe (2 bis 120) ist eingebaut |
| Editor: Stiftbedienung von Leisten und Dialogen | fertig | Muster `suspendWriting` und `setWriteExclusion`, gilt für jeden neuen Dialog im Editor |
| Editor: Vorlagen (16 Stück, Dialog mit Vorschau) | fertig | aus Phase 3 vorgezogen; Dialog auf dem Tablet bestätigt |
| Startseite (Ordner, Kacheln, Liste, Suche, Notizmenü, Sortierung) | fertig | Vorschauen aus Vorlage und Strichen, Cache in `cacheDir/previews` |
| App-Icon (Adaptive Icon) | fertig | Rahmen im Launcher sichtbar, Icon wirkt etwas klein (Inset 22 %, ggf. 20 %) |
| Zweisprachigkeit (de/en) | im Code fertig | auf dem Gerät nur Deutsch gesehen |
| **Phase 2b:** Mehrfachauswahl, Einstellungen, Papierkorb-Ansicht, Über, `LICENSE` | **umgesetzt (0.2.0), Gerätetest teilweise** | Auf dem Tablet gesehen: Über-Seite (Auto-Anzeige), Startseite, Menü `⋮`, Einstellungen, leerer Papierkorb, Auswahlmodus (Haken setzen). **Gerätetest ausstehend:** Teilen/Verschieben/Löschen, Papierkorb mit Inhalt (Wiederherstellen, endgültig löschen), Englisch. Siehe Abschnitt Phase 2b |
| Automatische Sicherungskopien beim Schreiben | fertig, auf dem Tablet geprüft | Intervall einstellbar (Standard 5 min), `.backup/<noteId>/` max. 10 je Notiz, Wiederherstellen nur als Kopie; `.backup` wie `.trash` vom Sync ausschließen (Phase 6) |
| **Phase 3 (Rest):** Radierkreis, Lasso-Unterleiste und Skalieren, Seitenleiste, Zoom, Skalierung der Striche | **offen** | Lasso-Werkzeug ist eingebunden (laut Code: Auswahl, Verschieben, Löschen und Verlauf über das Framework; auf dem Tablet nicht geprüft). Platzhalter (ausgegraut): Menü `⋮`, Seitenleiste, Textbox, Bild, Audio |
| Phase 4: Text, Bild, Audio | offen | Unterleisten von Lasso, Textbox, Bild in HiNote noch nicht erfasst |
| Phase 5: Übernahme der HiNote-Notizen | offen | Prototyp `tmp/export_all.py` exportierte 11 von 33 |
| Phase 6: PDF, Sync (Nextcloud), Plugin | offen | Papierkorb `.trash/` vom Sync ausschließen |
| Phase 7: Schrifterkennung | zurückgestellt | Sperre der Huawei-Engine wird nicht umgangen |

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

1. **Name und Paketname** der App. Entschieden: Anzeigename "PaperNotes by BOB", Paket `local.papernotes`. Das Icon ist das vom Nutzer gelieferte Bild `PaperNotes BY BOB.png`. Beides ist leicht änderbar, solange nichts veröffentlicht ist.
2. **Kotlin oder Java.** Entschieden: Kotlin, klassische Views, kein Compose.
3. **Speicherort.** Entschieden (2026-11-05): normaler Benutzerbereich, nicht app-privat. Die Notizen liegen in `Documents/PaperNotes` auf dem gemeinsamen Speicher. Umsetzung: `targetSdk 29` mit `requestLegacyExternalStorage` und `WRITE_EXTERNAL_STORAGE`. Bei einem späteren Anheben von `targetSdk` auf 30 oder höher müsste auf `MANAGE_EXTERNAL_STORAGE` oder das Storage Access Framework gewechselt werden.
4. **Übergangszeit:** Entschieden: HiNote bleibt installiert, bis die Übernahme geprüft ist.
5. **Schrifterkennung:** siehe Abschnitt 8. Vorschlag: zunächst weglassen, später serverseitig prüfen.
6. **Eigene Ideen** (Abschnitt 7): welche sollen rein?

## 4. Architektur

**Umgesetzt (Stand 2026-11-05):**
- **UI:** klassische Views, keine Animationen, großer Kontrast, kein Compose. Dialoge und Menüs über der Schreibfläche funktionieren mit dem Muster `suspendWriting` plus `setWriteExclusion` (Stift sperren, solange ein Overlay offen ist).
- **Listen:** Seitenwechsel statt Scrollen, wie in HiNote ("Vorherige Seite 2/2 Nächste Seite"). Das schont das E-Ink-Display.
- **Schreibfläche:** `HWEditView` liegt über der `PageBackgroundView` (Vorlage). Die Sitzung hält `NoteSession`.
- **Undo/Redo:** eigener Verlauf pro Seite (`PageHistory`, Aktionen auf Track-Ebene), weil `setTrackList` den Framework-Verlauf leert.
- **E-Ink-Refresh:** Das Manifest-Attribut `hw_eink.app.refresh.mode=3` ist wie in HiNote gesetzt. Eine eigene Wirkung wurde nicht untersucht.
- **Compile-Stub:** `eink-stub` bleibt `compileOnly` und ist nicht in der APK (Prüfung: 0 Treffer für `android/eink`).

**Offen:**
- **Zoom:** Tracks bei Zoom umrechnen und neu setzen. Zu klären: Verhalten bei 500 oder mehr Strichen (Phase 3).
- **Stifttypen:** Die Zuordnung der `setAhwPenType`-Werte 4/2/3/6 zu den vier Stiften stammt aus dem Dekompilat und ist auf dem Gerät nicht belegt.

### Dateiformat (umgesetzt, mit einer Abweichung)
Eine Notiz = ein ZIP (Endung `.pnote`):

```
manifest.json        Version, Titel, Ordnerpfad, Erstellt, Geändert, UUID, Favorit, Fixiert, Seitenreihenfolge
preview.jpg          Vorschau der ersten Seite (JPEG, 360 px breit, max. 2 MB), direkt hinter dem Manifest
pages/<uuid>.json    Vorlage, Größe, Tracks [Typ, Breite, Farbe, Punkte], Textboxen, Bilder (Referenzen), Lesezeichen
media/<uuid>.<ext>   Bilder, Audio
```

- **Vorschau im Paket (Entscheidung 2026-11-05, im Code umgesetzt, auf dem Tablet noch nicht geprüft):** `preview.jpg` liegt in der Notiz. So braucht das Nextcloud-Plugin keinen eigenen Renderer, und nach Sicherung oder Wiederherstellung ist die Vorschau sofort da. Der Editor rendert sie beim Speichern (`PreviewStamper`), und zwar nur, wenn sich Seite 1 geändert hat. Die Startseite liest zuerst `preview.jpg` (`NoteRepository.loadPreview`, `PnoteCodec.readPreview`). Nur für ältere Notizen ohne Vorschau gilt der Rückfall: neu rendern und im App-Cache ablegen (`cacheDir/previews`). Beim Lesen wird eine Vorschau verworfen, die kein JPEG oder größer als 2 MB ist; die Notiz bleibt lesbar.
- **Morgen testen (offen):** (1) Neue Notiz anlegen, schreiben, zurück zur Startseite: Kachel zeigt die Vorschau. (2) Notiz umbenennen, verschieben und kopieren: Vorschau bleibt. (3) Alte Notiz ohne `preview.jpg` öffnen und speichern: Vorschau wird danach im Paket gespeichert. (4) Startseite mit vielen Notizen: Ladezeit prüfen, denn das Lesen der Vorschau öffnet jede Datei einzeln. (5) Notiz ohne Änderung an Seite 1 (nur Seite 2 beschreiben): Vorschau wird nicht neu gerendert. Ungeklärt: Notizen, die nur in der Liste liegen und nie im Editor geöffnet wurden, haben keine eingebettete Vorschau, bis sie einmal gespeichert werden.

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

### Phase 1: Schreiben und Speichern (umgesetzt, auf dem Tablet bestätigt)
- Neues Gradle-Projekt (`app/` im Projekt, Spike bleibt in `tmp/penspike`). Basis: Stub und Erkenntnisse aus den Spikes.
- Eine Notiz mit mehreren Seiten, Stift (4 Typen, 3 Farben, Größe), Undo, Redo, Seitenwechsel.
- Speichern und Laden im eigenen Format, Absturzfestigkeit (Speichern bei `onPause`, atomares Schreiben).
- Prüfen: Dialoge und Menüs über der Schreibfläche, Verhalten bei Drehung.
- Abnahme: 20 Minuten schreiben, App beenden, neu öffnen, nichts fehlt.

### Phase 2: Notizliste und Ordner (umgesetzt, auf dem Tablet geprüft)
- Startseite wie HiNote: Ordnerkacheln mit vier Mini-Vorschauen, Notizkarten mit Vorschau, Titel, Datum, Kachel "+ Kurznotiz", Seitenwechsel statt Scrollen, Raster und Liste.
- Menü pro Notiz: Umbenennen, Favorit, Oben fixieren, Kopieren, Verschieben, Teilen, Exportieren, Löschen. "Verschließen" (Schloss) später.
- Suche nach Titel.
- Papierkorb statt sofortigem Löschen (umgesetzt, Ansicht dazu siehe Phase 2b).
- Sortierung wählbar (umgesetzt).

### Phase 2b: Mehrfachauswahl, Einstellungen, Papierkorb, Über (umgesetzt in 0.2.0, Gerätetest teilweise; Entscheidungen vom 2026-11-05)
Entschieden: Der Papierkorb behält Notizen, bis der Nutzer ihn leert (keine automatische Frist). Die Einstellungen starten mit dem oben genannten Umfang. Die Lizenz ist GPL-3.0-or-later (wie PDFScan by BOB); die `LICENSE`-Datei (offizieller GNU-Text) und ein README-Abschnitt "Lizenz" werden mit diesem Schritt angelegt. Logo für Über: `PaperNotes BY BOB.png`. Das Banner `PaperNotes BY BOB-banner.jpg` ist für die README gedacht.
Referenz: Menü `⋮` der Startseite in HiNote enthält "Notizen auswählen", "Sortieren nach" und "Einstellungen". Ein Teil davon ist umgesetzt (Sortieren nach).
- **Auswahlmodus** ("Notizen auswählen"): Kopfzeile wechselt zu `✕  N Elemente ausgewählt`. Aktionen oben rechts als Symbol mit Beschriftung: Teilen, Exportieren, Verschieben, Löschen, Alle auswählen. Jede Kachel und Listenzeile bekommt unten rechts ein Auswahlkästchen, ein Tipp auf die Kachel setzt oder löst den Haken (öffnet nichts). Notizen und Ordner sind gemeinsam wählbar. Zurück-Taste oder `✕` beendet den Modus.
- **Sammelaktionen:** Löschen (Notizen in den Papierkorb, Ordner samt Inhalt nach Rückfrage), Verschieben (Zielordner wählen, Ordner nicht in sich selbst), Teilen (mehrere `.pnote`-Dateien über `ACTION_SEND_MULTIPLE`). Exportieren: Dialog "Exportieren als" mit Bild und PDF (kommt mit Phase 6; TXT entfällt, solange es keine Schrifterkennung gibt). Bis dahin bleibt der Eintrag ausgegraut.
- **Einstellungen:** eigene Seite, zunächst nur Sprache folgt dem System (keine Auswahl nötig), Speicherort anzeigen, Papierkorb leeren, Versionsangabe und Info. Sync-Einstellungen kommen mit Phase 6.
- **Papierkorb ansehen:** Wiederherstellen und endgültig löschen, erreichbar über die Einstellungen (bisher gibt es `.trash/` nur im Dateisystem).
- **Über (About):** Eintrag im Menü `⋮` der Startseite, Verhalten und Inhalt wie in PDFScan by BOB (Vorlage: `~/git/PDFScan-BY-BOB`, `ui/about/AboutScreen.kt`, `AboutViewModel.kt`, Texte in `values/strings.xml` unter "About"):
  - **Inhalt:** Logo (das PaperNotes-Bild, 120 dp), App-Name "PaperNotes by BOB", "Version x.y.z" (aus `BuildConfig.VERSION_NAME`, diese kommt aus `VERSION`), Kasten "Neu in Version x.y.z" mit Stichpunkten zur aktuellen Version, kurze Beschreibung der App, Lizenzhinweis ("Freie Software unter der GNU General Public License v3.0 oder später. Copyright © 2025 hjochman. Dieses Programm wird ohne jede Gewährleistung verteilt."), Link "Quellcode auf GitHub" (`https://github.com/hjochman/HiNoteNextcloud`), Knopf "Schließen". Zurück-Pfeil in der Kopfzeile.
  - **Automatisch anzeigen:** Beim ersten Start nach der Installation und nach jedem Update mit geänderter `versionName` öffnet die App zuerst den About-Screen. Gespeichert wird die zuletzt gesehene Version (Preference `about_last_seen_version`), geschrieben beim Schließen. Danach nur noch über das Menü erreichbar.
  - **Pflege:** Der Kasten "Neu in Version" und die Beschreibung werden bei jeder Versionsänderung angepasst (Regel in `AGENT.md` aufnehmen). Sie sind Teil der Release-Prüfung.
  - **Erledigt:** `LICENSE`-Datei (GPL-3.0-or-later) und README-Abschnitt "Lizenz" liegen im Projekt.
  - Der Link öffnet den Browser. Das Tablet hat einen; ohne Browser darf nichts abstürzen (Fehlermeldung als Hinweis).
- Alle Texte zweisprachig (Deutsch und Englisch), Menüs und Dialoge nach E-Ink-Regeln.

### Phase 3: Radierer, Lasso, Vorlagen, Seitenleiste (Vorlagen fertig, Rest offen)
- Radierer in drei Stilen (Standard, Fläche, Striche) mit gebundenem Weiß: **fertig** (Auswahl und Größe in der Radierer-Leiste, Einstellung wird gemerkt). **Offen:** eigener Radierkreis, denn er fehlt im Schnellpfad.
- Lasso: auswählen, verschieben, skalieren, löschen. **Offen.** Die Lasso-Unterleiste von HiNote ist noch nicht erfasst (ansehen vor dem Bau).
- Vorlagen: **fertig** (16 Vorlagen in 4 Gruppen, Dialog mit Pfeilen und "Für alle Seiten übernehmen", eigene Zeichnung). Marker hat noch keine Alpha-Logik.
- Seitenleiste mit Vorschauen, Neue Seite, Löschen, Lesezeichen, Sprungfeld: **fertig (3-2, auf dem Tablet geprüft)**: Reiter Seiten/Lesezeichen, Sprungfeld, Vorschauliste mit Blättern per Pfeil, Menü pro Seite, Neue Seite am Ende. Seiten verschieben ist nicht gebaut.
- Zoom und Verschieben durch Umrechnen der Tracks. **Offen (3-3).** Schritt 3-1 ist im Code umgesetzt (auf dem Tablet noch nicht geprüft): Striche liegen in Seitenkoordinaten (Seitengröße der `Page`, Standard 1404x1872), `PageTransform` bildet Seite <-> Ansicht ab (Maßstab + Versatz, Breiten skalieren mit), `TrackMapper`/`PageCanvasView`/`PageBackgroundView` wenden sie an; Zoom ändert später nur diese Abbildung. Format-Version 2; Version 1 (Ansichts-Pixel, Ansichtsbreite = Seitenbreite) wird beim Lesen als Seitenkoordinaten übernommen.

### Phase 4: Text, Bild, Audio
- Textbox, Bild einfügen (Kamera und Dateiauswahl), Audioaufnahme. Zugehörige Berechtigungen nur bei Bedarf anfordern.
- Unterleisten von Lasso, Textbox und Bild in HiNote ansehen, bevor sie gebaut werden (noch nicht erfasst).

### Phase 5: Übernahme der HiNote-Notizen
- Zwei Wege, einer genügt:
  - **A:** Parser für `.hinote`/`.bin` (Teilaufgabe 2 in `hinote-nextcloud-plan.md`) und Import in das eigene Format. Der Prototyp `tmp/export_all.py` (Teilaufgabe 4 dort) exportierte bisher 11 von 33 Notizen automatisch.
  - **B:** Nur Vorschau-Bilder und PDF übernehmen. Schneller, aber nicht mehr editierbar.
- Verschlossene Notizen sind nicht abgedeckt. Der Nutzer entscheidet, ob sie nötig sind.
- Abnahme: Vorschau jeder Notiz mit dem Original vergleichen.

### Phase 6: PDF, Sync, Plugin
- PDF-Export in der App (siehe oben). Das PC-Werkzeug `hinote2pdf` für das alte Format ist zurückgestellt und wird nur auf Wunsch gebaut (siehe `hinote-nextcloud-plan.md`).
- Sync nach Nextcloud, danach das Plugin. Plugin: Dateiaktion für `.pnote`, Anzeige im Browser, PDF-Download. Sicherheit: Größenlimits und Pfadprüfung beim Entpacken. Der Papierkorb `.trash/` wird nicht synchronisiert.
- Die Nextcloud-Server-Version und die App-API werden zu Beginn der Phase geprüft.

### Phase 7: Schrifterkennung (optional)
- Siehe Abschnitt 8.

## 6. Bezug zum Altbestand-Plan

`hinote-nextcloud-plan.md` ist der Plan für den HiNote-Altbestand. Seine Teilaufgaben ordnen sich so ein:

| Teilaufgabe dort | Stand | Bezug hier |
|---|---|---|
| 1 Spike | erledigt | Grundlage von Abschnitt 2 |
| 2 Parser `.bin` | offen | nur für Phase 5 nötig |
| 3 PDF-Tool `hinote2pdf` | zurückgestellt | nur auf Wunsch; der PDF-Export der App steht in Phase 6 |
| 4 Einmal-Export | offen, reduziert | Phase 5 (Weg A) |
| Sync, Plugin, Auto-Upload, Accessibility-Export | entfallen dort | gehen in Phase 6 dieses Plans auf |

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
- **Zweisprachig:** Die App läuft vollständig auf Deutsch (Standard) und Englisch. Jeder sichtbare Text, auch die in Seitenvorlagen gezeichneten Beschriftungen, steht in `values/strings.xml` und `values-en/strings.xml`. Sprache und Datumsformate folgen dem Gerät. Auf dem Tablet wurde bisher nur Deutsch gesehen, Englisch ist noch zu prüfen.
- **Über-Seite pflegen:** Bei jeder Versionsänderung den Kasten "Neu in Version" und die Beschreibung anpassen (siehe Phase 2b).
- **Offene kleine Punkte (Stand 2026-11-05):**
  - Englisch auf dem Gerät prüfen (Startseite, Editor, Vorlagendialog, Datumsformate).
  - Das Popup-Menü an der Notizkachel sitzt rechts bündig am `⋮`, bei HiNote links davon. Nur ändern, wenn es stört.
  - Ordner lassen sich nicht sortieren: Sie stehen immer vor den Notizen, nach Name.
  - Mehrere Notizen und Blättern über mehrere Seiten sowie die Suche sind noch nicht mit vielen Einträgen getestet.
  - Verzögerung der ersten 1 bis 2 Striche nach dem Schließen eines Dialogs im Editor: nicht gemessen, nur untersuchen, wenn sie stört.
  - Striche liegen in Ansichts-Pixeln, die Skalierung auf die Seitengröße fehlt noch (Phase 3).

