#!/usr/bin/env python3
"""Drives the installed app on an emulator and saves screenshots of the browser UI (CI only: .github/workflows/ui-shots.yml).

Usage: ui_shots.py <out_dir> <tag>
Finds controls through `uiautomator dump` (content descriptions first, toolbar position as a fallback),
so it works on any build and in both LTR and RTL. Fails if the app crashes.
"""
import os
import re
import subprocess
import sys
import time

PKG = "com.aib.browser"
out, tag = sys.argv[1], sys.argv[2]
os.makedirs(out, exist_ok=True)


def adb(*a, check=True):
    return subprocess.run(["adb", *a], capture_output=True, text=True, check=check).stdout


def shot(name):
    with open(os.path.join(out, f"{tag}_{name}.png"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, check=True).stdout)
    print("shot", tag, name)


def nodes():
    for _ in range(4):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        xml = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        if "<node" in xml:
            break
        time.sleep(1)
    res = []
    for m in re.finditer(r"<node [^>]*>", xml):
        n = m.group(0)
        g = lambda k: (re.search(k + r'="([^"]*)"', n) or [None, ""])[1]
        b = list(map(int, re.findall(r"\d+", g("bounds"))))
        if len(b) == 4:
            res.append({"cls": g("class"), "desc": g("content-desc"), "text": g("text"), "click": g("clickable") == "true", "b": b})
    return res


def center(n):
    b = n["b"]
    return (b[0] + b[2]) // 2, (b[1] + b[3]) // 2


def tap(x, y):
    adb("shell", "input", "tap", str(x), str(y))


def screen():
    w, h = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
    return w, h


def toolbar(ns, h):
    """The five toolbar buttons in reading order (back, forward, AI, ..., reload)."""
    bs = [n for n in ns if n["click"] and n["cls"].endswith("ImageView") and n["b"][1] > h * 0.75]
    bs.sort(key=lambda n: n["b"][0])
    return bs


def find(ns, desc):
    for n in ns:
        if n["desc"] == desc:
            return n
    return None


def by_desc_or_index(desc, idx, rtl):
    ns = nodes()
    n = find(ns, desc)
    if n:
        return n
    tb = toolbar(ns, screen()[1])
    if rtl:
        tb = tb[::-1]
    return tb[idx] if len(tb) > idx else None


def crashed():
    log = adb("logcat", "-d", "-b", "crash", check=False)
    return PKG in log and "FATAL" in log


def main():
    rtl = tag.startswith("ar")
    w, h = screen()
    adb("logcat", "-c", check=False)
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "am", "start", "-W", "-n", f"{PKG}/.MainActivity", "-d", "https://en.wikipedia.org/wiki/Video_super-resolution")
    time.sleep(14)
    shot("01_page")

    # address bar editing
    ns = nodes()
    tb = toolbar(ns, h)
    pill = find(ns, "شريط العنوان")
    if pill:
        tap(*center(pill))
    elif tb:
        tap(w // 2, tb[0]["b"][1] - (tb[0]["b"][3] - tb[0]["b"][1]))
    time.sleep(2)
    shot("02_edit")
    adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1)
    adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1)

    # settings sheet, then scrolled
    n = by_desc_or_index("الإعدادات", 2, rtl)
    if n:
        tap(*center(n)); time.sleep(2)
        shot("03_settings")
        for i in (1, 2, 3):
            adb("shell", "input", "swipe", str(w // 2), str(int(h * 0.80)), str(w // 2), str(int(h * 0.30)), "350")
            time.sleep(1.2)
            shot(f"0{3 + i}_settings_scroll{i}")
        adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.5)

    # history sheet
    n = by_desc_or_index("السجل", 3, rtl)
    if n:
        tap(*center(n)); time.sleep(2)
        shot("07_history")
        adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.5)

    # landscape
    adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
    adb("shell", "settings", "put", "system", "user_rotation", "1")
    time.sleep(3)
    shot("08_landscape")
    adb("shell", "settings", "put", "system", "user_rotation", "0")
    time.sleep(2)

    if crashed():
        print(adb("logcat", "-d", "-b", "crash", check=False))
        sys.exit("app crashed")
    with open(os.path.join(out, f"{tag}_ui.xml"), "w") as f:
        f.write(adb("shell", "cat", "/sdcard/ui.xml", check=False))


main()
