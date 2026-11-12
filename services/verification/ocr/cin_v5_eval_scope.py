"""Evaluation denominators for five PRIVATE owner-reviewed QA images.

Only image indices are public. No card scans, names, DOB values or CIN
digits belong in this repository or in GitHub Actions.
These indices are NOT used as runtime bypasses or OCR inference inputs.
"""
from datetime import date
import re

CIN_EVALUABLE=frozenset((1,2,5))
DOB_EVALUABLE=frozenset((1,2,3,4,5))
ALL_SAMPLES=frozenset(range(1,6))


def scope_for(index):
    if type(index) is not int or index not in ALL_SAMPLES:
        raise ValueError("unexpected_private_sample_index")
    return {"cin":index in CIN_EVALUABLE,"birthDate":index in DOB_EVALUABLE}


def score_availability(rows):
    """Availability only; exact accuracy needs separately verified private truth."""
    if not isinstance(rows,list) or len(rows)!=5:
        raise ValueError("exact_five_required")
    seen=set()
    cin_ok=birth_ok=0
    for row in rows:
        if not isinstance(row,dict):
            raise ValueError("bad_sample")
        idx=row.get("index")
        if type(idx) is not int or idx in seen:
            raise ValueError("duplicate_or_invalid_index")
        flags=scope_for(idx)
        seen.add(idx)
        fields=row.get("fields")
        if not isinstance(fields,dict):
            raise ValueError("missing_fields")
        cin=fields.get("cinLast4")
        dob=fields.get("birthDate")
        # Availability is not accuracy. Do not score masked/unseen CIN.
        if flags["cin"] and isinstance(cin,str) and re.fullmatch(r"[0-9]{4}",cin):
            cin_ok+=1
        if flags["birthDate"] and isinstance(dob,str):
            try:
                day=date.fromisoformat(dob)
                if date(1900,1,1)<=day<=date.today():
                    birth_ok+=1
            except ValueError:
                pass
    if seen!=ALL_SAMPLES:
        raise ValueError("missing_private_samples")
    return {"cin_visible_returned":cin_ok,"cin_visible_total":3,
            "birth_returned":birth_ok,"birth_total":5,
            "accuracy":"UNMEASURED_WITHOUT_PRIVATE_GROUND_TRUTH"}
