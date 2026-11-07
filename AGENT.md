# AGENT.md – PaperNotes by BOB

Dieser Leitfaden beschreibt Architektur, Konventionen und Arbeitsregeln für KI-Agenten in diesem Projekt.

## Projektziel

- Eigene Notiz-App für das Huawei Paper (HMW-W09, Android 10, kein Root) als Ersatz für HiNote.
- Flüssiges Schreiben über die geräteeigene E-Ink-Schicht (`android.eink.view.edit.HWEditView`).
- Nextcloud-Sync, PDF-Export und saubere Notizverwaltung mit klassischer View-Architektur (kein Compose).

## Modulstruktur

```
PaperNotes/
├── app/                  – Hauptanwendung (local.papernotes, minSdk 29, targetSdk 29, Java 17)
│   ├── src/main/java/    – App-Quellcode (Kotlin, klassische Views)
│   │   └── local/papernotes/
│   │       ├── model/    – reine Kotlin-Datenklassen (Point, Stroke, Page, Note, NoteFactory), kein Android-Bezug
│   │       ├── storage/  – PnoteCodec (.pnote ZIP+JSON), NoteRepository (Ordner, .trash), BackupPolicy (reine Entscheidungslogik, Intervalle) + BackupStore (`.backup/<noteId>/<zeitstempel>.pnote`, max. 10, Wiederherstellen nur als Kopie), FolderPath, PnoteResult/PnoteError
│   │       ├── editor/   – PageHistory + NoteSession + PageTransform (Abbildung Seite<->Ansicht; Striche liegen in Seitenkoordinaten, `.pnote` Format-Version 2) + EditorToolState + PenCatalog (reine Logik, JVM-Tests; PenCatalog = einzige Tabelle der Stifttypen); TrackMapper, PageCanvasView (einzige Berührung mit HWEditView), PageBackgroundView/TemplateRenderer, TemplateCatalog (einzige Tabelle der Vorlagen) + TemplateDialog + `templates/` (TemplateGeometry = reine Geometrie, ShapeTemplateRenderer, TemplateThumbnails), EditorActivity (Kopf-/Werkzeug-/Unterleiste mit fester Höhe über der Schreibfläche; Layout `res/layout/activity_editor.xml`, Symbole `res/drawable/ic_*.xml`)
│   │       ├── home/     – Startseite-Logik: HomeModel, Pagination, GridMetrics, DateLabels, PreviewLoader/-Cache/-Renderer, HomeTiles, SelectionState + SelectionActions (Mehrfachauswahl; reine Logik mit JVM-Tests)
│   │       ├── hinote/   – Import von HiNote-Exporten (`.hinote`): HinoteBinParser (PENCILENGINE und Altformat HWEINKBYTE, Big-Endian, strikt) + HinoteContainerReader (ZIP-Allowlist, ZIP-Slip, Größen-/Ratenlimits) + HinoteImporter (Mapping nach docs/hinote-bin-format.md Abschnitt 6, `STROKE_WIDTH_FACTOR`, Zielordner `HiNote-Import`) + HinoteImportRunner (Doppelimport über `importSource`, Dedup je `noteId` im Lauf, reine Logik mit JVM-Tests) + HinoteImportPlanner/HinoteFolderScanner (Unterverzeichnis -> Unterordner von `HiNote-Import`, Tiefe 3, keine Symlinks) + HinoteImportFlow (SAF/Documents/hinote/custom, Hintergrund-Thread, Dialoge)
│   │       ├── export/   – PDF-Export: PdfExporter (android.graphics.pdf.PdfDocument, Vektor, 595 pt Breite), PdfLayout (Seitenmaße) + ExportFileNames (Dateinamen; beide reine Logik mit JVM-Tests), ExportLocation (`Documents/PaperNotesPDF`), ExportFlow (Dialoge Format/Ziel, Hintergrund-Thread, Fortschritt, Teilen über FileProvider `cacheDir/export`)
│   │       ├── trash/    – TrashActivity (Papierkorb-Ansicht: wiederherstellen, endgültig löschen) + TrashEntryText/TrashPaging (reine Logik)
│   │       ├── backup/   – BackupsActivity (Sicherungen ansehen, nach Notiz gruppiert, "Wiederherstellen als Kopie", nicht exportiert; Einstellungen: Intervall + "Alle Sicherungen löschen")
│   │       ├── about/    – AboutActivity (Über-Seite, nicht exportiert; Logo `res/drawable-nodpi/ic_launcher_art.png`, Texte in strings.xml unter "Über-Seite") + AboutPolicy (`shouldShowAbout`, reine Logik, JVM-Test)
│   │       ├── MainActivity.kt – Startseite (Raster/Liste, Suche, Menü ⋮, Auswahlmodus; öffnet beim ersten Start nach Installation/Update die Über-Seite); SettingsActivity.kt – Einstellungen (Speicherort, Papierkorb, Version, Info); NoteSharing.kt – Teilen per FileProvider; EinkDialog.kt – Bestätigungsdialog; NotesLocation.kt – Speicherort `Documents/PaperNotes` (gemeinsamer Speicher, `WRITE_EXTERNAL_STORAGE` + `requestLegacyExternalStorage`, targetSdk 29)
│   └── src/test/java/    – JVM-Tests (JUnit 4, `./gradlew test`)
├── eink-stub/            – Compile-Only-Stub für android.eink.* (Namespace local.papernotes.einkstub)
│   └── src/main/java/    – Stub-Signaturen (wird NIE in die fertige APK gepackt)
└── tools/                – Lokale Hilfswerkzeuge (z. B. uber-apk-signer.jar, git-ignoriert)
```

## Build-, Test- und Lint-Befehle

Alle Builds erfordern Java 17 (`openjdk@17` via Homebrew):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home

# Debug-APK bauen
./gradlew assembleDebug

# Release-APK bauen (signiert automatisch, wenn keystore.properties vorhanden)
./gradlew assembleRelease

# Lint ausführen
./gradlew lint

# Alles zusammen (Build + Lint)
./gradlew assembleDebug assembleRelease lint
```

## Signieren

### Variante A – Gradle mit `keystore.properties` (Standard)
Liegt `keystore.properties` im Projektwurzelverzeichnis (git-ignoriert), signiert `./gradlew assembleRelease` automatisch mit `patch-key.jks`.
Erforderliche Property-Schlüssel in `keystore.properties`:
- `PAPYRUS_STORE_PASSWORD`
- `PAPYRUS_KEY_ALIAS`
- `PAPYRUS_KEY_PASSWORD`
*(Optional: `storeFile=patch-key.jks`)*

### Variante B – Manuell per `uber-apk-signer.jar`
```bash
java -jar tools/uber-apk-signer.jar \
  --apks app/build/outputs/apk/release/app-release-unsigned.apk \
  --ks patch-key.jks \
  --ksAlias <PAPYRUS_KEY_ALIAS> \
  --ksKeyPass <PAPYRUS_KEY_PASSWORD> \
  --ksPass <PAPYRUS_STORE_PASSWORD> \
  --verbose
```

## Versionsregel

Bei jeder Versionsänderung:
1. `VERSION` (Projektwurzel) mit neuer Versionsnummer (z. B. `0.2.0`) aktualisieren.
2. `CHANGELOG.md` – neuen Eintrag unter `[Unreleased]` bzw. der neuen Version pflegen.
3. `versionCode` in `app/build.gradle` bei Bedarf erhöhen.
4. `VERSION`-Datei ist die zentrale Quelle für `versionName`.
4a. Die Über-Seite (`about/AboutActivity`) existiert: `about_news_title`, `about_news_items` ("Neu in Version") und `about_description` in `values/strings.xml` UND `values-en/strings.xml` an die neue Version anpassen (Teil der Release-Prüfung). Die Seite erscheint nach einem Update mit geänderter `versionName` automatisch einmal (Preference `about_last_seen_version`).
5. Nach dem Release-Build APKs mit Versionsname nach `dist/` kopieren:

```bash
VERSION=$(cat VERSION)
mkdir -p dist
cp app/build/outputs/apk/debug/app-debug.apk     "dist/PaperNotes-by-Bob-${VERSION}-debug.apk"
cp app/build/outputs/apk/release/app-release.apk "dist/PaperNotes-by-Bob-${VERSION}.apk"
cp app/build/outputs/apk/release/app-release.apk "dist/PaperNotes-by-Bob-${VERSION}-release.apk"
```

## Screenshots und Geräte-Debugging

- `adb` liegt unter `/opt/homebrew/bin/adb`.
- Vor Installation immer mit dem Nutzer abstimmen (nicht selbstständig ungefragt installieren).
- **Screenshots klein halten:** Niemals unkomprimierte PNG-Screenshots direkt mit `read_file` laden. Verkleinern und als JPEG speichern:

```bash
mkdir -p .bob/tmp
/opt/homebrew/bin/adb exec-out screencap -p > .bob/tmp/shot.png \
  && sips -Z 800 .bob/tmp/shot.png --setProperty format jpeg --setProperty formatOptions 60 --out .bob/tmp/shot.jpg \
  && rm .bob/tmp/shot.png
```

## Sicherheitsregeln

1. **Keine Secrets im Code oder Git:** Passwörter, Keystores (`*.jks`, `keystore.properties`, `local.properties`) NIEMALS committen, in Logs ausgeben oder in Markdown-Dateien schreiben.
2. **Pfadsicherheit (Path Traversal):** Alle Dateipfade aus Notiz-Paketen (.pnote / ZIP) müssen kanonisiert (`canonicalPath`) und auf das Zielverzeichnis geprüft werden. Keine Pfade mit `..` zulassen.
3. **Größenlimits:** Beim Entpacken von ZIPs/Dateien strikte Größenbegrenzungen und Kompressionsraten-Prüfungen einhalten (Schutz vor Zip-Bombs).
4. **Compile-Only-Integrität:** Der `:eink-stub` darf nie in die APK gelangen (`compileOnly`). Nach Builds mit `unzip -l` prüfen, dass kein `android/eink` in der APK enthalten ist.

## Arbeitsregeln für KI-Agenten

- **Gradle-Ausführung:** Gradle-Befehle dürfen NUR im Hauptagenten / aktuellen Subtask ausgeführt werden, niemals parallel aus Subagents heraus.
- **Sequentielle Datei-Edits:** Mehrere Änderungen an derselben Datei immer nacheinander durchführen, um Überschreibungen zu vermeiden.
- **Ausgaben kompakt halten:** Logs mit `| tail -40` kürzen, keine riesigen Ausgaben in den Chat spülen.
- **Zweisprachig (Deutsch + Englisch):** Die App muss vollständig auf Deutsch und Englisch laufen. Jeder sichtbare Text (Beschriftungen, Dialoge, Menüs, Fehlermeldungen, `contentDescription`, Notiztitel-Vorgaben wie "Notiz …" und die in Seitenvorlagen gezeichneten Beschriftungen, z. B. "Fertig", "Mo") kommt aus `res/values/strings.xml` (Deutsch, Standard) UND `res/values-en/strings.xml` (Englisch), niemals fest im Code. Neue Strings immer in beiden Dateien anlegen; Datums- und Zahlenformate über das Gerätegebietsschema (`Locale`) formatieren. Vor Abschluss prüfen, dass `lint` keine `MissingTranslation`-Funde meldet.

