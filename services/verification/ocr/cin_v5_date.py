"""Conservative, preliminary Tunisian CIN birth-date vote (no personal data).

The v5 CTC recognizer reverses the visual output of each individual crop.
The isolated ASCII digit crops therefore need a second reversal, exactly as
in the existing CIN-number decoding logic. Arabic month crops do not.
Never infer an absent date from a CIN number, age, or card expiry date.
"""
from datetime import date
import math
import re

# Printed Tunisian Arabic month names; no edit distance or model guessing.
_MONTHS = {
    'جانفي': 1, 'فيفري': 2, 'مارس': 3, 'افريل': 4, 'ماي': 5,
    'جوان': 6, 'جويلية': 7, 'اوت': 8, 'سبتمبر': 9, 'اكتوبر': 10,
    'نوفمبر': 11, 'ديسمبر': 12,
}
_CONFIDENCE_FLOOR = 0.82
_NUMERIC_CONFIDENCE_FLOOR = 0.72


def _high_confidence(sample, floor=_CONFIDENCE_FLOOR):
    if not isinstance(sample, (tuple, list)) or len(sample) != 2:
        return None
    text, confidence = sample
    if not isinstance(text, str) or not isinstance(confidence, (int, float)):
        return None
    if isinstance(confidence, bool) or not math.isfinite(confidence) or confidence < floor or confidence > 1:
        return None
    return text.strip()


def _ascii_digits(sample, width):
    text = _high_confidence(sample, _NUMERIC_CONFIDENCE_FLOOR)
    if text is None:
        return None
    # Disallow labels, slashes, joined dates, OCR substitutions or extra digits.
    text = re.sub(r'[ \t]+', '', text)
    if re.fullmatch(r'[0-9]{' + str(width) + r'}', text) is None:
        return None
    return text[::-1]


def _arabic_month(sample):
    text = _high_confidence(sample)
    if text is None:
        return None
    # Normalize only common Arabic hamza/alef glyphs and the OCR ى/ي pair.
    normalized = (text.replace('أ', 'ا').replace('إ', 'ا')
                  .replace('آ', 'ا').replace('ى', 'ي').replace('ـ', ''))
    if not re.fullmatch(r'[\u0621-\u064a]{2,12}', normalized):
        return None
    return _MONTHS.get(normalized)


def _two_agree(samples, decoder):
    if not isinstance(samples, (tuple, list)) or len(samples) != 2:
        return None
    first = decoder(samples[0])
    second = decoder(samples[1])
    if first is None or second is None or first != second:
        return None
    return first


def date_from_v5_votes(day_samples, month_samples, year_samples, *, today=None):
    """Return ISO date or None from two matching decodes of EACH isolated field.

    All three fields have to be present. These are preliminary OCR results;
    this does not validate an ID, person, or property ownership.
    """
    day = _two_agree(day_samples, lambda sample: _ascii_digits(sample, 2))
    month = _two_agree(month_samples, _arabic_month)
    year = _two_agree(year_samples, lambda sample: _ascii_digits(sample, 4))
    if day is None or month is None or year is None:
        return None
    try:
        year = int(year)
        if not 1900 <= year <= 2100:
            return None
        birth = date(year, int(month), int(day))
    except ValueError:
        return None
    limit = today if today is not None else date.today()
    if birth > limit:
        return None
    return birth.isoformat()
