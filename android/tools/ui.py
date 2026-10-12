#!/usr/bin/env python3
"""Tiny UI driver for the smoke test: find things by their on-screen text or
content description and tap them, using `uiautomator dump`.

Compose screens expose the same accessibility tree a screen reader reads, so
this tests what a person would see rather than internal state.

  ui.py wait   "Welcome" [seconds]      block until the text is on screen
  ui.py tap    "Skip" [seconds]         wait for it, then tap its centre
  ui.py has    "Album Empty"            exit 0 if on screen now, else 1
  ui.py dump                            print every visible text
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True, timeout=60)


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    out = adb("exec-out", "cat", "/sdcard/ui.xml").stdout
    if "<hierarchy" not in out:
        return []
    return list(ET.fromstring(out[out.index("<hierarchy"):]).iter("node"))


def label(n):
    return (n.get("text") or "") + "\n" + (n.get("content-desc") or "")


def find(needle):
    for n in nodes():
        if needle.lower() in label(n).lower():
            return n
    return None


def centre(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def wait(needle, seconds):
    end = time.time() + seconds
    while time.time() < end:
        n = find(needle)
        if n is not None:
            return n
        time.sleep(1.5)
    return None


def main():
    cmd, args = sys.argv[1], sys.argv[2:]
    if cmd == "dump":
        for n in nodes():
            t = label(n).strip()
            if t:
                print(repr(t))
        return 0
    needle = args[0]
    seconds = float(args[1]) if len(args) > 1 else 30
    if cmd == "has":
        # Compare with None: an ElementTree node with no children is falsy, so a
        # plain truth test reports every leaf (every text label) as missing.
        return 0 if find(needle) is not None else 1
    n = wait(needle, seconds)
    if n is None:
        print(f"NOT FOUND within {seconds:.0f}s: {needle!r}. On screen instead:", file=sys.stderr)
        for m in nodes():
            t = label(m).strip().replace("\n", " | ")
            if t:
                print("   ", t[:100], file=sys.stderr)
        return 1
    if cmd == "tap":
        x, y = centre(n)
        adb("shell", "input", "tap", str(x), str(y))
    return 0


sys.exit(main())
