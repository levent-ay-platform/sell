#!/usr/bin/env python3
"""Offline-only Android login guard smoke; synthetic phone, no ID photos.

This test cannot exercise the authenticated seller/camera/OCR journey:
the hosted emulator has NO Malak HTTPS QA server configured.
"""
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PKG="tn.malak.app"
OUT=Path("/tmp/malak-android-offline-qa")
OUT.mkdir(exist_ok=True)

def adb(*args,check=True):
    return subprocess.run(["adb",*args],capture_output=True,text=True,
                          check=check,timeout=40)

def nodes():
    adb("shell","uiautomator","dump","/sdcard/malak-ui.xml",check=False)
    result=adb("exec-out","cat","/sdcard/malak-ui.xml",check=False)
    if not result.stdout.strip():
        raise AssertionError("Android UI unavailable")
    return list(ET.fromstring(result.stdout).iter("node"))

def shown():
    return [n.get("text") or n.get("content-desc") or ""
            for n in nodes() if n.get("text") or n.get("content-desc")]

def tap(labels):
    for i in range(3):
        for n in nodes():
            if n.get("text","") not in labels:
                continue
            bounds=re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]",
                                n.get("bounds",""))
            if bounds:
                x1,y1,x2,y2=map(int,bounds.groups())
                adb("shell","input","tap",str((x1+x2)//2),
                    str((y1+y2)//2))
                return
        adb("shell","input","swipe","180","660","180","280","320")
        time.sleep(1)
    raise AssertionError("Expected login control missing")

def main():
    adb("shell","am","start","-W","-n",PKG+"/.MainActivity")
    time.sleep(3)
    tap(["Numéro tunisien","رقم الهاتف التونسي","Tunisian phone number"])
    adb("shell","input","text","29500000") # fictional test-only number
    adb("shell","input","keyevent","KEYCODE_BACK")
    tap(["Continuer","متابعة","Continue"])
    time.sleep(1)
    tap(["Code d’accès","رمز الدخول","Access code"])
    adb("shell","input","text","0000")
    adb("shell","input","keyevent","KEYCODE_BACK")
    tap(["Accéder à Malak","الدخول إلى ملاك","Continue to Malak"])
    time.sleep(1)
    incorrect=shown()
    if not any(x in incorrect for x in [
        "Code incorrect. Réessayez.","الرمز غير صحيح. حاول مجدداً.",
        "Incorrect code. Try again."]):
        raise AssertionError("Incorrect access code not rejected")
    tap(["Code d’accès","رمز الدخول","Access code"])
    for _ in range(4):
        adb("shell","input","keyevent","KEYCODE_DEL")
    adb("shell","input","text","1234")
    adb("shell","input","keyevent","KEYCODE_BACK")
    tap(["Accéder à Malak","الدخول إلى ملاك","Continue to Malak"])
    time.sleep(3)
    visible=shown()
    if not any(x in visible for x in [
        "Connexion indisponible. Réessayez.",
        "تعذّر الاتصال بالخدمة. حاول مجدداً.",
        "Connection unavailable. Please try again."]):
        raise AssertionError("No server must show connection unavailable")
    if any("Déposer un terrain" in x or "إضافة أرض" in x for x in visible):
        raise AssertionError("Offline code bypassed required server session")
    if not adb("shell","pidof",PKG,check=False).stdout.strip():
        raise AssertionError("Offline login failure crashed Android")
    with (OUT/"offline-login.png").open("wb") as output:
        result=subprocess.run(["adb","exec-out","screencap","-p"],
                              stdout=output,stderr=subprocess.PIPE,timeout=30)
        if result.returncode:
            raise AssertionError("Offline login screenshot failed")
    print("PASS: offline 0000 rejected, 1234 cannot forge a server session; app alive")
    print("NOT TESTED: seller navigation, camera, real OCR, certificate comparison")

if __name__=="__main__":
    main()
