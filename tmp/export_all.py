#!/usr/bin/env python3
"""Exportiert alle HiNote-Notizen per UI-Automation (adb + uiautomator) als .hinote.

Voraussetzung: Tablet entsperrt, HiNote installiert, USB-Debugging an.
Ablauf je Notiz: Menue (grid_item_menu / ib_folder_menu) -> "Exportieren" -> "HiNote".
Nach dem Export oeffnet HiNote den Dateimanager; BACK bringt HiNote zurueck.
Ziel auf dem Tablet: /sdcard/Documents/hinote/custom/
"""
import argparse, re, subprocess, sys, time
import xml.etree.ElementTree as ET

SERIAL = "TGPYD22602200007"
PKG = "com.huawei.hinote"
EXPORT_DIR = "/sdcard/Documents/hinote/custom"
MENU_EXPORT = ("Exportieren", "Export")
FMT_HINOTE = "HiNote"


def adb(*a, check=True):
    for attempt in range(5):
        r = subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True, text=True, timeout=60)
        if r.returncode and "not found" in r.stderr:
            subprocess.run(["adb", "-s", SERIAL, "wait-for-device"], timeout=60)
            time.sleep(2)
            continue
        break
    if check and r.returncode:
        raise RuntimeError(r.stderr.strip())
    return r.stdout


def ui():
    adb("shell", "uiautomator dump /sdcard/_ui.xml >/dev/null 2>&1")
    xml = adb("exec-out", "cat /sdcard/_ui.xml")
    return ET.fromstring(xml)


def bounds(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return x1, y1, x2, y2


def center(n):
    x1, y1, x2, y2 = bounds(n)
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(n):
    x, y = center(n)
    adb("shell", f"input tap {x} {y}")


def rid(n):
    return n.get("resource-id", "").split("/")[-1]


def find(root, rid_=None, text=None):
    return [n for n in root.iter("node")
            if (rid_ is None or rid(n) == rid_) and (text is None or n.get("text") in (text if isinstance(text, tuple) else (text,)))]


def foreground():
    out = adb("shell", "dumpsys activity activities | grep mResumedActivity | head -1")
    return out


def wait_hinote(timeout=10):
    t = time.time()
    while time.time() - t < timeout:
        if PKG in foreground():
            return True
        time.sleep(0.5)
    return False


def listing():
    return set(adb("shell", f"ls {EXPORT_DIR}", check=False).split())


def export_item(menu_node, title):
    """menu_node: klickbares Menue-Icon einer Notiz. Gibt True zurueck bei Erfolg."""
    before = listing()
    tap(menu_node)
    time.sleep(1.2)
    root = ui()
    ex = find(root, "menu_name", MENU_EXPORT)
    if not ex:
        print(f"  ! kein Export-Menue fuer {title}")
        adb("shell", "input keyevent KEYCODE_BACK")
        return False
    tap(ex[0])
    time.sleep(1.2)
    root = ui()
    fmt = find(root, "item_title", FMT_HINOTE)
    if not fmt:
        print(f"  ! kein HiNote-Format fuer {title}")
        adb("shell", "input keyevent KEYCODE_BACK")
        return False
    tap(fmt[0])
    # Export erzeugt neue Datei; Dateimanager kommt in den Vordergrund
    for _ in range(40):
        time.sleep(0.5)
        if listing() - before:
            break
    else:
        print(f"  ! keine neue Datei fuer {title}")
        return False
    time.sleep(1.0)
    if PKG not in foreground():
        adb("shell", "input keyevent KEYCODE_BACK")
    wait_hinote()
    time.sleep(1.0)
    return True


def note_cards(root):
    """Notizkarten (Einzelnotizen) mit Menue-Icon. Rueckgabe [(titel, menu_node)]."""
    res = []
    for item in find(root, "item_content"):
        menu = [n for n in item.iter("node") if rid(n) == "grid_item_menu"]
        title = [n for n in item.iter("node") if rid(n) == "caudal_title"]
        if menu and title:
            res.append((title[0].get("text"), menu[0]))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0, help="max. Anzahl Notizen (Test)")
    ap.add_argument("--dry-run", action="store_true")
    a = ap.parse_args()

    adb("shell", f"am start -n {PKG}/.HomeLauncherActivity")
    time.sleep(3)
    root = ui()
    for _ in range(3):  # offene Dialoge/Menues vom letzten Lauf wegraeumen
        if find(root, "alertTitle") or find(root, "menu_name"):
            adb("shell", "input keyevent KEYCODE_BACK")
            time.sleep(1)
            root = ui()
    cards = note_cards(root)
    print(f"{len(cards)} Notizen auf der aktuellen Ansicht sichtbar")
    done = 0
    for title, menu in cards:
        print(f"- {title}")
        if a.dry_run:
            continue
        if export_item(menu, title):
            done += 1
        if a.limit and done >= a.limit:
            break
        root = ui()  # Layout kann sich aendern
    print(f"fertig: {done} exportiert")


if __name__ == "__main__":
    main()
