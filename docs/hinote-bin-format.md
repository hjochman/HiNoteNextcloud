# HiNote-Export: Container `.hinote` und Stiftdaten `.bin` (PENCILENGINE)

Stand: Analyse von HiNote 12.4.3.395 (Format-Kennung der JSON-Dateien `hinote_1.0.4`) anhand von 14 echten Exporten
(19 Seiten, 4607 Striche, 78 119 Punkte). Referenzimplementierung: [`tools/hinote_bin.py`](../tools/hinote_bin.py).

Die Beschreibung ist in eigenen Worten aus dem Studium des Dekompilats (`tmp/jadx_src_124`, Pakete
`hwstylusfeature14`/`hwstylusfeature15`, `com.huawei.stylus.baseengine`) und der Hexdumps der Beispieldateien entstanden.
Der Huawei-Code ist proprietär und wurde weder kopiert noch eingebunden.

**Kennzeichnung:** *belegt* = durch die 14 Beispieldateien (Parser läuft ohne Restbytes, Rendering deckt sich mit der
Vorschau) und/oder eindeutig im Dekompilat. *Annahme* = nur aus Namen, Standardwerten oder einer einzigen Codestelle
abgeleitet, durch die Proben nicht überprüfbar (alle Proben enthalten nur schwarze Kugelschreiberstriche).

Alle Zahlen sind **Big-Endian** (wie `java.io.DataInputStream`). `int` = 4 Byte vorzeichenbehaftet, `u16` = 2 Byte
vorzeichenlos, `i64` = 8 Byte, `f32` = IEEE-754 Single, `bool` = 1 Byte.

---

## 1. Container `.hinote` (ZIP)

| Eintrag | Inhalt | Status |
|---|---|---|
| `<noteId>.jhinote` | gzip-komprimiertes JSON, Wurzel `customNoteContent` (Notiz-Metadaten) + `fileList` | belegt |
| `pages/<pageId>.jhinote` | gzip-JSON, Wurzel `customNotePageContent` (Seite) + `fileList` | belegt |
| `files/<pageId>.bin` | Stiftstriche der Seite (Abschnitt 2). **Fehlt bei leeren Seiten** (Beispiel: Seite 2 von „API Gateway“) | belegt |
| `files/<pageId>.jpg` | Seitenvorschau, **inklusive Hintergrundvorlage und Strichen**, 903 × 1080 px (in allen 19 Seiten) | belegt |
| `custom_md.jhinote` | gzip-JSON `customMdContents[]` mit `fileMdStr` = SHA-256 des Dateiinhalts und `fileNameMdStr` = SHA-256 des Dateinamens (ohne `files/`) | belegt (alle Einträge nachgerechnet) |
| `files/`, `pages/` | leere Verzeichniseinträge | belegt |

Seiten-ID und Dateiname stimmen überein (`pages/<id>.jhinote` enthält `"id": "<id>"`). Die Seitenreihenfolge steht in
`pageNumber` (ab 1), nicht im ZIP.

### 1.1 Notiz-JSON (`customNoteContent`)

| Feld | Beispiel | Bedeutung | Import |
|---|---|---|---|
| `id` | `4ff509b4…` | Notiz-ID (32 Hex) | als Quelle merken, neue UUID vergeben |
| `noteTitle` | `"- Alle Pipeline"` | Titel | ja → `Note.title` |
| `background` | `base1`, `base6` | Standardvorlage der Notiz | nur Fallback für Seiten |
| `createTime`, `modifiedTime` | **immer 0** | im Export nicht befüllt | nein; stattdessen aus den Seiten ableiten (Min/Max) |
| `pageRatio` | `0.8357143` | Breite/Höhe der Seite (= 1404/1680) | ja, bestimmt die Seitenhöhe |
| `pageColor` | `-1` | Papierfarbe (Annahme: -1 = Standard/weiß) | nein |
| `pageOrientation` | `0` | Annahme: 0 = Hochformat | nein (in allen Proben 0) |
| `noteType` | `100` | Annahme: Notiztyp „E-Ink-Handschrift“ | nein |
| `isFavorite`, `isTop` | `0` | Favorit / angeheftet | ja (→ `favorite`, `pinned`), in Proben 0 |
| `hasCover`, `noteIcon` | `0`, `""` | Deckblatt | nein |
| `data1` | `{"originDeviceType":"eink","isContentCover":"0"}` | JSON-String | nein |
| `attachment`, `outLineAttachment`, `recordItemEntities` | `[]` | Anhänge, Gliederung, Audioaufnahmen | in den Proben immer leer → nicht prüfbar |
| `cloudSyncState`, `deleteTag`, `deleteTime`, `extendFields` | – | Sync-/Papierkorbstatus, `hinote_1.0.4` | nein |

**Ordner/Kategorie steht NICHT im Export.** Weder `customNoteContent` noch ein anderer Eintrag nennt den Ordner. Der
Ordnername muss beim Einmal-Export (Teilaufgabe 4, `tmp/export_all.py` kennt den geöffneten Ordner) extern mitgegeben
werden, z. B. als Unterverzeichnis oder Dateinamenspräfix.

### 1.2 Seiten-JSON (`customNotePageContent`)

| Feld | Beispiel | Bedeutung | Import |
|---|---|---|---|
| `id`, `notesId` | 32 Hex | Seiten-/Notiz-ID | Zuordnung |
| `pageNumber` | `1`, `2` | Reihenfolge (ab 1) | ja |
| `background` | `base1`, `base6`, `plan2` | Vorlage der Seite | ja, über Tabelle 1.3 → `Page.template` |
| `pageRatio` | `0.8357143` | wie oben | ja |
| `createTime`, `modifiedTime` | ms seit 1970 | Erstell-/Änderungszeit der Seite | ja (für Notizzeit) |
| `pageBookMarks` | `[]` | Lesezeichen (Entität `NotePageBookMarkEntity`: `bookMarkName`, `noteId`, …) | in Proben immer leer; Annahme: nicht-leer = Seite hat Lesezeichen → `bookmarked=true` |
| `lastPageTag` | `0`/`1` | Annahme: 1 = zuletzt geöffnete Seite (genau eine Seite je Notiz hat 1) | nein |
| `pageElement` | `[]` | Textboxen/Bilder (Entität `page_element`: `element_type`, `positionX/Y/Z`, `width`, `height`, `angle`, `scale`, `content_text`, `html`, `file_path`, `sourceDpi`) | **in allen 19 Proben leer** → Format ungeprüft |
| `attachment[]` | siehe unten | Verweis auf die `.bin` | nur zur Zuordnung |
| `bkgAttachmentIndex`, `pageType`, `pageColor`, `pageOrientation`, `isDelete`, `cloudSyncState`, `thumbnail` | – | intern | nein |
| `fileList[]` | `{hash, name}` | SHA-256 + Name der zur Seite gehörenden Dateien (`.bin`, `.jpg`) | zur Integritätsprüfung |

`attachment[0]` hat `attachType: 0`, `data1: {"synDataTpye":"memopage"}` und `filePath` auf den privaten Pfad
`/data/data/com.huawei.hinote/files/hwFile/<pageId>.bin` (für den Import wertlos; maßgeblich ist `files/<pageId>.bin`).
Bei leeren Seiten ist `attachment` leer und `fileList` nennt nur die `.jpg`.

### 1.3 Vorlagen: HiNote-`background` → `TemplateCatalog`-ID

HiNote-IDs und Namen stammen aus `note_background_config.json` und `values-de/strings.xml` der 12.4.3-APK. In den Proben
kommen nur `base1`, `base6`, `plan2` vor; die übrigen Zuordnungen sind **Annahme** (nach deutschem Anzeigenamen).

| HiNote-ID | Anzeigename (de) | `TemplateCatalog`-ID | Status |
|---|---|---|---|
| `base1` | Leer | `blank` | belegt (Probe) |
| `base4` | Breite Linien | `lines_wide` | Annahme |
| `base5` | Schmale Linien | `lines_narrow` | Annahme |
| `base6` | Gepunktet | `dotted` | belegt (Probe: JPG zeigt Punktraster) |
| `base3` | Kleines Raster | `grid` | Annahme (Rastergröße weicht ab) |
| `base2` | Mittleres Raster | `grid` | Annahme |
| `handwrite1` | Papier für Handschrift | `handwriting` | Annahme |
| `handwrite2` | Millimeterpapier | `millimeter` | Annahme |
| `handwrite4` | Brief (chinesisch) | `letter` | Annahme (vermutlich anderes Layout) |
| `plan2` | To-Do-Liste | `todo` | Annahme (Probe vorhanden, Zuordnung per Name) |
| `plan3` | Besprechungsprotokolle | `meeting` | Annahme |
| `plan4` | Wochenplan | `weekly` | Annahme |
| `plan5` | Monatsplan | `monthly` | Annahme |
| `plan1` | Aufgabenliste | `tasks` | Annahme |
| `learning7` | Fehlerprotokoll | `error_log` | Annahme |
| `learning2` | Lesenotizen | `reading_notes` | Annahme |
| `learning3` | Cornell-Notizen | `cornell` | Annahme |
| `handwrite3` (Gitterpapier chin.), `learning4` (Punkteblatt), `learning5` (SWOT), `learning6` (Notenlinien), `plan6` (Zeitmanagement) | – | kein Gegenstück → `blank` | Annahme |

`TemplateCatalog.lookup()` fällt bei unbekannten IDs ohnehin auf `blank` zurück. Die JPG-Vorschau enthält den Hintergrund
(belegt); sie eignet sich als Notbehelf nur für die Anzeige, nicht als Strichersatz (sie enthält die Striche mit).

---

## 2. Stiftdaten `files/<pageId>.bin` (`PENCILENGINE`)

Aufbau (alles hintereinander, keine Lücken; belegt in allen 19 Dateien, es bleiben **0 Restbytes**):

```
Dateikopf (76 B) | Seitenkopf (48 B) | Grundinhalt (12 B) | Strich 1 … Strich n | Abschlussblock (40 B + Gruppen)
```

Beispiel `d495cc51….bin` (131 956 Byte): Dateikopf 0–75, Seitenkopf 76–123, Grundinhalt 124–135, erster Strich ab 136
(`0x88`), 184 Striche, Abschlussblock ab 131 916 (`0x2034C`) bis Dateiende.

### 2.1 Dateikopf (76 Byte, Offset 0)

| Offset | Typ | Wert in Proben | Bedeutung | Status |
|---|---|---|---|---|
| 0 | 12 × byte | `PENCILENGINE` | Kennung (ASCII, genau 12 Zeichen) | belegt |
| 12 | int | 76 | Größe dieses Kopfes | belegt |
| 16 | i16, i16 | 0, 0 | Version (Haupt/Neben); Annahme: unbenutzt | Annahme |
| 20 | int, int | 0, 0 | reserviert/unbekannt | – |
| 28 | i64 | 0 | Erstellzeit (ms); im Export 0 | belegt (0) |
| 36 | i64 | 0 | Änderungszeit (ms); im Export 0 | belegt (0) |
| 44 | 32 × byte | 0 | reserviert | belegt (0) |

### 2.2 Seitenkopf (48 Byte, Offset 76)

| Offset | Typ | Wert | Bedeutung | Status |
|---|---|---|---|---|
| +0 | int | 48 | Größe dieses Kopfes | belegt |
| +4 | int | 2 | Format-/Abschnittsversion | belegt (konstant 2) |
| +8 | u16, u16 | 1, 0 | Neben-/Unterversion | belegt (konstant) |
| +12 | int | Dateigröße − 124 | Länge aller Daten nach dem Seitenkopf | belegt (geprüft) |
| +16 | 16 × byte | zufällig | UUID der Seite (nicht identisch mit `pageId`) | belegt |
| +32 | int | 12 | Größe des Grundinhalts | belegt |
| +36 | int | n | **Anzahl Striche** | belegt |
| +40 | int | 12 + Σ Strichgrößen | Länge von Grundinhalt + allen Strichen, also Offset des Abschlussblocks relativ zum Seitenkopf-Ende | belegt |
| +44 | int | 1 | unbekannt (Annahme: Anzahl Abschnitte) | Annahme |

### 2.3 Grundinhalt (12 Byte, Offset 124)

| Offset | Typ | Wert | Bedeutung | Status |
|---|---|---|---|---|
| +0 | int | 0 | unbekannt | – |
| +4 | int | **1000** | Seitenbreite der Koordinaten (normiert) | belegt |
| +8 | int | **1195** | Seitenhöhe der Koordinaten; Dekompilat: `(int)(Ansichtshöhe × 1000 / Ansichtsbreite)` | belegt |

### 2.4 Strich (variable Länge)

Jeder Strich: Kopf (48) + Grundwerte (24) + Pinsel (48) + Punktblock (20 + 36 × Punkte).

**Strichkopf (48 Byte)**

| Offset | Typ | Wert | Bedeutung | Status |
|---|---|---|---|---|
| +0 | int | 48 | Kopfgröße | belegt |
| +4 | int | 0 | unbekannt (Version) | – |
| +8 | u16, u16 | 1, 0 | Unterversion | belegt (konstant) |
| +12 | int | 72 + Punktblock | Länge aller Daten dieses Strichs nach dem Kopf (Grundwerte + Pinsel + Punktblock) | belegt |
| +16 | 16 × byte | zufällig | Strich-UUID (Verweis aus Gruppen im Abschlussblock) | belegt |
| +32 | int | 24 | Größe der Grundwerte | belegt |
| +36 | int | 48 | Größe der Pinselstruktur | belegt |
| +40 | int | 72 | Offset des Punktblocks ab Kopfende (= 24 + 48) | belegt |
| +44 | int | 20 + 36·n | Größe des Punktblocks | belegt |

Nächster Strich beginnt bei `Strichanfang + 48 + Wert(+12)`.

**Grundwerte (24 Byte, ab Kopfende)**

| Offset | Typ | Wert | Bedeutung | Status |
|---|---|---|---|---|
| +0 | i64 | 0 | Startzeit (ms, Epoche); im Export 0 | belegt (0) |
| +8 | f32 | 0.0 | Index des ersten Punkts | belegt |
| +12 | f32 | n − 1 | Index des letzten Punkts (in allen 4607 Strichen = Punktzahl − 1) | belegt |
| +16 | bool | 0 | Flag A (Annahme: „aus Datei geladen/Teil einer Formkorrektur“) | Annahme |
| +17 | bool | 0 | Flag B (Annahme: Kennzeichen für erkannten/geglätteten Strich) | Annahme |
| +18 | 2 × byte | 0 | reserviert | belegt (0) |
| +20 | int | **−1** | Gruppen-/Verbindungsindex. −1 = normaler Strich; ≠ −1 = Strich gehört zu einer Formgruppe und wird von HiNote beim normalen Zeichnen **nicht** als eigener Strich geladen (Dekompilat) | belegt (−1 in allen Proben), Bedeutung ≠ −1 Annahme |

**Pinselstruktur (48 Byte)**

| Offset | Typ | Wert in Proben | Bedeutung | Status |
|---|---|---|---|---|
| +0 | int | **2** | Pinseltyp (`PenKitBrushType`, siehe 2.7) | belegt |
| +4 | int | 0 | zweiter Typwert (Annahme: Unterart) | Annahme |
| +8 | f32 | 0.0 | **Blau** 0..1 | belegt (Reihenfolge B, G, R aus Code) |
| +12 | f32 | 0.0 | **Grün** 0..1 | belegt (Code) |
| +16 | f32 | 0.0 | **Rot** 0..1 | belegt (Code) |
| +20 | f32 | 1.0 | „Intensität“ (Annahme: Deckkraftfaktor für Marker; beim Schreiben aus Alpha abgeleitet: Marker α/5, Textmarker α) | Annahme |
| +24 | f32 | 1.0 | **Alpha** 0..1 | belegt (Code), in Proben nur 1.0 |
| +28 | f32 | **2.0** | **Strichbreite** (Einheit: Ansichtspixel, Abschnitt 4) | belegt (Wert/Einheit teils Annahme) |
| +32 | int | 0 | unbekannt | – |
| +36 | bool | 0 | Flag; HiNote setzt es beim Schreiben, wenn der Pinseltyp 3 (Bleistift) ist; Bedeutung unbekannt | Code belegt, Bedeutung Annahme |
| +37 | 3 × byte | 0 | reserviert | belegt (0) |
| +40 | 8 × byte | 0 | reserviert | belegt (0) |

ARGB für die Anzeige: `A=round(alpha·255)`, `R/G/B` analog. HiNote hellt beim Einlesen die Deckkraft aller Farben außer
Schwarz (`0xFF000000`), Dunkelgrau (`0xFF505050`) und Weiß um `(255−A)/6` auf (Anzeigekorrektur, Annahme: für den Import
den Rohwert verwenden).

**Punktblock (20 Byte Kopf + 36 Byte je Punkt)**

Kopf: `int` 0 (unbekannt) | `int` n (Punktzahl) | `int` 36 (Punktgröße) | 8 × byte 0 (reserviert) — belegt.

Punkt (36 Byte):

| Offset | Typ | Bedeutung | Status |
|---|---|---|---|
| +0 | f32 | **x** in Seitenkoordinaten (Breite 1000) | belegt |
| +4 | f32 | **y** in Seitenkoordinaten (Höhe 1195) | belegt |
| +8 | int | **Zeit in ms relativ zum ersten Punkt des Strichs** (erster Punkt 0, monoton nicht fallend, max. 1800 ms in den Proben) | belegt |
| +12 | f32 | **Druck** 0..1 (Proben: 0.0049 … 0.9993, in 1/4096-Stufen quantisiert, Mittel 0.67) | belegt |
| +16 | f32 | unbekannt, immer 0 (Annahme: Neigung/Azimut o. ä.) | Annahme |
| +20 | f32 | unbekannt, immer 0 | Annahme |
| +24 | f32 | unbekannt, immer 0 (HiNote schreibt hier explizit 0.0) | Annahme |
| +28 | int | Punktrolle: **4** = erster, **5** = letzter, **6** = mittlerer Punkt | belegt |
| +32 | f32 | laufender Punktindex 0.0 … n−1 | belegt (alle 78 119 Punkte) |

### 2.5 Abschlussblock (≥ 40 Byte, am Dateiende)

Kopf (40 Byte): `int` 40 (Größe) | `int` 3 | `u16` 1 | `u16` 0 | `int` 0 | 16 × byte 0 (UUID) | `int` 0 | `int` **g** =
Anzahl Gruppen. In allen Proben ist g = 0, die Datei endet unmittelbar danach (belegt).

Je Gruppe (nur aus dem Code, **ungetestet**): `int` Anzahl Einträge c | `int` Eintragsgröße e | 8 × byte reserviert |
c × e Byte; jeder Eintrag beginnt mit der 16-Byte-UUID eines Strichs (gefolgt von einem `int`, e = 20). Annahme:
Gruppen fassen Striche zu einem erkannten Objekt (Formerkennung) zusammen; solche Striche werden beim normalen Laden
übersprungen.

### 2.6 Weitere Formatvarianten (nur Dekompilat, nicht durch Proben belegt)

| Kennung | Vorkommen | Bemerkung |
|---|---|---|
| `PENCILENGINE` | HiNote 12.4.x (Export-Proben) | dieses Dokument |
| `HWEINKBYTE` | 12.3 und 12.4 (Altformat, „Version 1“) | Abschnittstabelle, Striche mit Typ/Farbe-ARGB-int/Breite/Rechteck + Punkte (x, y, Druck, ?, Zeit als i64) |
| `HWEINKDATA` | Beta-Altformat | Kopf mit Breite/Höhe, Striche mit normiertem Rechteck |
| `HWPENKITDATA`, `HWGRAFFI` | in `FileUtil.isFileValid` gelistet | nicht untersucht |

Der 12.4.3-Code lehnt `PENCILENGINE` im Altleser ausdrücklich ab (eigener Zweig). Die 12.3.0.372-Quellen
(`tmp/jadx_src`) kennen die Kennung `PENCILENGINE` gar nicht – Dateien aus 12.3 könnten also noch die Altformate sein.
Für die 14 Exporte aus 12.4.3 reicht der Parser für `PENCILENGINE`. Ein `HWEINKBYTE`-Leser ist **nicht** implementiert.

### 2.7 Pinseltyp (Dateiwert) → Stift in `PenCatalog`

Dateiwert = `PenKitBrushType` der App (12.4.3). Die Gruppen stammen aus den Listen der Klasse (Stift: 1, 2, 12, 13;
Bleistift: 3, 11; Marker: 4, 5). Der Wert wird beim Laden über eine kleine Tabelle auf den Framework-Stifttyp
abgebildet (2→2, 3→3, 4/5→6, alles andere→4) – dasselbe Muster wie `PenCatalog` in der App.

| Dateiwert | `PenKitBrushType` | Framework-Typ | `PenCatalog`-ID | Status |
|---|---|---|---|---|
| 1 | PEN | 4 | `fountain` | Code belegt (Schreibabbildung Framework 4→1), in Proben nicht vorhanden |
| **2** | BALLPOINT | 2 | `ballpoint` | **belegt in allen 4607 Strichen** |
| 3 | PENCIL | 3 | `pencil` | Code belegt |
| 4 | MARKER | 6 | `marker` | Code belegt (Framework 6→4) |
| 5 | HIGHLIGHTER | 6 | `marker` (mit Alpha) | Code belegt, Rendering als Annahme |
| 11 | TWOB (2B-Bleistift) | 4 | `pencil` | Annahme (Gruppe Bleistift) |
| 12 | BEAUTY | 4 | `fountain` | Annahme |
| 13 | CHINESE_WRITING | 4 | `fountain` | Annahme |

---

## 3. Koordinatensystem und Einheiten

| Größe | Ergebnis | Status |
|---|---|---|
| Koordinaten | x ∈ [0, 1000], y ∈ [0, 1195]; Proben: x 0,1…988, y 3…1189 (alle innerhalb) | belegt |
| Normierung | beim Laden: `x_Ansicht = x / 1000 · Ansichtsbreite`, `y_Ansicht = y / Höhe_Datei · Ansichtshöhe` (Dekompilat) – x und y teilen also **einen** Maßstab | belegt |
| Seitenverhältnis | Datei 1000 : 1195 = 0,8368; `pageRatio` 0,8357143 (= 1404/1680); Abweichung 0,13 % (Rundung der Ansichtshöhe auf ganze Pixel/Abschneiden) | belegt |
| Bezug zu PaperNotes (1404 × 1872) | Breite passt: Maßstab `1404/1000 = 1,404`. Die HiNote-Seitenhöhe ist **1404/0,8357 ≈ 1680 px** (nicht 1872): HiNote zeigt die Seite mit etwas weniger Höhe. 1195 · 1,404 = 1678 px | Breite belegt; „1404 px = Ansichtsbreite“ Annahme (aus `pageRatio` = 1404/1680 gefolgert) |
| Vorschau-JPG | 903 × 1080 = Seite (1404 × 1680) × 0,643; Verhältnis 0,8361 | belegt |
| Strichbreite | Zahl wird im Code **ohne Skalierung** als Stiftbreite in Ansichtspixeln übernommen. Test: Rendering mit Faktor 1,0 auf 1404er Breite liefert etwa 1,1-fache Tintenmenge der Vorschau (Schwarzschwelle 110; Faktor 0,75 → 0,75-fach), optisch deckungsgleich | Einheit „Pixel einer 1404 px breiten Ansicht“ ≈ belegt, genauer Faktor offen |
| Zeit | ms ab erstem Punkt des Strichs | belegt |
| Farbe | Float-RGBA 0..1 → ARGB; Proben nur Schwarz | Formel belegt, Farbvielfalt ungeprüft |
| Druck | 0..1 | belegt |
| Radierer/Gelöscht | Es gibt **keine** Löschmarkierung im Strichformat; Rendering aller Striche deckt sich mit der Vorschau (nichts Unsichtbares gefunden). Annahme: Gelöschte Striche werden beim Speichern entfernt | Annahme |
| Text, Bilder, Lesezeichen | **nicht in der `.bin`**, sondern in `pageElement[]`/`pageBookMarks[]` der Seiten-JSON (dort in den Proben leer) | belegt (für die Proben) |

---

## 4. Verifikation

`python3 tools/hinote_bin.py check tmp/device/exp/custom` → **14 von 14 Exporten fehlerfrei** (19 `.bin`, 4607 Striche,
78 119 Punkte, 0 Restbytes, 0 Plausibilitätswarnungen). Geprüft werden: Magic, alle Größenfelder untereinander,
Dateiende exakt am Ende des Abschlussblocks, Koordinaten innerhalb der Seite, Zeit monoton ab 0, Druck 0..1, Punktrollen,
Pinseltyp bekannt. Negativtests (angehängtes Byte, gekürzte Datei, falsche Kennung) werden erkannt.

Sichtvergleich: `python3 tools/hinote_bin.py render <Export.hinote> <Ausgabeverzeichnis>` (Pillow nötig) legt je Seite
ein JPG mit Vorschau links und eigenem Rendering rechts ab. Beispiel PI37, Seite 1: Positionen, Zeilenabstände und
Schriftbild stimmen überein (Hintergrundpunkte fehlen im eigenen Rendering, weil die Vorlage nicht mitgezeichnet wird).

## 5. Importierbar / nicht importierbar

| Inhalt | Importierbar | Anmerkung |
|---|---|---|
| Striche (Typ, Farbe, Breite, Punkte, Druck, Zeit) | ja | siehe Mapping in der Empfehlung |
| Seitenreihenfolge, Seitenzahl | ja | `pageNumber`; leere Seiten haben keine `.bin` |
| Titel | ja | Zeitstempel der Notiz fehlen → aus Seiten ableiten |
| Vorlage | teilweise | Tabelle 1.3; unbekannt → `blank` |
| Favorit/angeheftet | ja | Felder vorhanden, in Proben 0 |
| Lesezeichen | vermutlich | `pageBookMarks` in Proben leer |
| Textboxen, Bilder | **ungeprüft** | `pageElement[]` in Proben leer; Beispiel mit Inhalt erforderlich |
| Audioaufnahmen, Gliederung | **ungeprüft/nein** | Listen in Proben leer |
| Ordner/Kategorie | **nein** (nicht im Export) | extern beim Export festhalten |
| Verschlossene Notizen | nicht exportiert | – |
| Formgruppen (Abschlussblock) | nur lesen, nicht prüfbar | Proben ohne Gruppen |
| Vorschau-JPG | nur als Kontrollbild | enthält Hintergrund und Striche |

## 6. Empfehlung für den Kotlin-Import (Mapping HiNote → PaperNotes)

Koordinaten und Breiten werden beim Import einmal in Seitenkoordinaten der PaperNotes-Seite (1404 × 1872) umgerechnet.
HiNote-Seiten sind 1404 × 1680 groß (aus `pageRatio`); die Striche bleiben oben ausgerichtet, die Seite wird unten
um 192 px verlängert (Annahme: das entspricht der Darstellung von HiNote; am Gerät gegenprüfen).

| HiNote | PaperNotes | Regel |
|---|---|---|
| Notiz `noteTitle` | `Note.title` | direkt |
| `createTime`/`modifiedTime` der Notiz (0) | `Note.created`/`modified` | Minimum/Maximum der Seiten-`createTime`/`modifiedTime` |
| `isFavorite`, `isTop` | `favorite`, `pinned` | `== 1` |
| Ordner | `folderPath` | nicht im Export; beim Einmal-Export mitgeben (Unterordner), sonst leer |
| Seite (nach `pageNumber`) | `Page` | neue UUID; `width` = 1404, `height` = 1872 |
| Seite ohne `.bin` | leere `Page` | belegt (kommt vor) |
| `background` | `Page.template` | Tabelle 1.3 |
| `pageBookMarks` nicht leer | `bookmarked = true` | Annahme |
| Strich | `Stroke` | siehe unten |
| Pinseltyp 1/2/3/4/5/11/12/13 | `penType` = `PenCatalog`-`frameworkType` | 1→4, 2→2, 3→3, 4→6, 5→6, 11→3, 12→4, 13→4 (Spalte „Framework-Typ“ der Tabelle 2.7; 11→3 ist Annahme) |
| Farbe `(α, R, G, B)` Float | `color` ARGB-Int | `round(v·255)` je Kanal; Pinselwert „Intensität“ ignorieren |
| `width` | `Stroke.width` | `width` unverändert (Ansichtspixel bei 1404 px Breite); bei Marker/Textmarker prüfen |
| Punkt `x`, `y` | `Point.x`, `Point.y` | `x·1404/1000`, `y·1404/1000` (gleicher Maßstab für beide Achsen) |
| Punkt `t_ms` | `Point.time` | Strichstartzeit fehlt (0); Zeit relativ lassen oder Striche fortlaufend mit 0 beginnen |
| Punkt `pressure` | `Point.pressure` | direkt |
| Striche mit `link_index != -1` oder in Abschlussgruppen | überspringen | wie HiNote (`BinFile.visible_strokes`) |
| `pageElement[]` (Text/Bild) | vorerst nicht | erst mit Beispielexport, der Inhalt enthält |

Importregeln (AGENT.md): ZIP-Pfade kanonisieren (kein `..`), Größenlimits je Eintrag und gesamt, Kompressionsrate
begrenzen, `.bin`-Größenfelder gegen Dateilänge prüfen (der Python-Parser macht das vor jeder Allokation), kein Entpacken
auf die Platte nötig. Der Kotlin-Parser sollte `ByteBuffer.order(BIG_ENDIAN)` nutzen und nach dem Abschlussblock exakt das
Dateiende verlangen.
