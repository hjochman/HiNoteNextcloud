# HiNote-Altbestand: Analyse, Parser und Übernahme

> **Stand 2026-11-05 (bereinigt):** Der Nutzer hat entschieden, eine eigene Notiz-App zu bauen ("wir schreiben selber"). Sie heißt **PaperNotes by BOB** und wird in [`notiz-app-plan.md`](notiz-app-plan.md) geführt. Dieses Dokument behandelt nur noch den **Altbestand**: die Notizen in HiNote lesbar machen und einmalig in die neue App übernehmen. Der frühere Plan "HiNote-Export automatisieren, `.hinote` nach Nextcloud hochladen, Plugin dafür" ist **entfallen**. Sync, PDF-Export und Nextcloud-Plugin stehen jetzt in Phase 6 des Neubau-Plans und arbeiten mit dem neuen Format `.pnote`.

## Statusübersicht

| Teilaufgabe | Stand | Bemerkung |
|---|---|---|
| 1 Spike Stift-Latenz | **erledigt** | Ergebnis: geht. Die Spikes 1 bis 3 stehen in `tmp/FINDINGS.md` |
| 2 Parser für `.bin` (PENCILENGINE) | **offen** | nur nötig für die Übernahme (Phase 5 im Neubau-Plan, Weg A) |
| 3 Lokales PDF-Tool `hinote2pdf` | **zurückgestellt** | nur sinnvoll, wenn Weg A in Phase 5 scheitert oder der Nutzer PDFs der alten Notizen vor dem Import will. Danach bietet die App den PDF-Export selbst |
| 4 Einmal-Export der vorhandenen Notizen | **offen, reduziert** | Prototyp exportierte 11 von 33. Dauerbetrieb und Zeitsteuerung entfallen |

Entfallen und nicht mehr in diesem Plan: Export per Accessibility-Service und Zeitsteuerung, Upload der `.hinote`-Exporte (Auto-Upload der Nextcloud-App, DAVx5), Plugin für `.hinote`. Grund: Die eigene App speichert im eigenen Format und synchronisiert selbst (Phase 6 im Neubau-Plan). Die Teilaufgaben 5 bis 7 des früheren Plans sind dort aufgegangen.

## Ausgangslage (belegt, Details in `tmp/FINDINGS.md`)

- Die Originaldaten von HiNote (DB `memo`, `files/hwFile/<pageId>.bin`) liegen im privaten App-Verzeichnis `/data/data/com.huawei.hinote`. Ohne Root nicht lesbar (`Permission denied`, `run-as` nicht möglich, `allowBackup=false`). Die Daten kommen also nur über den Export von HiNote heraus.
- HiNote 12.4.3.395 kann pro Notiz als `.hinote` exportieren. Ziel: `/sdcard/Documents/hinote/custom/`. Bei Mehrfachauswahl fehlt die Option "HiNote" im Dialog.
- `.hinote` ist ein ZIP mit: `<noteId>.jhinote` (gzip-JSON), `pages/<pageId>.jhinote` (gzip-JSON), `files/<pageId>.bin` (Stiftstriche, Kennung `PENCILENGINE`, Big-Endian), `files/<pageId>.jpg` (Vorschau), `custom_md.jhinote` (SHA-256-Hashes).
- Der Export lässt sich von außen nicht auslösen: Provider sind signaturgeschützt, es gibt keinen exportierten Intent oder Service dafür.
- Der Prototyp `tmp/export_all.py` (adb + uiautomator) exportierte 11 Notizen des Ordners "BIT" in etwa 2 Minuten. Alle Ergebnisse waren gültige ZIPs. Das Tablet muss dafür entsperrt und der Bildschirm an sein.
- HiNote hat 33 Notizen, teils in Ordnern (z. B. "BIT" mit 18 Notizen, "Lesenotizen" mit 1). Verschlossene Notizen sind nicht erfasst.

## Teilaufgaben

### 1. Spike: Stift-Latenz in einer eigenen App
**Status:** [x] erledigt. Ergebnis: geht. `HWEditView` läuft in einer Fremd-App ohne Root und ohne `<uses-library>`, die Latenz wirkt laut Nutzer wie bei HiNote. Belege in `tmp/FINDINGS.md` (Abschnitte "Spike Stift-Latenz", "Spike 2", "Spike 3"). Projekt: `tmp/penspike/` (unverändert lassen). Die Erkenntnisse sind in die App eingeflossen (siehe `notiz-app-plan.md`).

### 2. Format `.bin` (PENCILENGINE) dokumentieren und Parser bauen
**Intent:** Die Strichdaten der alten Notizen lesbar machen. Das ist die Grundlage für die Übernahme (Phase 5, Weg A) und für `hinote2pdf`.

**Erwartetes Ergebnis:**
- Eine Formatbeschreibung (Datei-Header, Strich-Struktur, Punkte mit x, y, Druck, Zeit, Pinsel, Farbe, Breite) in eigener Formulierung.
- Ein Parser (Python zum Start) liest alle `.bin` aus den Beispielexporten ohne Fehler.
- Eine Testausgabe, ein Bild pro Seite, vergleichbar mit `files/<pageId>.jpg`.

**Todo:**
- [ ] `PENCILENGINE`-Header aus `tmp/device/x/files/*.bin` mit den Lesepfaden in `tmp/jadx_src/sources/b/a/a/a/c/b.java` abgleichen (Methoden `a(DataInputStream, ...)`, `b(...)`, Zeilen 228 bis 650). Klären, welche Variante für das Format 12.4.3 gilt (`tmp/jadx_src_124`).
- [ ] Datenstruktur ableiten und als Markdown festhalten (kein Kopieren des Huawei-Codes).
- [ ] Parser schreiben. Seitengröße aus `pages/<pageId>.jhinote` (`pageRatio`) und der Vorschau-JPG.
- [ ] Striche in ein Bild rendern und mit der Vorschau vergleichen.
- [ ] Mit allen Exporten aus `tmp/device/exp/custom/` testen (14 Dateien).

**Relevanter Kontext:**
- `tmp/device/sample.hinote`, `tmp/device/x/` (entpackte Probe), `tmp/device/exp/custom/`
- `tmp/jadx_src/sources/com/huawei/stylus/baseengine/bean/NotePoint.java`, `tools/EngineTools.java`
- Rechtlich: Der Huawei-Code ist proprietär und dient nur als Anleitung zum Format.

**Status:** [ ] offen

### 3. Lokales PDF-Tool `hinote2pdf`
**Intent:** Aus `.hinote`-Dateien ein PDF erzeugen, ohne HiNote.

**Status:** [ ] zurückgestellt (siehe Statusübersicht). Wird nur gebaut, wenn der Nutzer es braucht. Dann: Kommandozeilen-Tool, Eingabe `.hinote` (auch Verzeichnis), Ausgabe PDF mit einer Seite pro Notizseite, Striche als Vektoren. Sprache und Bibliothek zuerst prüfen (aktuell gepflegt, Lizenz Apache 2.0, MIT oder BSD). Vergleich mit dem HiNote-PDF-Export (Beispiele auf dem Tablet unter `/sdcard/Documents/*.pdf`). Voraussetzung ist Teilaufgabe 2.

### 4. Einmal-Export der vorhandenen Notizen
**Intent:** Alle 33 Notizen einmal als `.hinote` herausholen, damit sie in Phase 5 in die neue App übernommen werden können. Kein Dauerbetrieb, keine Zeitsteuerung, kein Accessibility-Service.

**Erwartetes Ergebnis:** Alle Notizen, auch die in Ordnern, liegen als gültige `.hinote`-Dateien auf dem PC (und sind gesichert).

**Todo:**
- [ ] `tmp/export_all.py` erweitern: Ordner öffnen, Liste blättern statt scrollen (BIT hat 18 Notizen, 11 sichtbar), Notizen im Wurzelverzeichnis und Ordnerkarten (`ib_folder_menu`) berücksichtigen.
- [ ] Doppelte Dateien (Zeitstempel im Namen) bereinigen.
- [ ] Fehlerfälle behandeln (Dialog bleibt offen, adb-Verbindung bricht ab). Retry und Dialog-Aufräumen kann der Prototyp schon.
- [ ] Verschlossene Notizen: Der Nutzer entscheidet, ob sie nötig sind.
- [ ] Die 14 Testdateien in `/sdcard/Documents/hinote/custom/` auf dem Tablet danach aufräumen (nach Rückfrage beim Nutzer).

**Relevanter Kontext:**
- `tmp/export_all.py`, `tmp/ui.sh` (UI-Dump)
- Stabile Resource-IDs: `grid_item_menu`, `ib_folder_menu`, `menu_name`, `item_title`, `toolbar_more`, `batch_manager`
- `NoteExportDialog.v()`: HiNote-Format nur bei Einzelnotiz

**Status:** [ ] offen, reduziert. Läuft vor oder zusammen mit Phase 5.

## Reihenfolge

1. Teilaufgabe 1: erledigt.
2. Teilaufgabe 4 (Einmal-Export) und Teilaufgabe 2 (Parser) gehören zu **Phase 5** im Neubau-Plan. Sie beginnen, sobald die App im Alltag trägt (nach Phase 2b und 3).
3. Teilaufgabe 3 nur auf Wunsch.

## Arbeitsumgebung

- Projekt: `/Users/hjochman/git/HiNoteNextcloud`. `appBackup/` enthält die HiNote-APK 12.3.0.372 und die HarmonyOS-Widget-Module (git-ignoriert).
- Arbeitsdateien der Analyse liegen in `tmp/` (Dekompilate, Geräte-Dateien, Skripte). Die `.gitignore` schließt `appBackup/`, die entpackten HiNote-Verzeichnisse (`tmp/jadx_*`, `tmp/apk`, `tmp/device/`) und Build-Ausgaben aus.
- Gerät: `adb -s TGPYD22602200007` (Pfad `/opt/homebrew/bin/adb`). Tablet: HMW-W09, Android 10, EMUI 12, kein Root.
- Dekompilate: `tmp/jadx_src` (HiNote 12.3.0.372), `tmp/jadx_src_124` (HiNote 12.4.3.395), `tmp/device/fw/eink_src` (E-Ink-Framework). Nur lesen, nicht kopieren.
- Hinweis zu zsh: Die Variable `path` überschreibt `PATH`. Andere Namen verwenden, zum Beispiel `apkp`.
- Sicherheit und Build der App: siehe `AGENT.md`.
