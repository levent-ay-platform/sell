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



def score_private_exact(rows):
    """Exact full-CIN/DOB benchmark; strict five-case private truth only.

    Caller holds source images and reference inside an approved local
    encrypted store. This function returns COUNTS ONLY, never raw strings.
    No hidden CIN is used in denominators and availability is not accuracy.
    """
    from datetime import date
    import re
    if not isinstance(rows,list) or len(rows)!=5:
        raise ValueError("private_exact_five_required")
    seen=set()
    counts={"cin_exact_matches":0,"cin_readable":0,"cin_evaluable":3,
            "birth_exact_matches":0,"birth_readable":0,"birth_evaluable":5}
    for row in rows:
        if not isinstance(row,dict):
            raise ValueError("bad_private_reference")
        idx=row.get("index")
        if type(idx) is not int or idx in seen or idx not in ALL_SAMPLES:
            raise ValueError("private_sample_mismatch")
        seen.add(idx)
        truth=row.get("expected")
        read=row.get("observed")
        if not isinstance(truth,dict) or not isinstance(read,dict):
            raise ValueError("bad_private_reference")
        expected_cin=truth.get("cinFull")
        observed_cin=read.get("cinFull")
        if idx in CIN_EVALUABLE:
            if not isinstance(expected_cin,str) or re.fullmatch(
                    r"[0-9]{8}",expected_cin) is None:
                raise ValueError("private_truth_incomplete")
            if isinstance(observed_cin,str) and re.fullmatch(
                    r"[0-9]{8}",observed_cin):
                counts["cin_readable"]+=1
                if observed_cin==expected_cin:
                    counts["cin_exact_matches"]+=1
        elif expected_cin is not None:
            raise ValueError("masked_cin_must_be_null")
        expected_birth=truth.get("birthDate")
        if not isinstance(expected_birth,str):
            raise ValueError("private_birth_truth_missing")
        try:
            validated=date.fromisoformat(expected_birth)
            if validated.isoformat()!=expected_birth or (
                    not date(1900,1,1)<=validated<=date.today()):
                raise ValueError
        except ValueError:
            raise ValueError("private_birth_truth_invalid") from None
        if isinstance(read.get("birthDate"),str):
            observed_birth=read["birthDate"]
            try:
                value=date.fromisoformat(observed_birth)
                if value.isoformat()==observed_birth and (
                        date(1900,1,1)<=value<=date.today()):
                    counts["birth_readable"]+=1
                    if observed_birth==expected_birth:
                        counts["birth_exact_matches"]+=1
            except ValueError:
                pass
    if seen!=ALL_SAMPLES:
        raise ValueError("private_cases_incomplete")
    return counts
