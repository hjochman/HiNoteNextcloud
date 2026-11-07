# Changelog

Alle nennenswerten Änderungen an diesem Projekt werden in dieser Datei dokumentiert.

Das Format basiert auf [Keep a Changelog](https://keepachangelog.com/de/1.0.0/)
und dieses Projekt folgt [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Hinzugefügt
- Import von HiNote-Exporten (`.hinote`, Phase 5): Paket `hinote/` mit `HinoteBinParser` (strenger Big-Endian-Leser für `PENCILENGINE` nach `docs/hinote-bin-format.md`: Größenfelder vor jeder Allokation geprüft, Limits für Strich- und Punktzahl, Dateiende exakt, Striche mit `link_index != -1` oder in Abschlussgruppen entfallen; Ergebnis als `HinoteResult`, keine Exceptions nach außen), `HinoteContainerReader` (ZIP im Speicher, Allowlist für `<noteId>.jhinote`, `pages/<id>.jhinote`, `files/<id>.bin`, ZIP-Slip-Prüfung, Größenlimits wie `PnoteCodec` und Kompressionsratenprüfung, gzip-JSON mit Gson), `HinoteImporter` (Mapping: x/y mit 1,404 auf die 1404x1872-Seite, oben ausgerichtet; Stifttypen 1/2/3/4/5/11/12/13 auf `PenCatalog`; Farbe BGR-Float auf ARGB; Vorlagen-Tabelle auf `TemplateCatalog`; Seiten nach `pageNumber`; unlesbare Seite = leere Seite + Warnung; Konstante `STROKE_WIDTH_FACTOR` = 1,0) und `HinoteImportRunner`/`HinoteImportFlow`.
- Startseite, Menü `⋮`: „HiNote-Notizen importieren …“ (Dateiauswahl per Storage Access Framework, Mehrfachauswahl) und „Aus Documents/hinote/custom importieren“. Import im Hintergrund-Thread mit Textfortschritt „Notiz 3/14“, Ergebnisdialog „x importiert, y übersprungen (bereits importiert), z mit Warnungen“; Zielordner `HiNote-Import` in der Wurzel. `EinkDialog.message` (Meldungsdialog). Strings de/en.
- Doppelimport-Schutz: optionales Manifest-Feld `importSource` (`hinote:<noteId>`) in `.pnote`, `Note.importSource`, `NoteSummary.importSource`. Format-Version bleibt 2; ältere Dateien ohne das Feld sind unverändert lesbar, ältere App-Versionen ignorieren das Feld.
- Tests: `HinoteBinParserTest`, `HinoteImportTest` mit synthetischen Dateien (gültig, abgeschnitten, angehängtes Byte, falsche Kennung, ZIP-Slip, überdimensionierter Eintrag, Kompressionsrate, Strichzahl-Limit, Zufallsdaten) und einem Test über die echten Exporte aus `tmp/device/exp/custom` (übersprungen, wenn nicht vorhanden): 19 Seiten, 18 `.bin`, 4607 Striche, 78 119 Punkte wie in der Python-Zählung.
- PDF-Export (Phase 6, PDF-Teil): `export/PdfExporter` schreibt mit `android.graphics.pdf.PdfDocument` (Teil von Android, keine Fremdbibliothek) eine PDF-Seite je Notizseite, Breite 595 pt, Höhe nach Seitenverhältnis (1404x1872 ergibt 595x793). Vorlage (`TemplateRenderers`) und Striche (Linienzüge in Seitenkoordinaten, Farbe und Breite aus dem Strich) werden wie in `PreviewRenderer` gezeichnet, aber als Vektoren statt Bitmap. Reine Logik mit JVM-Tests: `PdfLayout` (Seitenmaße, Skalierung, Grenze 14400 pt) und `ExportFileNames` (Titel -> sicherer Dateiname ohne `/`, `\`, `..`, Steuerzeichen, höchstens 80 Zeichen, Kollisionen mit „ (2)“).
- Export-Ablauf `ExportFlow`: Dialog „Exportieren als“ (PDF), danach „Teilen“ (`ACTION_SEND`/`ACTION_SEND_MULTIPLE`, `application/pdf`, FileProvider-Pfad `cacheDir/export`) oder „In Dokumente speichern“ (`Documents/PaperNotesPDF`, bestehende Dateien werden nie überschrieben). Je Notiz eine Datei; Erzeugung im Hintergrund-Thread mit Textfortschritt „Seite 3/12“ (`EinkDialog.progress`, keine Animation, abbrechbar); `cacheDir/export` wird vor jedem Export geleert.
- Startseite: Notizmenü „Exportieren“ und Auswahlmodus-Aktion „Exportieren“ sind aktiv (Ordner in der Auswahl exportieren alle enthaltenen Notizen). Editor: Menü `⋮` der Kopfzeile mit „Exportieren als PDF“ (speichert die Notiz vorher; Dialoge nutzen `overlayOpened`/`overlayClosed`). Strings de/en.
- Automatische Sicherungskopien während des Schreibens: Solange eine Notiz im Editor offen ist, legt ein Handler-Tick (alle 10 s geprüft) im eingestellten Intervall (Aus/1/2/5/10/15/30 Minuten, Standard 5, Preference `backup_interval_minutes`) eine Kopie der gespeicherten `.pnote` an, sofern seit der letzten Sicherung Änderungen vorliegen; beim Schließen nur bei Intervall != Aus, abgelaufenem Intervall und Änderungen. Dateizugriffe nie im UI-Thread. Reine Entscheidungslogik in `BackupPolicy`.
- `BackupStore`: Sicherungen unter `Documents/PaperNotes/.backup/<noteId>/<zeitstempel>.pnote` (atomar über temp+rename, höchstens 10 je Notiz, älteste zuerst gelöscht, noteId nur UUID, `canonicalPath`-Prüfung, keine Symlinks); `.backup` wird wie `.trash` von Listen, Suche, Ordnerlisten und Ordneroperationen ignoriert und bleibt bei Papierkorb-Aktionen bestehen; Wiederherstellen nur als neue Kopie (neue IDs, Titel mit Zusatz „(Sicherung <Zeit>)“), nie überschreibend.
- Einstellungen: Abschnitt „Sicherung“ mit Intervall-Auswahldialog (`EinkDialog.singleChoice`), „Sicherungen“ (`BackupsActivity`: nach Notiz gruppiert, Seitenwechselleiste, „Wiederherstellen als Kopie“) und „Alle Sicherungen löschen“ mit Rückfrage. Texte de/en; Tests `BackupPolicyTest`, `BackupStoreTest`.
- Phase 3 (3-2): Seitenleiste im Editor (Knopf „Seitenleiste“ in der Kopfzeile, Fenster rechts über dem Blatt, Stift bedient sie, Schreibfläche währenddessen gesperrt) mit den Reitern „Seiten“ und „Lesezeichen“: Sprungfeld mit „Gehe zu“, Seitenvorschauen (Vorlage + Striche über `PreviewRenderer.renderPage`, Hintergrund-Thread, kleiner Speicher-Cache in `PagePreviews`), aktuelle Seite markiert, Blättern per Pfeil statt Scrollen, pro Seite Lesezeichen und Menü (Lesezeichen setzen/entfernen, Seite löschen mit Rückfrage, nicht die letzte), „Neue Seite“ am Ende. Reine Logik in `SidebarLogic`.
- Lesezeichen als boolesches Feld `bookmarked` je Seite (war im Format und in `Page` schon vorhanden, bleibt Format-Version 2; fehlt das Feld, gilt „kein Lesezeichen“); `NoteSession.setBookmark`, `deletePageAt`, `addPage(atEnd)`.
- Neue Symbole `ic_bookmark`, `ic_bookmark_outline`; Strings de/en; Tests für `SidebarLogic`, Lesezeichen im Codec und in `NoteSession`.

### Geändert
- Phase 3 (3-1): Striche liegen jetzt in Seitenkoordinaten (Seitengröße der `Page`, Standard 1404x1872) statt in Ansichts-Pixeln. Reine Abbildung Seite <-> Ansicht in `PageTransform` (Maßstab + Versatz, Punkte und Strichbreiten); `TrackMapper` und `PageCanvasView` rechnen an der Grenze zur `HWEditView` um, `PageBackgroundView` legt die Vorlage mit derselben Abbildung auf die Seite. Aktuell Maßstab = Ansichtsbreite / Seitenbreite (bei 1404 Pixeln Breite 1:1), Versatz 0.
- `.pnote` Format-Version 2 (Koordinaten in Seitenkoordinaten). Version 1 wird weiter gelesen (Ansichtsbreite = Seitenbreite, Zahlen unverändert) und beim nächsten Speichern als Version 2 geschrieben; ältere App-Versionen lehnen Version 2 ab.

## [0.2.0] - 2026-11-06

### Hinzugefügt
- Phase 2b: Papierkorb-API im `NoteRepository` (Einträge auflisten, wiederherstellen, endgültig löschen, Papierkorb leeren) und `TrashActivity` (Papierkorb-Ansicht mit Wiederherstellen und endgültigem Löschen, seitenweise Anzeige; `TrashEntryText`, `TrashPaging`)
- Auswahlmodus der Startseite („Notizen auswählen“ im Menü `⋮`) mit gemeinsamer Auswahl von Notizen und Ordnern, Auswahlkästchen auf Kacheln und Zeilen, „Alle auswählen“ sowie den Sammelaktionen Teilen (`ACTION_SEND_MULTIPLE` über `FileProvider`), Verschieben (`moveFolder`, Ordner nicht in sich selbst) und Löschen (Notizen in den Papierkorb, Ordner samt Inhalt über `deleteFolderWithContents`); reine Logik in `SelectionState` und `SelectionActions`
- `SettingsActivity` (Einstellungen: Speicherort, Papierkorb leeren/ansehen, Version, Info)
- `AboutActivity` (Über-Seite mit Logo, Version, Kasten „Neu in Version“, Beschreibung, Lizenzhinweis und GitHub-Link); erscheint beim ersten Start nach Installation und nach jedem Update mit geänderter `versionName` automatisch einmal (`AboutPolicy.shouldShowAbout`, Preference `about_last_seen_version`)
- `LICENSE` (GPL-3.0-or-later) und `README.md` mit Abschnitt „Lizenz“
- `EinkDialog` (Bestätigungsdialog nach E-Ink-Regeln) und `NoteSharing` (Teilen per `FileProvider`)
- Neue JUnit-Tests (u. a. `SelectionState`, `SelectionActions`, `AboutPolicy`, Papierkorb, Repository-Erweiterungen); insgesamt 219 Tests
- Startseite nach HiNote-Vorbild (ersetzt die provisorische `MainActivity`): Kopfbereich mit Überschrift („Alle Notizen“/Ordnername), Zurück-Pfeil im Ordner und Anzahl per Plurals; Neu-Menü (Notiz/Ordner), Suche in allen Titeln, Raster/Liste umschaltbar (gemerkt in SharedPreferences), Menü `⋮` („Neuer Ordner“); Ordnerkacheln mit Umriss und bis zu 4 Mini-Vorschauen, Ordneransicht mit 4 Spalten (6 im Querformat) und Kachel „+ Kurznotiz“; Notizkarten mit Vorschau, Titel (2 Zeilen), Datum, Favoritenstern und Menü
- Seitenwechsel statt Scrollen (Leiste „Vorherige/Nächste Seite“, Seitenzahl, ausgegraut an den Grenzen); Kacheln je Seite ergeben sich aus Höhe des Inhaltsbereichs und Zeilenhöhe (`Pagination`, `GridMetrics`, `HomeModel`)
- Notizmenü: Umbenennen, Favorit, Oben fixieren, Kopieren, Verschieben, Teilen (Share-Intent mit `.pnote` über `FileProvider`, Kopie nur in `cacheDir/share`), Exportieren (ausgegraut, Phase 6), Löschen in den Papierkorb mit Bestätigung; Ordnermenü: Umbenennen, Löschen (nur leere Ordner)
- Datumsanzeige (`DateLabels`): heute Uhrzeit, im laufenden Jahr Tag + Monatsname, sonst volles Datum, alles nach Gerätesprache
- Vorschaubilder (`PreviewRenderer`, `PreviewLoader`, `PreviewCache`): Seite 1 mit Vorlage und Strichen im Hintergrund-Thread, PNG in `cacheDir/previews/<id>-<modified>.png`, LRU-Begrenzung 50 MB, nur betroffene Kachel wird aktualisiert
- `NoteRepository`: `search`, `listAll`, `listAllFolders`, `noteFile`, `setFavorite`/`setPinned` (ohne Änderung von `modified`), `copy` (neue IDs), `deleteFolder` (nur leer), Titelvalidierung beim Umbenennen (`NoteTitles`), einheitliche Sortierung (`NoteOrdering`: fixiert zuerst, dann `modified` absteigend)
- Neue Vektor-Symbole (Suche, Raster, Liste, Ordner, Stern, Pin, Kopieren, Verschieben, Teilen, Export, Papierkorb, Umbenennen, Pfeile), Strings de/en und neue Tests (Seitenaufteilung, Datumsformat, Sortierung, Vorschau-Cache, Repository-Erweiterungen, `HomeModel`)
- Seitenvorlagen (Subtask 4): `TemplateCatalog` (16 Vorlagen in 4 Gruppen, unbekannte IDs fallen auf `blank`), `editor/templates/` mit reiner Geometrie (`TemplateGeometry`, JVM-getestet) und `ShapeTemplateRenderer` (Canvas, graue Linien, keine Flächen), Vorschaubilder im Hintergrund-Thread (`TemplateThumbnails`, RGB_565, Cache), Dialog „Vorlage wechseln“ (`TemplateDialog`, Blättern per Pfeil, Haken, „Für alle Seiten übernehmen“, mit `overlayOpened/Closed` gekoppelt), `NoteSession.setTemplate`; neue Seiten übernehmen die Vorlage der aktuellen Seite
- Datenmodell (`model/`: `Point`, `Stroke`, `Page`, `Note`, `NoteFactory`), noch ohne UI-Anbindung
- Speicherschicht (`storage/`): `PnoteCodec` (`.pnote` als ZIP mit JSON, Lesesicherheit mit Größen-/Mengenlimits, Namens-Allowlist, Versionsprüfung, atomares Schreiben) und `NoteRepository` (Ordner, Papierkorb `.trash/`, Pfadschutz)
- JUnit-Tests (41) für Roundtrip, atomares Schreiben, ZIP-Angriffe und Repository
- Editor-Kern (`editor/`): `PageHistory` (eigener Undo/Redo-Verlauf pro Seite, Differenz-Schritte, Limit 100), `TrackMapper` (`Stroke` <-> `IHWTrack`), `PageCanvasView` (`HWEditView` transparent über `PageBackgroundView`, Stift/Radierer/Lasso, schneller Radierer wie HiNote), `NoteSession` (Seiten, atomares Speichern im Hintergrund, Debounce 5 s)
- `EditorActivity` mit provisorischer Kopf- und Werkzeugleiste; provisorische `MainActivity` mit „Neue Notiz“ und Notizliste (wird in Subtask 5 ersetzt)
- Editor-Oberfläche nach Feedback: Kopfzeile 48dp und Werkzeugzeile 40dp (vorher 56/56), Vollbild ohne Statuszeile; Stift-/Radierer-Leiste als PopupWindow über dem Blatt (verkleinert die Schreibfläche nie), startet eingeklappt, klappt nach dem Schreiben automatisch ein, Pinnnadel hält sie fest (gemerkt in den Preferences)
- App-Icon `PaperNotes BY BOB.png` als Adaptive Icon mit weißem Hintergrund und 22 % Rand, damit die Launcher-Maske keine Ecken abschneidet
- Stift bedient Leisten und Dialoge: Die Fläche der Stift-/Radierer-Leiste wird per `IFBHandSurface.setClip` vom nativen Schreiben ausgenommen (wie HiNote: Blattfläche minus Fensterfläche). Solange ein Dialog oder Menü offen ist, ist das Schreiben mit `setAutoHandWrite(false)` gesperrt.
- Zeichenketten de/en für Editor und Liste; `EditorActivity` nicht exportiert
- Speicherort der Notizen im normalen Benutzerbereich `Documents/PaperNotes` (statt app-privat): `WRITE_EXTERNAL_STORAGE`, `requestLegacyExternalStorage`, Berechtigungsabfrage beim Start
- 32 weitere JUnit-Tests (`PageHistory`, `StrokeGeometry`, `NoteSession`), insgesamt 73
- Stub `HWEditView.cancelSelection()` ergänzt
- Editor-Oberfläche nach HiNote (Subtask 3b): Kopfzeile (Zurück, Titel, `‹ 1/2 ›`, Undo, Redo, Seite hinzufügen, Seitenleiste und Menü als Platzhalter), Werkzeugleiste (Stift, Radierer, Lasso; Textbox, Bild, Vorlage, Mikrofon sichtbar, aber deaktiviert; Seite hinzufügen/löschen), Stift-Leiste (4 Stifttypen, 3 Farben, Größe) und Radierer-Leiste (3 Stile, Größe); selbst gezeichnete Vektor-Symbole
- `EditorToolState` (reine Logik) und `PenCatalog` (einzige Tabelle der Stifttypen, aus dem Dekompilat abgeleitet); Stift- und Radierereinstellungen bleiben in `SharedPreferences` erhalten
- Leisten mit fester Höhe oberhalb der Schreibfläche: Die Schreibfläche ändert Größe und Position nie; keine Animationen/Übergänge im Editor
- 11 weitere JUnit-Tests (`EditorToolState`, `PenCatalog`), insgesamt 84

### Geändert
- Startseite: Sortierung wählbar (Menü `⋮` → „Sortieren nach …“: zuletzt geändert, älteste Änderung zuerst, zuletzt erstellt, Titel A–Z/Z–A; fixierte Notizen bleiben oben, Wahl wird gemerkt, Titelvergleich nach Gerätesprache)
- Startseite: Schrift unter Kacheln kleiner (Titel 14 sp, Datum 12 sp); Popup-Menüs haben die Breite des längsten Eintrags statt einer festen Mindestbreite

- Provisorische Text-Buttons des Editors und nicht mehr genutzte Zeichenketten entfernt


## [0.1.0] - 2026-11-05

### Hinzugefügt
- Projektgerüst mit Multi-Modul-Struktur (`:app` und `:eink-stub`)
- Minimal-Activity (`MainActivity`) mit TextView "PaperNotes by BOB"
- Adaptive App-Icons (schwarz/weiß) und Lokalisierung (Standard Deutsch, Englisch)
- `hw_eink.app.refresh.mode=3` Metadaten im AndroidManifest
- Gradle-Build mit Java 17, minSdk 29, targetSdk 29, compileSdk 36
- Eink-Stub für `android.eink.*` als `compileOnly`-Abhängigkeit
- Automatisches Signieren für Release-Builds über `keystore.properties`
