#!/usr/bin/env python3
"""Malak QA-only MAC OCR ingress, listening on 127.0.0.1 behind Tailscale Serve.

Development purpose: exercise a genuine in-memory, time-limited server session
from the debug +216 phone / 1234, through the REAL pinned Arabic v5 ONNX
recognizer. NOT legal ID verification, not public signup, not a production API.
Preview images remain transient. Completed comparisons retain encrypted private
review evidence for administrator submission. No image/text debug output, client
file paths, public TCP bind, or OCR/database secrets in the Android APK.
"""
import hashlib
import http.server
import json
import os
import re
import secrets
import socket
import sys
import time
import threading
from collections import defaultdict, deque
from pathlib import Path

# Suppress Python tracebacks containing request details.
sys.tracebacklimit = 0

PHONE = re.compile(r"^\+216[2-9][0-9]{7}$")
DEVICE = re.compile(r"^[A-Za-z0-9_-]{43}$")
BEARER = DEVICE
LOGIN_PATH = "/internal/v1/pilot/login"
OCR_PATH = "/internal/v1/pilot/preview-cin"
MAX_IMAGE_BYTES = 6 * 1024 * 1024
SESSION_SECONDS = 12 * 60 * 60
PORT = 18443
NULL_FIELDS = {
    "surname": None, "givenName": None, "cinLast4": None, "cinFull": None,
    "birthDate": None, "confidencePercent": 0
}


class MemoryPilot:
    def __init__(self, recognizer, alphabet, ocr_module):
        self.recognizer = recognizer
        self.alphabet = alphabet
        self.engine = ocr_module
        self.tokens = {}
        self.store = None
        self.inference_lock = threading.Lock()
        self.failures = defaultdict(deque)

    def login(self, body, ip):
        if not isinstance(body, dict) or set(body) != {"phone","code","deviceKey"}:
            return None
        phone, code, device = (body.get("phone"),body.get("code"),body.get("deviceKey"))
        if not isinstance(phone, str) or not PHONE.fullmatch(phone) or not isinstance(
                code, str) or not re.fullmatch(r"[0-9]{4}", code) or not isinstance(
                    device, str) or not DEVICE.fullmatch(device):
            return None
        now = time.monotonic()
        key = hashlib.sha256((ip+":"+phone+":"+device).encode()).digest()
        recent = self.failures[key]
        while recent and now-recent[0] >= 900:
            recent.popleft()
        if len(recent) >= 5:
            return None
        if not secrets.compare_digest(code, "1234"):
            recent.append(now)
            return None
        if len(self.tokens) >= 128:
            self.tokens = {k:v for k,v in self.tokens.items() if v[0] > now}
        if len(self.tokens) >= 128:
            return None
        if self.store is not None:
            return self.store.login(phone,code,device)
        token = secrets.token_urlsafe(32)
        self.tokens[hashlib.sha256(token.encode()).digest()] = [now+SESSION_SECONDS, 0]
        return {
            "ok": True, "pilotConfirmed": True, "identityVerified": False,
            "ownershipVerified": False, "mayPublish": False,
            "expiresInSeconds": SESSION_SECONDS, "bearer": token
        }

    def authorized(self, value):
        if not isinstance(value, str) or not value.startswith("Bearer "):
            return None
        token = value[7:]
        if not BEARER.fullmatch(token):
            return None
        if self.store is not None:
            return [time.monotonic()+60,0] if self.store.authenticate(token) else None
        digest = hashlib.sha256(token.encode()).digest()
        session = self.tokens.get(digest)
        if not session or session[0] <= time.monotonic():
            self.tokens.pop(digest, None)
            return None
        if session[1] >= 120:
            return None
        return session

    def read(self, payload, mime):
        import numpy as np
        import cv2
        if mime == "image/jpeg":
            if payload[:3] != b"\xff\xd8\xff":
                raise ValueError("bad JPEG")
        elif mime == "image/png":
            if payload[:8] != b"\x89PNG\r\n\x1a\n":
                raise ValueError("bad PNG")
        else:
            raise ValueError("unsupported image type")
        photo = cv2.imdecode(np.frombuffer(payload,dtype=np.uint8),cv2.IMREAD_COLOR)
        if photo is None:
            raise ValueError("invalid image")
        try:
            h,w = photo.shape[:2]
            if min(h,w) < 175 or max(h,w) > 6000 or h*w > 25_000_000:
                raise ValueError("invalid dimensions")
            if not self.inference_lock.acquire(blocking=False):raise RuntimeError("OCR busy")
            try:fields = self.engine.extract_fields(photo,self.recognizer,self.alphabet)
            finally:self.inference_lock.release()
            if not isinstance(fields,dict) or set(fields) != set(NULL_FIELDS):
                raise ValueError("bad OCR response")
            full=fields.get("cinFull")
            if full is not None and (not isinstance(full,str) or
                    not re.fullmatch(r"[0-9]{8}",full) or fields.get("cinLast4")!=full[-4:]):
                raise ValueError("inconsistent CIN")
            return {**fields,"ok":True,"pilotOcr":True,"schemaVersion":1,
                    "engine":"arabic-v5","identityVerified":False,
                    "ownershipVerified":False,"mayPublish":False}
        finally:
            photo.fill(0)


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "MalakQAPilot"
    sys_version = ""

    def log_message(self, *_args):
        # Avoid logging phone, bearer, path, payload or other private data.
        pass

    def result(self, status, payload):
        raw = json.dumps(payload,ensure_ascii=False,separators=(",",":")).encode()
        self.send_response(status)
        self.send_header("X-Request-Id",secrets.token_hex(12))
        self.send_header("X-Malak-Source-Revision",os.environ.get("MALAK_SOURCE_SHA","development"))
        self.send_header("Content-Type","application/json; charset=utf-8")
        self.send_header("Content-Length",str(len(raw)))
        self.send_header("Cache-Control","no-store, max-age=0")
        self.send_header("X-Content-Type-Options","nosniff")
        self.send_header("Connection","close")
        self.end_headers()
        self.wfile.write(raw)
        self.close_connection = True

    def do_GET(self):
        if getattr(self.server,"workflow",None) and self.server.workflow.dispatch(self,"GET"):return
        if self.path == "/healthz":
            self.result(200,{"ok":True,"engine":"arabic-v5",
                             "modelReady":True,"pilotQa":True})
        else:
            self.result(404,{"ok":False})

    def do_POST(self):
        if getattr(self.server,"workflow",None) and self.server.workflow.dispatch(self,"POST"):return
        if self.path not in (LOGIN_PATH,OCR_PATH):
            self.result(404,{"ok":False})
            return
        content_type = self.headers.get("Content-Type")
        try:
            if self.headers.get("Transfer-Encoding") or len(self.headers.get_all(
                    "Content-Length",[])) != 1:
                raise ValueError("bad framing")
            size = int(self.headers["Content-Length"])
            limit = 2048 if self.path == LOGIN_PATH else MAX_IMAGE_BYTES
            if size < 20 or size > limit:
                self.result(413,{"ok":False})
                return
            self.connection.settimeout(30)
            if self.path == LOGIN_PATH:
                if content_type != "application/json":
                    raise ValueError("login JSON required")
                body = self.rfile.read(size)
                if len(body) != size:
                    raise ValueError("truncated")
                decoded = json.loads(body.decode("utf-8"))
                reply = self.server.pilot.login(decoded,self.client_address[0])
                self.result(200,reply) if reply else self.result(401,{"ok":False})
                return
            auth = self.server.pilot.authorized(self.headers.get("Authorization"))
            if auth is None:
                self.result(401,{"ok":False})
                return
            if content_type != "application/octet-stream" or self.headers.get(
                    "X-Pilot-Ocr-Consent") != "1":
                raise ValueError("consent required")
            mime = self.headers.get("X-Claimed-Mime")
            if mime not in ("image/jpeg","image/png"):
                raise ValueError("unrecognized media")
            data = bytearray(self.rfile.read(size))
            if len(data) != size:
                raise ValueError("truncated")
            try:
                fields = self.server.pilot.read(data,mime)
                auth[1] += 1
                self.result(200,fields)
            finally:
                data[:] = b"\x00"*len(data)
        except (ValueError,UnicodeError,TypeError,OverflowError,
                json.JSONDecodeError,socket.timeout):
            self.result(400,{"ok":False})
        except Exception:
            # Inference errors MUST NOT leak any OCR text.
            self.result(503,{"ok":False})

    def do_PUT(self): self.result(405,{"ok":False})
    def do_DELETE(self): self.result(405,{"ok":False})
    def do_PATCH(self): self.result(405,{"ok":False})


class Server(http.server.ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True

def main():
    config_path=Path(__file__).with_name("runtime.json")
    if config_path.exists():
        config=json.loads(config_path.read_text())
        for key,value in config.items():
            if key.startswith("MALAK_") and isinstance(value,str):os.environ.setdefault(key,value)
    if os.environ.get("MALAK_PYTHON_DEPS"):sys.path.insert(0,os.environ["MALAK_PYTHON_DEPS"])
    if len(sys.argv)<3 or sys.argv[1]!="--model-root":
        raise SystemExit("usage: mac_pilot_ocr_http.py --model-root /private/ocr/model")
    # Resolve code from public repository with no private samples in the cwd.
    import cin_v5 as v5
    v5.MODEL_ROOT = Path(sys.argv[2])
    recognizer, alphabet = v5.recognizer()
    with Server(("127.0.0.1",int(os.environ.get("MALAK_PORT",PORT))),Handler) as server:
        server.pilot = MemoryPilot(recognizer,alphabet,v5)
        if os.environ.get("MALAK_PILOT_DATA"):
            from pilot_workflow import PilotStore
            from workflow_http import WorkflowRoutes
            from mac_pair_runtime import MacPairRuntime
            server.pilot.store=PilotStore(os.environ["MALAK_PILOT_DATA"])
            server.workflow=WorkflowRoutes(server.pilot.store,MacPairRuntime(server.pilot,
                os.environ["MALAK_VISION_BIN"],os.environ["MALAK_NODE_BIN"]))
        server.serve_forever(poll_interval=.5)

if __name__=="__main__":
    main()
