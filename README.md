[![PaperNotes by BOB](PaperNotes%20BY%20BOB-banner.jpg)](PaperNotes%20BY%20BOB-banner.jpg)

# PaperNotes by BOB

Notiz-App für das Huawei Paper (HMW-W09, Android 10, E-Ink) als Ersatz für HiNote. Handschrift wird flüssig über die geräteeigene E-Ink-Stiftschicht geschrieben; die Oberfläche besteht aus klassischen Views (ohne Animationen, hoher Kontrast) und ist zweisprachig (Deutsch/Englisch).

*English: PaperNotes by BOB is a handwritten note-taking app for the Huawei Paper E-Ink tablet (Android 10). It uses the device's native E-Ink pen layer, stores notes as `.pnote` files in `Documents/PaperNotes` and is available in German and English.*

## Funktionsstand

- Editor mit Stiften (Füllfeder, Kugelschreiber, Bleistift, Marker), Radierer, Lasso, Rückgängig/Wiederholen, mehreren Seiten und 16 Seitenvorlagen
- Startseite mit Ordnern, Raster- und Listenansicht, Suche, Sortierung, Favoriten und Fixieren
- Mehrfachauswahl mit Teilen, Verschieben und Löschen
- Papierkorb (wiederherstellen, endgültig löschen, leeren), Einstellungen und Über-Seite
- Offen: Seitenleiste, Zoom, Textbox/Bild/Audio, Export (Bild/PDF), Nextcloud-Sync, Übernahme der HiNote-Notizen (siehe `notiz-app-plan.md`)

## Bauen

Java 17 und das Android SDK sind nötig. Befehle, Signierung und Arbeitsregeln stehen in [AGENT.md](AGENT.md); kurz:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew test assembleDebug lint
```

## Lizenz

Freie Software unter der GNU General Public License v3.0 oder später (GPL-3.0-or-later). Den vollständigen Text enthält die Datei [LICENSE](LICENSE).

Copyright © 2025 hjochman. Dieses Programm wird ohne jede Gewährleistung verteilt.

*English: Free software under the GNU General Public License v3.0 or later. See [LICENSE](LICENSE). This program is distributed without any warranty.*
