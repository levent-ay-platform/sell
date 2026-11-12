#!/usr/bin/env python3
"""Warm, private Huawei Arabic v5 OCR over an AF_UNIX socket (NO TCP port).

This process receives only an already-authorized temporary image from the
protected Malak gateway, returns preliminary fields, never logs personal data,
and never stores the image on disk. Model weights load ONCE per process.
"""
import json
import os
import signal
import socket
import stat
import struct
import sys

import cv2
import numpy as np

from cin_v5 import MAX_BYTES, MAX_EDGE, extract_fields, recognizer

SOCKET_PATH="/run/malak-ocr/ocr-v5.sock"
MAX_RESPONSE=4096


def decode_image(data,mime):
    if mime==1:
        if data[:3]!=b"\xff\xd8\xff":
            raise ValueError("bad jpeg magic")
    elif mime==2:
        if data[:8]!=b"\x89PNG\r\n\x1a\n":
            raise ValueError("bad png magic")
    else:
        raise ValueError("unsupported media type")
    image=cv2.imdecode(np.frombuffer(data,dtype=np.uint8),cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("bad image data")
    h,w=image.shape[:2]
    if not(175<=min(h,w) and max(h,w)<=MAX_EDGE and
           h*w<=25_000_000):
        raise ValueError("image dimensions out of range")
    return image


def recv_exact(conn,n):
    data=bytearray()
    while len(data)<n:
        part=conn.recv(min(32768,n-len(data)))
        if not part:
            raise ValueError("incomplete private request")
        data.extend(part)
    return data


def handle(conn,session,letters):
    conn.settimeout(12)
    header=recv_exact(conn,5)
    size=struct.unpack(">I",header[:4])[0]
    media=header[4]
    if size<20 or size>MAX_BYTES or media not in(1,2):
        raise ValueError("private request rejected")
    source=recv_exact(conn,size)
    try:
        image=decode_image(source,media)
        # Preview only; AUTH + consent + tenant/case access are checked by
        # the upstream gateway both BEFORE and AFTER inference.
        result=extract_fields(image,session,letters)
    finally:
        source[:]=b"\0"*len(source)
    answer=json.dumps({"ok":True,**result},ensure_ascii=False,
                      separators=(",",":")).encode("utf-8")
    if len(answer)>MAX_RESPONSE:
        raise ValueError("private output limit exceeded")
    conn.settimeout(25)
    conn.sendall(struct.pack(">I",len(answer))+answer)


def main():
    if len(sys.argv)!=1:
        raise ValueError("No variable socket paths or identities allowed")
    parent=os.path.dirname(SOCKET_PATH)
    st=os.lstat(parent)
    if not stat.S_ISDIR(st.st_mode) or st.st_uid!=os.geteuid() or \
       (st.st_mode&0o077)!=0:
        raise RuntimeError("PRIVATE_SOCKET_DIRECTORY_UNSAFE")
    if os.path.lexists(SOCKET_PATH):
        # Never steal an existing socket or follow user-controlled symlinks.
        raise RuntimeError("PRIVATE_SOCKET_ALREADY_EXISTS")
    session,letters=recognizer()
    old_umask=os.umask(0o177)
    server=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM)
    try:
        server.bind(SOCKET_PATH)
        os.chmod(SOCKET_PATH,0o600)
        server.listen(2)
        server.settimeout(1)
        def shutdown(_signal,_frame):
            raise SystemExit(0)
        signal.signal(signal.SIGTERM,shutdown)
        while True:
            try:
                conn,_=server.accept()
            except socket.timeout:
                continue
            try:
                with conn:
                    try:
                        handle(conn,session,letters)
                    except Exception:
                        # Never send raw OCR content, filenames or exception
                        # messages to the caller, the container or logs.
                        try:
                            reply=b'{"ok":false}'
                            conn.sendall(struct.pack(">I",len(reply))+reply)
                        except OSError:
                            pass
            finally:
                pass
    finally:
        os.umask(old_umask)
        server.close()
        try:
            os.unlink(SOCKET_PATH)
        except FileNotFoundError:
            pass


if __name__=="__main__":
    main()
