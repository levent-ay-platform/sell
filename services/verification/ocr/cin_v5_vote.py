"""Private Arabic CIN field consensus: no model imports, no identity samples."""
from collections import Counter
import re

_AR = re.compile(r"^[\u0621-\u064a]+(?: [\u0621-\u064a]+){0,3}$")


def _valid(text):
    if not isinstance(text, str):
        return None
    result = " ".join(text.split())
    if not (2 <= len(result) <= 55 and _AR.fullmatch(result)):
        return None
    if any(len(token) < 2 for token in result.split(" ")):
        return None
    return result


def select_name(samples):
    """Select at least two agreeing independent crops, else (None, 0).

    Comparison tolerates ى/ي, but displays exactly one untouched OCR candidate.
    Model confidence is never itself sufficient to select a name.
    """
    if len(samples) != 3:
        return None, 0.
    valid = []
    for value, confidence in samples:
        text = _valid(value)
        if (text is not None and isinstance(confidence, (float, int)) and
                0.72 <= confidence <= 1.0):
            valid.append((text.replace("\u0649", "\u064a"), text, float(confidence)))
    votes = Counter(item[0] for item in valid)
    if not votes:
        return None, 0.
    best, count = votes.most_common(1)[0]
    if count < 2 or (len(votes) > 1 and votes.most_common(2)[1][1] == count):
        return None, 0.
    agreeing = [(raw, confidence) for key, raw, confidence in valid if key == best]
    chosen = max(agreeing, key=lambda item: item[1])[0]
    return chosen, min(confidence for _, confidence in agreeing)
