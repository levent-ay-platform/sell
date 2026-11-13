#!/usr/bin/env python3
"""Synthetic Android emulator smoke for the OFFLINE QA configuration.

A fixed pilot PIN must NOT invent a bearer/session or grant app entry
unless a real private HTTPS Malak gateway issues a scoped OCR token.
The connected-seller flow needs a separate private endpoint E2E run.
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
def enter_pilot(phone="29500000",check_bad_code=True):
    """Use only invented test numbers and the debug-only fixed pilot code."""
    print("Pilot visible labels:",texts(dump_nodes())[:40])
    click_text(["Numéro tunisien","رقم الهاتف التونسي","Tunisian phone number"])
    adb("shell","input","text",phone)
    adb("shell","input","keyevent","KEYCODE_BACK")
    click_text(["Continuer","متابعة","Continue"])
    time.sleep(1)
    click_text(["Code de test à 4 chiffres","رمز الدخول التجريبي (4 أرقام)","4-digit test code"])
    if check_bad_code:
        # Negative check: an incorrect PIN must not open the seller workspace.
        adb("shell","input","text","0000")
        adb("shell","input","keyevent","KEYCODE_BACK")
        click_text(["Entrer en mode pilote","الدخول إلى النسخة التجريبية","Enter pilot"])
        time.sleep(1)
        rejected=texts(dump_nodes())
        if not any(x in rejected for x in [
                "Code de test incorrect. Réessayez.",
                "الرمز التجريبي غير صحيح. حاول مجدداً.",
                "Incorrect test code. Try again."]):
            raise AssertionError("Incorrect pilot code did not show a rejection")
    click_text(["Code de test à 4 chiffres","رمز الدخول التجريبي (4 أرقام)","4-digit test code"])
    for _ in range(4):
        adb("shell","input","keyevent","KEYCODE_DEL")
    adb("shell","input","text","1234")
    adb("shell","input","keyevent","KEYCODE_BACK")
    click_text(["Entrer en mode pilote","الدخول إلى النسخة التجريبية","Enter pilot"])
    time.sleep(2)
    visible=texts(dump_nodes())
    print("After pilot login:",visible[:40])
    if not any("Service OCR pilote indisponible" in x or
               "تعذّر الاتصال بخدمة OCR التجريبية" in x or
               "Pilot OCR service unavailable" in x for x in visible):
        raise AssertionError("Offline QA failed to explain real server is required")
    if "MALAK / ملاك" in visible or any(
            "Accès pilote — numéro non vérifié" in x or
            "جلسة OCR تجريبية" in x for x in visible):
        raise AssertionError("Offline pilot PIN created a fake server session")

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
def relaunch():
    adb("shell","am","force-stop",PACKAGE)
    print(adb("shell","am","start","-W","-n",PACKAGE+"/.MainActivity").stdout)
    time.sleep(2)

def assert_draft_value(expected,should_exist=True):
    visible=texts(dump_nodes())
    found=any(expected in line for line in visible)
    if found != should_exist:
        raise AssertionError(
            f"Pilot draft isolation or process restart failed: expected_present={should_exist}; "
            f"visible={visible[:50]}"
        )

def main():
    # Hosted CI has no configured private gateway. It must test the actual
    # fail-closed login UI, not assert that 1234 bypasses server auth.
    print(adb("shell","am","start","-W","-n",PACKAGE+"/.MainActivity").stdout)
    time.sleep(3)
    enter_pilot()
    if not adb("shell","pidof",PACKAGE,check=False).stdout.strip():
        raise AssertionError("Pilot login error crashed Android")
    with open(TMP/"seller-open.png","wb") as out:
        shot=subprocess.run(["adb","exec-out","screencap","-p"],
            stdout=out,stderr=subprocess.PIPE,timeout=30)
        if shot.returncode:print("Screenshot unavailable")
    print("PASS: offline 1234 cannot create a fake OCR session; Android remains alive")

if __name__=="__main__":
    try:main()
    except Exception:
        crash_log()
        raise
