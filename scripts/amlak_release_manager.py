#!/usr/bin/env python3
"""Read-only CI gate for an exact Amlak candidate. Never deploys or merges."""
import argparse
import json
import re
import subprocess
import sys

REPO = "levent-ay-platform/sell"
GATES = {
    "android": (".github/workflows/amlak-full-release-gate.yml", ("connected-apk", "offline-intake-ui")),
    "private": (".github/workflows/amlak-private-attachments.yml", ("private-contract",)),
    "ocr": (".github/workflows/amlak-intake-ocr.yml", ("birth-date",)),
}
APK_NAME = "amlak-full-gate-connected-qa"

def api(path):
    result = subprocess.run(["gh", "api", "--method", "GET", "repos/"+REPO+"/"+path],
                            check=True, capture_output=True, text=True, timeout=40)
    return json.loads(result.stdout)

def evaluate(sha, run_ids, read=api):
    if not re.fullmatch(r"[a-f0-9]{40}", sha):
        raise ValueError("Exact full candidate SHA required")
    report = {"candidate": sha, "gates": {}, "artifact": None,
              "status": "BLOCKED", "deployment": "NOT_PERFORMED",
              "scope": "CI only; live enrolled pilot and legal evidence acceptance remain separate"}
    all_pass = True
    for key, (path, expected) in GATES.items():
        rid = run_ids[key]
        run = read(f"actions/runs/{rid}")
        gate = {"run": rid, "status": "BLOCKED", "jobs": {}}
        report["gates"][key] = gate
        if run.get("head_sha") != sha or run.get("path") != path or run.get("event") != "push":
            gate["reason"] = "wrong revision, workflow or event"
        elif run.get("status") != "completed":
            gate["status"] = "PENDING"
        elif run.get("conclusion") != "success":
            gate["reason"] = "workflow did not succeed"
        else:
            jobs = read(f"actions/runs/{rid}/jobs?per_page=100").get("jobs", [])
            for name in expected:
                found = [j for j in jobs if j.get("name")==name]
                gate["jobs"][name] = (
                    "PASS" if len(found)==1 and found[0].get("head_sha")==sha
                    and found[0].get("status")=="completed"
                    and found[0].get("conclusion")=="success" else "BLOCKED")
            if all(v=="PASS" for v in gate["jobs"].values()):
                gate["status"] = "PASS"
        all_pass &= gate["status"]=="PASS"
    if report["gates"]["android"]["status"]=="PASS":
        artifacts=read(f"actions/runs/{run_ids['android']}/artifacts?per_page=100").get("artifacts",[])
        found=[a for a in artifacts if a.get("name")==APK_NAME and a.get("expired") is False
               and type(a.get("size_in_bytes")) is int and 0<a["size_in_bytes"]<=60_000_000
               and re.fullmatch(r"sha256:[a-f0-9]{64}",a.get("digest") or "")]
        if len(found)==1:
            a=found[0]
            report["artifact"]={"id":a["id"],"digest":a["digest"],"size":a["size_in_bytes"],
                "url":f"https://github.com/{REPO}/actions/runs/{run_ids['android']}/artifacts/{a['id']}"}
    if all_pass and report["artifact"]:
        report["status"]="CI_CANDIDATE_READY"
    elif any(g["status"]=="PENDING" for g in report["gates"].values()) and not any(
            g["status"]=="BLOCKED" for g in report["gates"].values()):
        report["status"]="PENDING"
    return report

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sha", required=True)
    for gate in GATES:
        parser.add_argument("--"+gate,required=True,type=int)
    args=parser.parse_args()
    try:
        report=evaluate(args.sha,{k:getattr(args,k) for k in GATES})
    except (ValueError, KeyError, subprocess.SubprocessError, OSError):
        print(json.dumps({"status":"BLOCKED","reason":"invalid input or CI metadata unavailable"}))
        return 1
    print(json.dumps(report,indent=2))
    return 0 if report["status"]=="CI_CANDIDATE_READY" else 2

if __name__=="__main__":
    sys.exit(main())
