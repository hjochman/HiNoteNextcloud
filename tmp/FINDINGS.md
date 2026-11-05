# HiNote APK – Analyse (Zwischenstand)

Quelle: `appBackup/HiNote/HiNote.apk` (package `com.huawei.hinote`, Android-App, dekompiliert mit jadx nach `tmp/jadx_src`).

## Wo liegen die Originaldaten auf dem Tablet?
Im privaten App-Datenverzeichnis (`/data/data/com.huawei.hinote/`), NICHT auf dem normalen Speicher:

| Pfad (relativ zu dataDir)      | Inhalt                                             | Beleg im Code |
|--------------------------------|----------------------------------------------------|---------------|
| `databases/memo`               | greenDAO-SQLite-DB (Notizen, Seiten, Ordner, ...) | `data/e.java` (`"memo"`), `data/gen/*Dao` |
| `files/hwFile/<pageId>.bin`    | Stiftstriche pro Seite (binär)                     | `edit/editor/a/a.java`, `common/utils/b/b.c()` |
| `files/image/`                 | eingefügte Bilder                                  | `cloud/g/a.java` |
| `files/thumbnail/`             | Vorschaubilder                                     | `cloud/g/a.java` |
| `files/importfiles/`           | importierte Dateien                                | `cloud/g/a.java` |
| `record/`                      | Audioaufnahmen (.wav)                              | `cloud/g/a.java` |
| `databases/hinote_search.db`, `sync.db` | Suchindex / Sync-Status                   | `data/l.java` |

Tabellen (greenDAO): `notes`, `note_page`, `category`, `folders`, `attachment`, `page_element`, `note_brush`, `note_lock`, `note_page_bookmark`, `recording_tag`, `trans_text`, `user_config`, `cloud_item_relation`.

## Stiftformat
`.bin` = Big-Endian-Binärformat (`java.io.DataInputStream`), Reader/Writer in
`tmp/jadx_src/sources/b/a/a/a/c/b.java` (IOUtils der Huawei-Stylus-Engine, in der APK enthalten).
Punkte: x, y, Druck, Zeit (float/long), Pinsel-Typ, Farbe, Breite, Bounding-Rect pro Strich.

## Offen / nächste Schritte
- Zugriff auf `/data/data/...`: nicht direkt möglich ohne Root. Optionen: `adb backup` (meist blockiert), App-eigenes Backup/Export, `run-as` (nur debuggable), HiNote-„Backup/Restore“ (`BackupRestoreJobService`, `common/dbprotection`) → prüfen wohin das schreibt.
- Format der `.bin` vollständig dokumentieren, mit echter Beispieldatei vom Tablet verifizieren.
- `appBackup/HiNoteHm/*.hap|apk` sind nur kleine HarmonyOS-Widget-Module (notes2x2), keine Datenhaltung.

---
## Ergebnisse vom echten Tablet (adb, Serial TGPYD22602200007)

- Gerät: HMW-W09 (Huawei Paper), Android 10 / EMUI 12, build type `user`, adb-Shell uid=2000 (kein Root).
- Installiert: `com.huawei.hinote` **12.4.3.395** (System-App, Update-Stand 2025-12-05; die APK in appBackup ist 12.3.0.372, also älter) und `com.huawei.ohos.hinote` (Widget-Service).
- `/data/data/com.huawei.hinote` -> Permission denied, `run-as` -> not debuggable. Originaldaten im Private-Dir sind ohne Root nicht lesbar.
- Auf dem öffentlichen Speicher gibt es KEIN automatisches Abbild der Daten. Nur Exporte:

| Pfad auf dem Tablet                                   | Inhalt |
|-------------------------------------------------------|--------|
| `/sdcard/Documents/hinote/custom/<Titel>_<yyyyMMddHHmm>_<n>.hinote` | Vollexport einer Notiz im Originalformat (ZIP) |
| `/sdcard/Documents/*.pdf`, `/sdcard/hinote/export/*.pdf` | PDF-Exporte (alte Versionen/andere Wege) |

### Format `.hinote` (ZIP) – Probe in `tmp/device/sample.hinote`, entpackt in `tmp/device/x/`
```
<noteId>.jhinote             gzip(JSON)  Notiz-Metadaten (customNoteContent: Titel, pageRatio, noteType, ...)
pages/<pageId>.jhinote       gzip(JSON)  Seite (pageNumber, attachment[], pageElement[], thumbnail, fileList[] mit SHA-256)
files/<pageId>.bin           Stiftstriche, Magic "PENCILENGINE", Big-Endian
files/<pageId>.jpg           Seiten-Vorschau/Hintergrund (903x1080)
custom_md.jhinote            gzip(JSON)  Hashliste (SHA-256 von Datei und Dateiname)
```
Das JSON verweist per `filePath` auf `/data/data/com.huawei.hinote/files/hwFile/<pageId>.bin`; die `.bin` im Export hat denselben Dateinamen. Byte-Gleichheit ist NICHT geprüft (Private-Dir nicht lesbar).
Die 12.3.0.372-Quellen enthalten keinen String `jhinote`/`custom_md` (grep geprüft). Die 12.4.3.395-APK vom Tablet wurde später gelöscht (Dekompilat: `tmp/jadx_src_124`, Ressourcen: `tmp/device/apk124/res`). Bei Bedarf neu vom Tablet ziehen (`adb shell pm path com.huawei.hinote`).

### Konsequenz
Die Originaldaten kommen ohne Root nur per `.hinote`-Export vom Tablet (manuell in der App, ggf. automatisierbar). Der Ordner `/sdcard/Documents/hinote/custom/` kann von der Nextcloud-Android-App per "Auto-Upload"/Ordner-Sync gesichert werden. Die APK 12.3 kennt das `.hinote`-Exportformat nicht (kein String `jhinote`/`custom_md`), es ist neu in 12.4.x -> neuere APK vom Tablet ziehen (`pm path com.huawei.hinote` -> `/data/app/com.huawei.hinote--*/base.apk` ist per adb lesbar) und dort Export/Import-Code analysieren.

---
## Export-Automatisierung: Analyse 12.4.3 + UI-Test auf dem Tablet

### Von aussen nicht auslösbar (Quellcode 12.4.3, Manifest geprüft)
- `NotePadProvider`, `HiNoteBackupContentProvider`, `SystemBackupContentProvider`: Berechtigungen `signatureOrSystem` -> gesperrt.
- `SyncContentProvider` (`com.huawei.hinote.cloudSync`) ist exportiert, aber `checkCallingValidate` verlangt Paketname + Signatur von HiCloud -> gesperrt. Er kennt zudem nur `sync_upload.zip` für HiCloud, keinen `.hinote`-Export.
- Kein exportierter Intent/Service, der `ExportManager`/`CustomPolicy` (Klassen für den .hinote-Export) startet. Exportierte Activities (`ShareActivity`, `HomeLauncherActivity`, `FaFoward`) nehmen nur Import-/Öffnen-Extras.
- `allowBackup=false`, `run-as` nicht möglich, kein Root.

### UI-Test (nur Navigation, es wurde NICHTS exportiert oder verändert)
- Startseite "Alle Notizen" (33 Notizen, teils in Ordnern, z.B. "BIT" mit 18 Notizen). Resource-IDs sind stabil: `toolbar_more`, `batch_manager`, `toolbar_select_all`, `toolbar_export`, `grid_item_menu`, `menu_name`, `item_title`.
- Mehrfachauswahl -> "Exportieren": Dialog bietet nur Bild/PDF/TXT, **kein HiNote** (`NoteExportDialog.v()` blendet es bei Mehrfachauswahl aus).
- Einzelne Notiz -> Menü `grid_item_menu` -> "Exportieren": Dialog bietet Bild/PDF/**HiNote**/TXT. HiNote-Export geht also nur pro Notiz.
- Zielordner fest: `Environment.DIRECTORY_DOCUMENTS/hinote/custom` (`NoteExportDialog`, `NoteBatchExportDialog`).
- `enabled_accessibility_services` ist leer; Tasker (`net.dinglisch.android.taskerm`) und Nextcloud (`com.nextcloud.android.beta`) sind installiert.

### Werkzeug
- `tmp/ui.sh <name>`: uiautomator-Dump + Screenshot nach `tmp/device/ui/`, druckt klickbare Elemente.
- Validierung: alle exportierten .hinote sind gueltige ZIPs mit je Seite einer .bin (`unzip -t`). Achtung: Dateinamen mit fuehrendem "-" (Notiz "- Alle Pipeline") brauchen `./` vor dem Namen.

---
## Spike Stift-Latenz (Teilaufgabe 1): Fremd-App mit `android.eink.*`

Projekt: `tmp/penspike/` (Gradle, AGP 9.5.0, compileSdk 36, targetSdk 29, minSdk 29). Zwei Module: `app` und `eink-stub` (nur `compileOnly`, eigene Stub-Signaturen, nicht in der APK; mit `dexdump` geprüft: keine `android/eink`-Klassen in der APK). Die Stubs sind selbst geschrieben und deklarieren nur die benötigten Methoden. Der Huawei-Code wurde nur als Anleitung gelesen.

### Ergebnis: geht
Eine normale Fremd-App (kein System-App, kein Root, `targetSdk 29`) kann `android.eink.view.edit.HWEditView` nutzen. Der Nutzer bestätigte, dass die Striche mit B (HWEditView) sich so flüssig wie in HiNote anfühlen und mit A (normale SurfaceView) spürbar träger.

### Belege
| Frage | Ergebnis | Beleg |
|-------|----------|-------|
| `<uses-library>` nötig? | Nein | `eink.framework.jar` liegt im `BOOTCLASSPATH` (`/system/framework/eink.framework.jar`). `pm list libraries` führt es nicht auf. Das HiNote-Manifest hat kein `uses-library`. In der App: `Class.forName` für `HWEditView`, `HWSurfaceView`, `HandPen`, `FBHandSurface` erfolgreich, Loader `BootClassLoader`. |
| Hidden-API-Sperre | Keine Meldung | Kein `hiddenapi`/`Accessing hidden` im Logcat (3153 Zeilen, `tmp/penspike/shots/logcat_full.txt`). `HWSurfaceView` greift selbst per Reflection auf `SurfaceView`-Felder zu, das lief ohne Fehler. |
| UnsatisfiedLinkError / SecurityException / Crash | Keine | `grep` auf `UnsatisfiedLink`, `SecurityException`, `FATAL`: 0 Treffer. Natives `FBHandSurface.nativeCreate` lief durch (Konstruktor von `HWEditView` erfolgreich). |
| SELinux | Keine Denials für die App | Keine `avc: denied` mit `penspike` oder `untrusted_app`. Gefundene Denials betrafen nur `shell` und `system_server` (adb). |
| Surface | Funktioniert | `HWEditView surfaceCreated`, `surfaceChanged 1404x1562`. |
| Stifteingabe | Funktioniert | `IAhwTouchListener.onAhwAddPoint` lieferte DOWN/UP mit Koordinaten. Der Screenshot `tmp/penspike/shots/B_after_injected_stylus.png` zeigt einen gezeichneten Strich. |

### Einschränkungen (nicht belegt)
- Die Latenz ist nur subjektiv vom Nutzer beurteilt, nicht gemessen. Im Test A zeigt die App die Zustellverzögerung der Events an. Den Wert hat der Nutzer nicht genannt.
- Der Strich im Screenshot B passt geometrisch nicht zu meinem `input stylus swipe`. Er stammt vermutlich vom echten Stift des Nutzers. Ein per `adb` injizierter Stift-Swipe erzeugte in A keinen Strich. Die adb-Injektion ist daher nicht als Test geeignet.
- Nicht geprüft: Radierer (`STATE_ERASE`), Lasso, `getTrackList()`, Speichern und Laden von Strichen, Verhalten bei Seitenwechsel oder Zoom, Pen-Typen, E-Ink-Refresh-Modus (HiNote setzt `hw_eink.app.refresh.mode=3` per Manifest-Metadaten), Verhalten nach App-Pause oder Rotation.
- Die Stub-Signaturen sind aus dem Dekompilat abgeleitet. Mit anderen Firmware-Ständen kann sich das Framework ändern.
- Test nur auf diesem Gerät (HMW-W09, EMUI 12, Android 10).

### Konsequenz
Teilaufgabe 7 (eigene Notiz-App) ist technisch machbar, was die Stift-Latenz betrifft. Offen bleibt der Aufwand für die restlichen Funktionen (Format-Schreiber, Radierer, Lasso, Zoom, UI).

---
## Recherche: Basis-App für die eigene Notiz-App (Stand 2026-11-05)

Quellen: GitHub-API und Klone per curl, Klone in `tmp/research/src/`. Die Tabelle stammt aus README und Quellcode. Der Praxistest auf dem Paper steht im Abschnitt "Latenztest xnotes und Saber" weiter unten.

| | xnotes-android | Saber | Folio |
|---|---|---|---|
| Lizenz | MIT | GPL-3.0 | Apache-2.0 |
| Technik | Kotlin, eigene OpenGL-Pipeline, native PDFium | Flutter/Dart, `perfect_freehand` | Kotlin Multiplatform |
| Alter / Pflege | seit 2026-05, Release v0.9.7 (2026-10-31), 1150 Commits vom Hauptautor | seit 2022, v1.37.1 (2026-10-28), 3664 Commits vom Hauptautor, 421 Forks | seit 2026-10-19, kein Release, ein Beitragender, 60 Commits |
| Umfang | ca. 104.000 Zeilen Kotlin | ca. 23.600 Zeilen Dart | ca. 20.000 Zeilen Kotlin |
| minSdk | 26 | Flutter-Standard (nicht geprüft) | 25 |
| Format | `.xnote` = ZIP mit `manifest.json` (Striche als Samples x, y, Druck, Zeit) | `.sbn2` = BSON | `.folio` (nicht geprüft) |
| PDF-Export | ja, Vektor (README) | ja (`editor_exporter.dart`) | ja (README) |
| Nextcloud-Sync | nein: keine INTERNET-Berechtigung, kein WebDAV im Code | ja, eingebaut (`saber_syncer.dart`, Paket `nextcloud`), mit Verschlüsselung (`encrypt`) im Sync-Code; ob abschaltbar, ist nicht geprüft | "optional sync" laut README, nicht geprüft |
| E-Ink | Eigener Front-Buffer-Weg über `EGL_KHR_mutable_render_buffer` (`GlWetPad.kt`). Der Kommentar bezieht sich auf das Gerät des Autors. Ob das Paper die Erweiterung bietet, ist ungeprüft. | Plugin `onyxsdk_pen` nur für Onyx/Boox, auf anderen Geräten wirkungslos | nichts gefunden |
| Huawei-Framework | kein Treffer auf `android.eink`/`HWEditView` | kein Treffer | kein Treffer |

Weitere Funde: `bamonroe/NeXopp` (GPL-2.0, 4 Sterne) liest und schreibt Xournal++-Dateien (`.xopp`). `huuunleashed/BooxUltimatum` ist Boox-spezifisch. `jdkruzr/ForestNote` ist archiviert.

### Bibliotheken (Pflege-Check, Regel: Commit in den letzten 6 Monaten)
- `nextcloud/android-library`: aktiv (letzter Push 2026-11-05, Release 2.26.1). Lizenz laut GitHub-API "NOASSERTION", also vor Nutzung prüfen.
- `thegrizzlylabs/sardine-android` (WebDAV): letzter Push 2024-02, fällt durch.
- `TomRoush/PdfBox-Android`: letzter Push 2024-03, fällt durch.
- PDF ohne Zusatzbibliothek wäre mit der Android-eigenen `PdfDocument`-API möglich (Annahme aus Erfahrungswissen, hier nicht getestet).

### Was `HWEditView` schon mitbringt (Quelle: Dekompilat `tmp/device/fw/eink_src`)
`HWEditView` hat öffentliche Methoden für `undo`, `redo`, `canUndo`, `canRedo`, `erase`, `copy`, `cut`, `paste`, `copySelection`, `cutSelection`, `getTrackList`, `setTrackList`, `capture`, Zustände Schreiben/Radieren/Auswahl. Die Strich-Engine, der Radierer, Lasso und Undo kommen also vom Framework. Eine eigene App müsste vor allem Seiten-UI, Speicherung, PDF und Sync liefern. Das ist aus der Methodenliste abgeleitet und nicht praktisch getestet.

### Übernahme der vorhandenen HiNote-Notizen
Alle drei Formate speichern Striche als Punktfolgen mit Position, Druck und Zeit. Eine einmalige Konvertierung `.hinote` -> Zielformat ist daher plausibel, setzt aber den `.bin`-Parser aus Teilaufgabe 2 voraus. Das ist eine Einschätzung, nicht geprüft.

### Latenztest xnotes und Saber (Release-APK auf dem Paper, 2026-11-05)
- Installiert wurden `xnotes-0.9.7.apk` (`com.xnotes`) und `Saber_FOSS_v1.37.1.apk` (`com.adilhanney.saber`). Beide SHA-256-Werte entsprachen den GitHub-Digests. Beide starteten ohne `FATAL EXCEPTION`.
- Bewertung durch den Nutzer: **bei beiden Apps ist die Latenz sehr groß.** Zum Vergleich: Variante B im Spike (`HWEditView`) fühlte sich so flüssig an wie HiNote.
- Das Urteil ist subjektiv, es wurde nichts gemessen.
- Bei xnotes ist ungeklärt, ob der Front-Buffer-Weg (`GlWetPad.kt`) auf dem Paper greift. Die Prüfroutine schrieb nichts ins Log, und im Log stand `ITouchService: itouch set_config … com.xnotes#-1#0 fail`. Die Bedeutung ist nicht geklärt. Die Latenz von xnotes lässt sich also nicht ohne Weiteres durch Umbau am Renderer beheben. Das ist eine Vermutung.
- Saber hat laut Quellcode keinen Weg für das Paper (Onyx-Plugin wirkungslos).

### Konsequenz
Keine der beiden Apps ist unverändert brauchbar. Bleiben zwei Wege:
- Weg 1: eigene App auf `HWEditView`. Stift-Latenz ist durch den Spike belegt gut.
- Weg 2: xnotes oder Saber als Basis und das Schreiben auf `HWEditView` umbauen. Das bedeutet, die Zeichenschicht einer fremden Code-Basis auszutauschen (xnotes ca. 104.000 Zeilen Kotlin mit eigener GL-Pipeline, Saber Flutter mit GPL-3.0 und eigenem Strichmodell). Einschätzung, nicht erprobt: Man müsste das Strichmodell der Apps auf `getTrackList`/`setTrackList` abbilden oder doppelt halten. Der Gewinn gegenüber Weg 1 liegt dann vor allem in fertigem Seiten-UI, Format und PDF-Export.

### Offene Prüfungen
1. Ob `HWEditView` für die Eigenbau-App ausreicht: zweiter Spike für Radierer, `getTrackList`/`setTrackList` (Speichern und Laden), Zoom, Rotation und E-Ink-Refresh-Modus. Das ist nicht geprüft.
2. Bei Saber klären, ob die Sync-Verschlüsselung abschaltbar ist (nur wichtig, wenn Saber Basis würde).
3. Lizenz von `nextcloud/android-library` klären.

### Vergleich xnotes / Saber: Codeumfang, Text, Format (Quellcode gelesen, 2026-11-05)
- Zeilenzahlen sind nicht direkt vergleichbar. Saber `lib/`: 50.449 Zeilen Dart, davon 26.801 generierte Übersetzungen (`lib/i18n`). Eigener Code: ca. 23.600 Zeilen. Flutter-Framework und Pakete (u. a. `flutter_quill`) zählen nicht mit. xnotes: 103.600 Zeilen Kotlin (ui 34.321, core 31.850, canvas 12.596, platform 9.076, format 7.516, gl 5.649), dazu gebündeltes PDFium in C/C++. xnotes bringt Strich-Engine, GL-Rendering, Textlayout (`core/text`, 5.664 Zeilen), Formen (3.946), Audio, Tape, Tabellen und PDF-Anbindung selbst mit.
- Text in Saber: ja, über `flutter_quill`. Jede Seite (`EditorPage`) hat genau ein Quill-Dokument (Feld `q` im JSON, ab Format v6). Frei platzierte Textboxen habe ich nicht gesehen, aber nicht gezielt gesucht. xnotes: `TextItem` plus Markdown, Tabellen, LaTeX laut README.
- Saber-Format `.sbn2`: BSON, Version 19, Assets in Nebendateien (`.sbn2.1`), Paket `packages/sbn` als separate Format-Bibliothek. Sync: Dateinamen und Inhalte werden verschlüsselt (`.sbe`, `encrypter` aus Passwort). Einen Schalter zum Abschalten habe ich nicht gefunden (nicht vollständig gesucht).
- xnotes-Format `.xnote`: ZIP mit `manifest.json` (deflated) und Assets unter `assets/`. Eine Formatspezifikation liegt im Repo nicht, nur der Code (`DocumentCodec.kt` 1.994 Zeilen, `CanvasCodec.kt` 1.395 Zeilen).

---
## Spike 2: HWEditView als Stiftschicht (Teilaufgabe 7, 2026-11-05)

Code: `tmp/penspike/app/src/main/java/local/penspike/Spike2Activity.java`, Stubs für `IHWTrack`, `IHWTrackCreator`, `HWPoint`, `IHWEditParam` und weitere Methoden in `tmp/penspike/eink-stub`. Die Stubs liegen nur als `compileOnly` im Build und sind nicht in der APK (`unzip -l` zeigt keinen Eintrag `android/eink`). Auswertung per `adb logcat` (Tag `PenSpike2`) und `capture()`-Screenshots in `tmp/penspike/shots/files2/`.

### Automatisch belegt (synthetische Tracks, kein Stift)
- **HWEditView liegt transparent über einer fremden View.** Die darunterliegende Gitter-View war im Bildschirmfoto sichtbar, die Striche lagen darüber. `capture()` liefert nur die Striche (Hintergrund transparent), nicht das Gitter.
- **Eigene Tracks setzen:** `getHWTrackCreator().createHWTrack(...)` und `createHWPoint(...)` mit eigenen Punkten, dann `setTrackList(list)`. 3 Tracks mit 600 Punkten wurden gezeichnet (Log: `Tracks=3, Punkte=600`).
- **Speichern und Laden:** `getTrackList()` lieferte Typ, Breite, Farbe sowie x, y, Zeit und Druck je Punkt. Als JSON geschrieben (10 Tracks, 22.744 Bytes), `clear()`, wieder gelesen und `setTrackList()`: identische Anzahl Tracks und Punkte (10 / 412).
- **Radierer per Code:** `erase(Path)` über ein Rechteck zerlegte 3 Tracks in 10 Tracks mit zusammen 412 statt 600 Punkten. Striche werden also geteilt und nicht nur ganz gelöscht. Der `OnEditListener` meldete `type=0`.
- **Undo/Redo:** `undo()` stellte 3 Tracks / 600 Punkte wieder her, `redo()` die 10 Tracks. `canUndo`/`canRedo` und `onEdit` (Typ 1 und 2) stimmten.
- **Zoom:** Punkte und Strichbreite umgerechnet und neu gesetzt (Breite 4,0 -> 8,0). Das funktioniert, ist aber ein kompletter Neuaufbau der Track-Liste. Wie sich das bei vielen Strichen anfühlt, ist nicht gemessen.

### Beobachtete Einschränkungen (aus Log und Dekompilat)
- `setTrackList()` und `clear()` löschen den Undo-Verlauf (`canUndo=false` nach dem Laden). Das passt zum Dekompilat `edit/d.java` (`a(List)` leert den Verlauf). Eine App muss ihren Verlauf also selbst führen oder das akzeptieren.
- `HWEraserView` ruft `setZOrderOnTop(true)` auf (Dekompilat). Menüs oder Dialoge der App lägen daher potenziell unter der Schreibfläche. Das ist nicht getestet.
- Zoom und Scrollen kennt `HWEditView` nicht. Die App muss die Tracks selbst umrechnen.

### Handtest mit Stift (Nutzer, 2026-11-05)
- **Schreiben über dem Gitter:** flüssig.
- **Lasso:** "etwas träge, aber ähnlich wie HiNote".
- **Radierer, erste Konfiguration** (Standardstil, kein `OnEraserPrepareListener`, Rand an): "sehr träge", funktioniert aber.
- **Radierer, zweite Konfiguration** (`setEraserStyle` 0, 1 oder 2, Radius 20, bei Stil 0 und 1 `FBPicture.bindColor(WHITE)` im Prepare-Listener): alle drei Stile schnell. `setEraserShowBorder` macht keinen Unterschied. Bei Stil 0 und 1 wird der Radierkreis nicht angezeigt, radiert wird trotzdem.
- Nicht getestet: Finger-Events am Parent, Gefühl nach Zoom.

### Erklärung aus dem Dekompilat, durch Gegenprobe bestätigt
- `HWEraser.b()` wählt den Pfad nach Stil und gebundenem `FBPicture`. Ist `FBPicture` auf Schwarz oder Weiß gebunden, ist `canSuperSpeedUp()` wahr, und der Bildpuffer wird mit dem Modus `2097152` ausgegeben. Ohne Bindung läuft der Standardpfad mit dem Modus `524290 | EINK_Y1_MODE_DISABLE`. Das passt zum Unterschied zwischen "sehr träge" und "schnell".
- Der Radierkreis (`r`) wird nur gesetzt, wenn Rand an, Stil 0 und `!canSuperSpeedUp()` gilt. Bei Stil 0 mit gebundenem Weiß und bei Stil 1 fehlt er daher, das passt zur Beobachtung. Stil 2 (Range) läuft über einen eigenen Pfad (`HandPen`, `eraseRange`). Was dort angezeigt wird, hat der Nutzer nicht beschrieben.
- HiNote bindet in `EinkEngine` den Hintergrund selbst: `bindColor(mBackgroundColor)` (Standard `-1`, also Weiß), sonst `bindBitmap(...)`. Es ruft außerdem `setEraserShowBorder(false)`. HiNote nutzt damit denselben Schnellpfad.
- Für eine eigene App heißt das: den Radierer wie HiNote konfigurieren (weißer Hintergrund gebunden). Der Radierkreis müsste dann selbst gezeichnet werden, falls er gewünscht ist. Das ist ein Folgepunkt.
- Gegenprobe (Schaltfläche "Rad. Stil 0 OHNE Hintergrund", Stil 0 ohne gebundenen Hintergrund): Der Nutzer meldet den Radierer wieder träge und den Radierkreis wieder sichtbar. Das stützt die Erklärung. Es ist ein subjektives Urteil ohne Messung.

### Ergebnis Spike 2
Für eine Eigenbau-App ist belegt: Schreiben, Speichern und Laden der Tracks, Radieren, Undo/Redo und das Lasso funktionieren, und `HWEditView` liegt über einer eigenen View. Der Radierer ist nur schnell, wenn der Hintergrund per `FBPicture.bindColor(Weiß)` gebunden ist (dann fehlt der Radierkreis). Offen sind Zoom-Verhalten in der Praxis, ein selbst gezeichneter Radierkreis, das Verhalten von Dialogen über der Schreibfläche (`setZOrderOnTop`) und der Undo-Verlauf nach `setTrackList`.

## HiNote-Oberfläche (Referenz für den Neubau)
Version 12.4.3.395. Screenshots in `tmp/device/ui/` (nicht im Git). Launcher-Activity `com.huawei.hinote/.HomeLauncherActivity`, Startseite `.hmwnoteshelf.activity.HmwFolderActivity`, Editor `.edit.EditActivityInk`, Vorlagenwahl `.noteshelf.activity.InkNoteTemplateActivity`.

- **Startseite** (`home.png`): Titel "Alle Notizen" mit Anzahl. Oben rechts Sync (Wolke), Neu (+), Suche, Umschalter Liste/Raster, weiteres Menü. Ordner als Kacheln mit bis zu vier Mini-Vorschauen. Darunter Name, Anzahl und Datum. Unten Seitenwechsel ("Vorherige Seite 2/2 Nächste Seite") statt Scrollen.
- **Ordneransicht** (`note.png`): Raster mit 4 Spalten, erste Kachel "+ Kurznotiz", danach Notizkarten mit Vorschau, Titel und Datum (Uhrzeit heute, "6. Oktober" in diesem Jahr, sonst volles Datum). Menü pro Notiz.
- **Notiz-Menü** (`nmenu.png`): Umbenennen, Favoriten, Oben fixieren, Kopieren, Verschieben, Teilen, Exportieren, Verschließen, Löschen.
- **Editor** (`ref_editor.png`): Kopfzeile mit Zurück, Titel, Seitenwechsel "1/2", Undo, Redo, Seite hinzufügen, Seitenleiste, Menü. Werkzeugleiste: Stift, Radierer, Lasso, Textbox, Bild | Vorlage, Seite hinzufügen, Seite löschen | Audioaufnahme.
- **Stift-Leiste** (`ref_pen.png`): Füllfeder, Kugelschreiber, Bleistift, Marker | Schwarz, Grau, Weiß | Größe mit −, Schieber, +.
- **Radierer-Leiste** (`ref_eraser.png`): Standard, Fläche, Striche löschen | Größe.
- **Seitenleiste** (`ref_side.png`): Reiter "Seiten" und "Lesezeichen", Sprungfeld, Seitenvorschauen mit Menü und Lesezeichen, "Neue Seite".
- **Menü ⋮** (`ref_more.png`): "Handschrift zu Text", Seiteninhalt löschen, Seite löschen; Teilen, Exportieren, Drucken; Schloss hinzufügen.
- **Vorlagen** (`ref_bg.png`): Grundlage (Leer, Breite Linien, Schmale Linien, Gepunktet), Schreiben (Handschriftpapier, Millimeter, Gitter, Brief), Pläne (To-do, Besprechung, Wochenplan, Monat), Arbeit und Bildung (Aufgaben, Fehlerprotokoll, Lesenotizen, Cornell).
- Nicht erfasst: Unterleisten von Lasso, Textbox und Bild.
- Berechtigungen im Manifest: Mikrofon, Kamera, `MANAGE_EXTERNAL_STORAGE`, Internet.

## Spike 3: Handschrifterkennung aus einer Fremd-App (2026-11-05)
Der Code liegt in `tmp/penspike/.../Spike3Activity.java` (eigener Code). Das Dekompilat diente nur als Anleitung für die Aufrufreihenfolge.

**Wie HiNote die Erkennung nutzt (Dekompilat):**
- `HwRecognizeEngineProxy` kopiert `/hw_product/app/HwStylusFeature/PenKitRecg-master.apk` in den Cache, hängt sie in den ClassLoader ein und lädt `com.huawei.penkit.feature.recg.HwRecognizeEngineImpl`. Aufrufe: `init(Context, "de_DE")`, dann `recognize(float[] x, float[] y, int[] status)`.
- Der Parameter `status` markiert je Punkt: 0 = Strichanfang, 1 = Strichende, 2 = dazwischen (`InkHandwritingInfo.c`). Marker (Stifttyp 6), Striche mit weniger als 2 oder mehr als 3000 Punkten und Listen über 20000 Strichen werden ausgelassen.
- Das Ergebnis ist ein JSON-String, aus dem HiNote das Feld `label` liest (`StylusRecognizeResultUtil.a`).

**Was belegt ist (Logcat auf dem Tablet):**
1. Die APK ist für eine normale App lesbar und lässt sich in den Cache kopieren (54.788.787 Bytes).
2. `DexClassLoader` lädt `HwRecognizeEngineImpl`, sobald die App das Interface `com.huawei.stylus.penengine.feature.recg.IRecgFeature` (plus `IFeature`) bereitstellt. Ohne dieses Interface schlägt das Laden mit `NoClassDefFoundError` fehl.
3. Beim Aufruf von `init` verlangt die Engine drei weitere Host-Klassen: `com.huawei.penkit.impl.utils.Logger`, `FileUtil`, `ReporterTool`. Das ist per `dexdump` belegt. Mit eigenen, minimalen Ersatzklassen läuft `init` bis zur Prüfung.
4. **Dann bricht die Engine ab:** `E PenKit/RecognizeControl: The app isn't in white list.` und `Recognize init fail, this hostApp is not allowed to recognize`. `init` liefert `null`, `recognize` kam nicht zum Zug.

**Einordnung (Vermutung, nicht belegt):** Die Engine prüft den Aufrufer, vermutlich über Paketname und Signaturzertifikat gegen `hinote_pubkey` in `assets/config.properties`. Die Strings `can't get signature`, `can't get public key` und `can't get the app's certificate` stehen im Dex. Die genaue Prüflogik habe ich nicht ausgewertet. Die Prüfung zu umgehen (zum Beispiel HiNotes Paketnamen oder Signatur vortäuschen) halte ich für nicht vertretbar. Die Engine ist proprietär (MyScript) und die Sperre ist offenbar gewollt. Ich verfolge das nicht weiter.

**Folgerung:** Mit der Huawei-Engine ist die Schrifterkennung für eine eigene App nicht nutzbar. Alternativen, jeweils nicht geprüft:
- Erkennung im HiNote-Dienst auslösen, indem HiNote die Notiz öffnet. Das setzt HiNote als Begleiter voraus.
- Eine frei lizenzierte Erkennung (zum Beispiel Google ML Kit Digital Ink Recognition, braucht Google Play Services, auf dem Paper nicht vorhanden; oder eine Open-Source-Engine) einsetzen. Dafür ist eine eigene Recherche nötig.
- Die Volltextsuche später serverseitig über das Nextcloud-Plugin ermöglichen.

---
## Prototyp: tmp/export_all.py (adb + uiautomator)
- Getestet auf dem Tablet (entsperrt, Ordneransicht "BIT"): 11 Notizen -> 11 gueltige .hinote in ca. 2 Min.
- Je Notiz: `grid_item_menu` -> `menu_name`="Exportieren" -> `item_title`="HiNote"; Ende erkannt ueber neue Datei in `/sdcard/Documents/hinote/custom`; danach BACK (HiNote oeffnet den Dateimanager).
- adb-Verbindung brach einmal kurz ab (Retry mit `wait-for-device` eingebaut). Ein abgebrochener Lauf laesst Dialoge offen (wird beim Start weggeraeumt).
- Dateiname: Zeitstempel + Zufallszahl, "/" im Titel wird entfernt. Jeder Lauf erzeugt neue Dateien -> Duplikate, Dedup/Aenderungserkennung noetig (`modifiedTime` im JSON oder SHA-256 in `custom_md.jhinote`).
- NOCH NICHT abgedeckt: Scrollen der Liste (BIT hat 18, sichtbar 11), Ordner-Navigation, Notizen im Wurzelverzeichnis, verschlossene Notizen (Sperre/PIN), Ordnerkarten (`ib_folder_menu`).
