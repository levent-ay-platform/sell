"""Field-specific CTC decode for Tunisian CIN/day/year; fictional-only tests.

Arabic-v5 sometimes predicts O over 0 on a small numeric crop. A restricted
digit vocabulary recovers a digit only when the model actually assigns
substantial probability to that digit. No fuzzy edit distance, no insertion,
no completion of missing digits, no ID/date lookup table.
"""
import numpy as np

_DIGITS=frozenset("0123456789")


def decode_numeric_ctc(scores,characters):
    values=np.asarray(scores)
    if values.ndim!=2 or values.shape[0]>1024 or values.shape[1]<11:
        return "",0.0
    if not np.isfinite(values).all():
        return "",0.0
    allowed=[0]+[i+1 for i,ch in enumerate(characters)
                 if ch in _DIGITS and i+1<values.shape[1]]
    if len(allowed)!=11:
        return "",0.0
    # Scores are probabilities emitted by the fixed public ONNX recognizer.
    if values.min()<0 or values.max()>1.0001:
        return "",0.0
    last=0
    emitted=[]
    probabilities=[]
    for row in values:
        pos=allowed[int(np.argmax(row[allowed]))]
        if pos==0:
            last=0
            continue
        if pos!=last:
            probability=float(row[pos])
            best=float(np.max(row))
            # A non-digit token with near-zero numeric support must not
            # transform into a plausible number just from the field label.
            if probability<.12 or best>probability*5.0:
                return "",0.0
            emitted.append(characters[pos-1])
            probabilities.append(probability)
        last=pos
    if not emitted or len(emitted)>16:
        return "",0.0
    return "".join(emitted)[::-1],sum(probabilities)/len(probabilities)
