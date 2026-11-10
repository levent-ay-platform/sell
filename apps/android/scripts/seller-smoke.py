#!/usr/bin/env python3
"""Synthetic Android emulator smoke: app must remain alive after seller navigation.

Run only in disposable GitHub-hosted emulator; no real documents, sessions or data.
"""
import subprocess
import time
import re
import xml.etree.ElementTree as ET
from pathlib import Path

PACKAGE="tn.malak.app"
TMP=Path("/tmp/malak-android-seller-smoke")
TMP.mkdir(exist_ok=True)
def adb(*args,check=True):
    return subprocess.run(["adb",*args],capture_output=True,text=True,check=check,timeout=40)
def dump_nodes():
    p=adb("shell","uiautomator","dump","/sdcard/malak-ui.xml",check=False)
    print("UI dump status:",p.returncode,(p.stderr or "")[-160:])
    raw=adb("exec-out","cat","/sdcard/malak-ui.xml",check=False).stdout
    if not raw.strip():raise RuntimeError("UI dump empty")
    root=ET.fromstring(raw)
    return list(root.iter("node"))
def texts(nodes):
    return [n.get("text") or n.get("content-desc") or "" for n in nodes if n.get("text") or n.get("content-desc")]
def click_text(labels):
    for attempt in range(3):
        nodes=dump_nodes()
        for n in nodes:
            label=n.get("text","")
            if any(label==x for x in labels):
                rect=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",n.get("bounds",""))
                if not rect:continue
                x1,y1,x2,y2=map(int,rect.groups())
                print("Tap selected client control:",label,"at",(x1+x2)//2,(y1+y2)//2)
                adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
                return
        if attempt<2:
            adb("shell","input","swipe","190","650","190","260","330")
            time.sleep(1)
    raise AssertionError(f"Seller entry not found in actual Android UI: {labels}; visible={texts(nodes)[:40]}")
def crash_log():
    result=adb("logcat","-d","-b","crash","-v","brief",check=False)
    log=result.stdout
    print("CRASH BUFFER END:\n"+log[-15000:])
    return log
def assert_alive_and_seller():
    pid=adb("shell","pidof",PACKAGE,check=False).stdout.strip()
    print("PID after entry:",pid)
    time.sleep(1)
    nodes=dump_nodes()
    visible=texts(nodes)
    print("Seller-visible labels:",visible[:65])
    if not pid or not any(x in visible for x in ("Description du terrain","تفاصيل الأرض","Déposer un terrain","إضافة أرض")):
        crash_log()
        raise AssertionError("Clicking Add Land closed the app or did not render seller form")
def main():
    print(adb("shell","am","start","-W","-n",PACKAGE+"/.MainActivity").stdout)
    time.sleep(3)
    print("Home visible labels:",texts(dump_nodes())[:40])
    click_text(["Déposer un terrain","أضف أرضك"])
    time.sleep(2)
    assert_alive_and_seller()
    with open(TMP/"seller-open.png","wb") as out:
        shot=subprocess.run(["adb","exec-out","screencap","-p"],stdout=out,stderr=subprocess.PIPE,timeout=30)
        if shot.returncode: print("Screen capture unavailable (nonblocking)")
    print("PASS: app stayed alive and seller form rendered after actual tap on disposable emulator")
if __name__=="__main__":
    try:main()
    except Exception:
        crash_log()
        raise
