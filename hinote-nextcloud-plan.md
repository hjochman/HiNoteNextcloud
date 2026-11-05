# HiNote -> Nextcloud: Plan

> **Stand 2026-11-05:** Der Nutzer hat entschieden, eine eigene Notiz-App zu bauen ("wir schreiben selber"). Der Neubau steht in [`notiz-app-plan.md`](notiz-app-plan.md). Teilaufgabe 7 wird dort geführt, Teilaufgabe 4 entfällt (außer als Einmal-Export). Die übrigen Teilaufgaben bleiben hier.

## Überblick

**Ziel:** Die Notizen der HiNote-App (Huawei Paper, HMW-W09) regelmäßig im Originalformat nach Nextcloud sichern. Dazu kommen ein Nextcloud-Plugin zum Anzeigen mit PDF-Export und ein lokales Tool für den PDF-Export aus den Originaldaten.

**Ausgangslage (belegt, Details in `tmp/FINDINGS.md`):**
- Die Originaldaten (DB `memo`, `files/hwFile/<pageId>.bin`) liegen im privaten App-Verzeichnis `/data/data/com.huawei.hinote`. Ohne Root nicht lesbar (`Permission denied`, `run-as` nicht möglich, `allowBackup=false`).
- HiNote 12.4.3.395 kann pro Notiz als `.hinote` exportieren. Ziel: `/sdcard/Documents/hinote/custom/`. Bei Mehrfachauswahl fehlt die Option "HiNote" im Dialog.
- `.hinote` ist ein ZIP mit: `<noteId>.jhinote` (gzip-JSON), `pages/<pageId>.jhinote` (gzip-JSON), `files/<pageId>.bin` (Stiftstriche, Kennung `PENCILENGINE`, Big-Endian), `files/<pageId>.jpg`, `custom_md.jhinote` (SHA-256-Hashes).
- Kein Export-Auslöser von außen: Provider sind signaturgeschützt, es gibt keinen exportierten Intent oder Service für den Export.
- Prototyp `tmp/export_all.py` (adb + uiautomator) exportierte 11 Notizen des Ordners "BIT" in etwa 2 Minuten. Alle Ergebnisse waren gültige ZIPs.
- Das Tablet muss für die UI-Automatisierung entsperrt und der Bildschirm an sein. Ein Accessibility-Service kann die Sperre nicht umgehen. Eine mögliche Lösung ist "Aktiv lassen" am Netzteil. Das ist nicht getestet.
- Die niedrige Stift-Latenz von HiNote kommt vom Framework `/system/framework/eink.framework.jar` (`android.eink.*`: `HWEditView`, `HWSurfaceView`, `HandPen`). Die APKs von INKredible, Squid, Royole und JNotes enthalten keine Treffer darauf. Eine Fremd-App kann diese Klassen nutzen (Spike 1 und 2, siehe `tmp/FINDINGS.md`).

**Entscheidungspunkte:**
1. Spike-Ergebnis (Teilaufgabe 1): positiv. Der Nutzer hat sich für die eigene App entschieden (Teilaufgabe 7, siehe `notiz-app-plan.md`).
2. Teilaufgaben 2, 3, 5 und 6 (Parser, PDF, Sync, Plugin) liefern auch bei einer eigenen App Wert. Teilaufgabe 4 (Export automatisieren) entfällt, außer als Einmal-Export der vorhandenen Notizen.

## Teilaufgaben

### 1. Spike: Stift-Latenz in einer eigenen App
**Intent:** Prüfen, ob eine Fremd-App die E-Ink-Framework-Klassen nutzen kann und flüssig schreibt. Das entscheidet, ob Teilaufgabe 7 machbar ist.

**Erwartetes Ergebnis:**
- Eine Mini-App läuft auf dem Tablet, zeigt eine `HWEditView` oder `HWSurfaceView` und nimmt Stiftstriche auf.
- Dokumentiert ist, ob die Klassen sichtbar sind (Hidden-API, `uses-library`), ob native Aufrufe funktionieren und wie die Latenz im Vergleich zu HiNote wirkt.
- Eine klare Aussage: geht oder geht nicht, mit Fehlermeldungen als Beleg.

**Todo:**
- [x] Android-Projekt anlegen (SDK unter `/opt/homebrew/share/android-commandlinetools`, JDK 17, Gradle). Ziel Android 10 (API 29).
- [x] Aus `tmp/device/fw/eink.framework.jar` ein Compile-Stub für `android.eink.*` ableiten (compileOnly, nicht in die APK packen).
- [x] Prüfen, ob `<uses-library>` für das Framework nötig ist. Die Quelle dafür ist das Manifest von HiNote 12.4.3 (`tmp/device/apk124/res/resources/AndroidManifest.xml`) und `adb shell pm list libraries`.
- [x] Activity mit `HWEditView` bauen und per adb installieren. Verhalten aus `adb logcat` auswerten (Hidden-API-Sperre, UnsatisfiedLinkError, SecurityException, SELinux-Denials).
- [x] Fallback prüfen: normale `SurfaceView` mit Stift-Events als Vergleich zur Latenz.
- [x] Ergebnis in `tmp/FINDINGS.md` festhalten und dem Nutzer vorlegen (Entscheidungspunkt 1).

**Relevanter Kontext:**
- `tmp/device/fw/eink_src/sources/android/eink/view/` (`HWEditView`, `HWSurfaceView`, `HandPen`, `FBHandSurface`)
- `tmp/jadx_src/sources/com/huawei/stylus/baseengine/engine/EinkEngine.java` (so nutzt HiNote die Klassen)
- `tmp/jadx_src_124/sources/com/huawei/hinote/edit/stylus/ink/InkStylusView.java`
- Systembibliotheken: `/system/lib64/libeink*.so`

**Status:** [x] erledigt. Ergebnis: geht, siehe `tmp/FINDINGS.md` (Abschnitt "Spike Stift-Latenz"). Projekt: `tmp/penspike/`.

### 2. Format `.bin` (PENCILENGINE) dokumentieren und Parser bauen
**Intent:** Die Strichdaten lesbar machen. Das ist die Grundlage für PDF-Export, Plugin-Anzeige und eine mögliche eigene App.

**Erwartetes Ergebnis:**
- Eine Formatbeschreibung (Datei-Header, Strich-Struktur, Punkte mit x, y, Druck, Zeit, Pinsel, Farbe, Breite).
- Ein Parser (Python zum Start) liest alle `.bin` aus den Beispielexporten ohne Fehler.
- Eine Testausgabe, zum Beispiel ein Bild pro Seite, die man mit `files/<pageId>.jpg` (Vorschau) vergleichen kann.

**Todo:**
- [ ] `PENCILENGINE`-Header aus `tmp/device/x/files/*.bin` mit den Lesepfaden in `tmp/jadx_src/sources/b/a/a/a/c/b.java` abgleichen (Methoden `a(DataInputStream, ...)`, `b(...)`, Zeilen 228 bis 650). Klären, welche Variante für das neue Format 12.4.3 gilt (`tmp/jadx_src_124`).
- [ ] Datenstruktur ableiten und als Markdown festhalten (eigene Formulierung, kein Kopieren des Huawei-Codes).
- [ ] Parser schreiben. Er soll die Seitengröße aus `pages/<pageId>.jhinote` (`pageRatio`) und der Vorschau-JPG nutzen.
- [ ] Striche in ein Bild rendern und mit der Vorschau vergleichen.
- [ ] Mit allen Exporten aus `tmp/device/exp/custom/` testen.

**Relevanter Kontext:**
- `tmp/device/sample.hinote`, `tmp/device/x/` (entpackte Probe), `tmp/device/exp/custom/` (14 Exporte)
- `tmp/jadx_src/sources/com/huawei/stylus/baseengine/bean/NotePoint.java`, `tools/EngineTools.java`
- Rechtlich: Der Huawei-Code ist proprietär. Er dient nur als Anleitung zum Format.

**Status:** [ ] pending

### 3. Lokales PDF-Tool
**Intent:** Aus den `.hinote`-Dateien ein PDF erzeugen, ohne HiNote.

**Erwartetes Ergebnis:**
- Ein Kommandozeilen-Tool `hinote2pdf`: Eingabe `.hinote`, Ausgabe PDF mit einer Seite pro Notizseite, Striche als Vektoren, Hintergrund wie in der Notiz.
- Mehrere Dateien und ein ganzes Verzeichnis werden unterstützt.

**Todo:**
- [ ] Sprache und PDF-Bibliothek wählen (aktuell gepflegt, Lizenz Apache 2.0, MIT oder BSD). Version und Pflegezustand vor der Wahl prüfen.
- [ ] `.hinote` entpacken, `.jhinote`-JSON lesen (gzip), Seiten nach `pageNumber` ordnen.
- [ ] Striche mit dem Parser aus Teilaufgabe 2 zeichnen. Seitengröße und Seitenverhältnis aus dem JSON.
- [ ] Vergleich mit dem HiNote-PDF-Export derselben Notiz. Beispiele liegen auf dem Tablet unter `/sdcard/Documents/*.pdf`.

**Relevanter Kontext:** Ergebnisse aus Teilaufgabe 2.

**Status:** [ ] pending

### 4. Export automatisieren
**Intent:** Alle Notizen regelmäßig und ohne Handarbeit als `.hinote` exportieren.

**Erwartetes Ergebnis:**
- Ein Skript (PC, adb) exportiert alle Notizen einschließlich Ordnern und Scrollen.
- Danach eine Android-App mit Accessibility-Service, die dieselben Schritte direkt auf dem Tablet zeitgesteuert ausführt.
- Nur geänderte Notizen werden neu exportiert.

**Todo:**
- [ ] `tmp/export_all.py` erweitern: Ordner öffnen, Liste scrollen (BIT hat 18 Notizen, 11 sichtbar), Notizen im Wurzelverzeichnis und Ordnerkarten (`ib_folder_menu`) berücksichtigen.
- [ ] Änderungserkennung: `modifiedTime` aus dem JSON oder die SHA-256-Werte aus `custom_md.jhinote` mit dem letzten Lauf vergleichen. Doppelte Dateien (Zeitstempel im Namen) bereinigen.
- [ ] Verschlossene Notizen und Fehlerfälle (Dialog bleibt offen, adb-Verbindung bricht ab) behandeln. Der Prototyp hat Retry und Dialog-Aufräumen schon.
- [ ] Android-App mit Accessibility-Service: zeitgesteuert (WorkManager oder AlarmManager), Bildschirm an, Tablet am Netzteil. Test mit gesperrtem Bildschirm unter Android 10 ausstehend.
- [ ] Test der Variante "Aktiv lassen am Netzteil" (Entwickleroption `stay_on_while_plugged_in`): Bleibt der Bildschirm entsperrt, und läuft der Export dann zeitgesteuert durch? Auf dem Tablet ist vermutlich eine Bildschirmsperre gesetzt (`locksettings` meldete "Old password '' didn't match"), das ist aber nicht getestet. Die Aussage, dass ein Accessibility-Service die Sperre unter Android 10 nicht umgehen kann, ist eine Einschätzung und nicht auf dem Gerät geprüft.
- [ ] 14 Testdateien in `/sdcard/Documents/hinote/custom/` auf dem Tablet aufräumen (nach Rückfrage beim Nutzer).

**Relevanter Kontext:**
- `tmp/export_all.py`, `tmp/ui.sh` (UI-Dump)
- Stabile Resource-IDs: `grid_item_menu`, `ib_folder_menu`, `menu_name`, `item_title`, `toolbar_more`, `batch_manager`
- `NoteExportDialog.v()`: HiNote-Format nur bei Einzelnotiz

**Status:** entfällt voraussichtlich. Der Prototyp `tmp/export_all.py` bleibt für den einmaligen Export der 33 Notizen (Phase 5 in `notiz-app-plan.md`).

### 5. Sync nach Nextcloud
**Intent:** Exportdateien automatisch in die Nextcloud bringen.

**Erwartetes Ergebnis:**
- `/sdcard/Documents/hinote/custom/` wird regelmäßig nach Nextcloud hochgeladen.
- Duplikate werden vermieden.

**Todo:**
- [ ] Prüfen, ob der Auto-Upload der installierten Nextcloud-App (`com.nextcloud.android.beta`) diesen Ordner auswählen kann. Alternativ DAVx5 (`at.bitfire.davdroid`, installiert) oder ein eigener WebDAV-Upload.
- [ ] Ablagekonzept für Nextcloud festlegen: Ordnerstruktur, Umgang mit den Zeitstempeln im Dateinamen.
- [ ] Ende-zu-Ende-Test: Export, Upload, Datei erscheint in Nextcloud.

**Status:** [ ] pending

### 6. Nextcloud-Plugin: Anzeige und PDF-Export
**Intent:** Notizen in Nextcloud ansehen und als PDF herunterladen.

**Erwartetes Ergebnis:**
- Ein Nextcloud-App-Plugin erkennt `.hinote`-Dateien, zeigt Seiten an (Striche gerendert) und bietet PDF-Download.

**Todo:**
- [ ] Aktuelle Nextcloud-Version und App-Entwicklung prüfen (Dateiaktionen, Viewer-API).
- [ ] Rendering im Browser (Striche aus dem Parser) oder serverseitig (PDF aus Teilaufgabe 3).
- [ ] Prüfung auf Sicherheitsvorgaben: Eingabevalidierung für ZIP-Inhalte (Größenlimits, Pfade), keine Secrets im Repository.

**Relevanter Kontext:** Teilaufgaben 2 und 3.

**Status:** [ ] pending

### 7. Eigene Notiz-App
**Intent:** Eigene App mit flüssigem Stift, die HiNote ersetzt und mit Nextcloud synchronisiert.

**Status:** Entscheidung gefallen, Plan in [`notiz-app-plan.md`](notiz-app-plan.md). Spike 1 bis 3 sind abgeschlossen, Befunde in `tmp/FINDINGS.md`. Die Schrifterkennung der Huawei-Engine ist für Fremd-Apps gesperrt (Spike 3). Offene Entscheidungen des Nutzers stehen in Abschnitt 3 des Neubau-Plans.

## Reihenfolge

1. Teilaufgabe 1 (Spike): erledigt.
2. Teilaufgabe 2, dann 3. Beides wird in jedem Fall gebraucht.
3. Teilaufgabe 7 (eigene App) in den Phasen aus `notiz-app-plan.md`. Teilaufgaben 5 und 6 gehen dort in Phase 6 auf.

## Arbeitsumgebung

- Projekt: `/Users/hjochman/git/HiNoteNextcloud`. `appBackup/` enthält die HiNote-APK 12.3.0.372 und die HarmonyOS-Widget-Module.
- Alle Arbeitsdateien liegen in `tmp/` (Dekompilate, Geräte-Dateien, Skripte). Die `.gitignore` schließt `appBackup/`, die entpackten HiNote-Verzeichnisse (`tmp/jadx_*`, `tmp/apk`, `tmp/device/`) und Build-Ausgaben aus. Aufgeräumt wurden `tmp/research` und `tmp/device/thirdparty`.
- Gerät: `adb -s TGPYD22602200007`. Tablet: HMW-W09, Android 10, EMUI 12, kein Root.
- Dekompilate: `tmp/jadx_src` (HiNote 12.3.0.372), `tmp/jadx_src_124` (HiNote 12.4.3.395), `tmp/device/fw/eink_src` (E-Ink-Framework).
- Hinweis zu zsh: Die Variable `path` überschreibt `PATH`. Andere Namen verwenden, zum Beispiel `apkp`.
- Sicherheit: Dienste nur an `127.0.0.1` binden, TLS 1.2 oder höher, keine Zugangsdaten im Code.
