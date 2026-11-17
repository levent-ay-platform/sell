#!/usr/bin/env python3
"""Authorized LOCAL ONLY benchmark for five consented Tunisian CIN fronts.

Run on the private Mac/Huawei QA host with a locally supplied encrypted
manifest. The manifest and all images stay LOCAL. GitHub/CI must never
receive the manifest, images, names or any real CIN/DOB. Console emits only
aggregate exact-match counts. This is NOT a production verification path.

Manifest structure (do not commit actual contents):
{"rows":[{"index":1,"image":"local-private-filename.png",
"expected":{"cinFull":"<8 private digits>","birthDate":"<YYYY-MM-DD>"}},...]}

For cases 3 and 4 set expected.cinFull to null (masked by the owner).
"""
import json
import os
from pathlib import Path
import sys

import cin_v5 as engine
from cin_v5_eval_scope import score_private_exact


def read_manifest(path):
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_CLOEXEC)
    try:
        st=os.fstat(fd)
        if not 2<=st.st_size<=30000 or not os.path.isfile(path):
            raise ValueError("private_manifest_invalid")
        buf=bytearray()
        while len(buf)<st.st_size:
            part=os.read(fd,min(4096,st.st_size-len(buf)))
            if not part:
                raise ValueError("private_manifest_truncated")
            buf.extend(part)
    finally:
        os.close(fd)
    try:
        data=json.loads(buf.decode("utf8"))
        if not isinstance(data,dict) or not isinstance(data.get("rows"),list):
            raise ValueError("private_manifest_bad_shape")
        return data["rows"]
    finally:
        buf[:]=b"\0"*len(buf)


def run(private_dir,manifest_path,model_root):
    root=Path(private_dir).resolve(strict=True)
    manifest=Path(manifest_path).resolve(strict=True)
    if manifest.parent!=root:
        raise ValueError("private_manifest_location")
    if not root.is_dir() or not manifest.is_file():
        raise ValueError("private_source_missing")
    rows=read_manifest(str(manifest))
    if len(rows)!=5:
        raise ValueError("private_exact_five_required")
    engine.MODEL_ROOT=Path(model_root)
    session,alphabet=engine.recognizer()
    checked=[]
    for row in rows:
        if not isinstance(row,dict):
            raise ValueError("private_bad_record")
        filename=row.get("image")
        idx=row.get("index")
        if not isinstance(filename,str) or not filename or (
                filename!=Path(filename).name) or (
                Path(filename).suffix.lower() not in
                (".png",".jpg",".jpeg")):
            raise ValueError("private_bad_image_name")
        image_path=root/filename
        if image_path.is_symlink():
            raise ValueError("private_symlink_refused")
        image=engine.image_from_private_file(str(image_path))
        card=None
        try:
            fields=engine.extract_fields(image,session,alphabet)
            card=engine.normalize_card(image)
            full=engine._cin_full_front(session,alphabet,card) if (
                card is not None and idx in (1,2,5)) else None
            # Strictly reflect what the production worker would return.
            if full is None or fields["cinLast4"]!=full[-4:]:
                full=None
            checked.append({
                "index":idx,
                "expected":row.get("expected"),
                "observed":{"cinFull":full,"birthDate":fields["birthDate"]}
            })
        finally:
            if card is not None:
                card.fill(0)
            image.fill(0)
    counts=score_private_exact(checked)
    # No filenames, actual digits, DOBs, names, or OCR snippets in stdout.
    print(json.dumps(counts,separators=(",",":"),sort_keys=True))


if __name__=="__main__":
    if len(sys.argv)!=4:
        raise SystemExit("usage: qa_private_five.py <private_dir> <manifest_file> <local_model_root>")
    try:
        run(sys.argv[1],sys.argv[2],sys.argv[3])
    except Exception:
        # Do not reveal private file names or manifest text in tracebacks.
        print("PRIVATE_QA_NOT_COMPLETED",file=sys.stderr)
        raise SystemExit(2)
