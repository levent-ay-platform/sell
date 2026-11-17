"""Bounded, advisory quality checks for scanned Tunisian property documents.

This module never transmits pixels or OCR content; diagnostics contain only flags
and dimensions. Quality flags request recapture, NOT forgery/ownership judgments.
Designed to operate in the private OCR worker, never on publicly hosted files.
"""
from __future__ import annotations

from pathlib import Path
from PIL import Image, ImageFilter, ImageStat

MAX_INPUT_PIXELS = 12_000_000
MAX_SIDE = 20000
PREVIEW_MAX_SIDE = 640
VALID_EXIF_ORIENTATIONS = {1,2,3,4,5,6,7,8}

class UnsafeQualityInput(ValueError):
    pass


def _percentile(histogram: list[int], fraction: float) -> int:
    target = sum(histogram) * fraction
    seen = 0
    for value, count in enumerate(histogram):
        seen += count
        if seen >= target:
            return value
    return len(histogram) - 1


def inspect_photo_quality(path: str | Path) -> dict:
    """Fast static preflight (no OCR), metadata-only result; thresholds advisory.

    IMPORTANT: photographs with highly ornamental borders, seals or handwriting
    may score 'sharp' even when critical typed fields are blurry. Never use this
    heuristic to approve a certificate or overwrite OCR confidence.
    """
    source = Path(path)
    if source.is_symlink() or not source.is_file():
        raise UnsafeQualityInput('INVALID_IMAGE_SOURCE')
    try:
        Image.MAX_IMAGE_PIXELS = MAX_INPUT_PIXELS
        with Image.open(source) as image:
            if getattr(image, 'n_frames', 1) != 1:
                raise UnsafeQualityInput('MULTIFRAME_IMAGE')
            width, height = image.size
            if width < 40 or height < 40 or width > MAX_SIDE or height > MAX_SIDE or width * height > MAX_INPUT_PIXELS:
                raise UnsafeQualityInput('IMAGE_DIMENSIONS_OUT_OF_RANGE')
            orientation = image.getexif().get(274, 1)
            if orientation not in VALID_EXIF_ORIENTATIONS:
                orientation = 1
            gray = image.convert('L')
            gray.thumbnail((PREVIEW_MAX_SIDE, PREVIEW_MAX_SIDE), Image.Resampling.LANCZOS)
    except UnsafeQualityInput:
        raise
    except (OSError, ValueError, SyntaxError, OverflowError) as exc:
        raise UnsafeQualityInput('UNREADABLE_IMAGE') from exc

    hist = gray.histogram()
    contrast_range = _percentile(hist, .95) - _percentile(hist, .05)
    # Laplacian-like high-frequency energy; useful for highlighting likely blur,
    # not a guarantee of text sharpness or legal evidence integrity.
    lap = gray.filter(ImageFilter.Kernel((3,3), [0,-1,0,-1,4,-1,0,-1,0], scale=1, offset=128))
    # Pillow preserves outer filter pixels: ignore that artificial border to
    # avoid classifying a uniformly blank page as sharply focused.
    interior = lap.crop((4, 4, lap.width - 4, lap.height - 4))
    focus_variance = float(ImageStat.Stat(interior).var[0])
    warnings = []
    if min(width, height) < 700:
        warnings.append('LOW_RESOLUTION')
    if contrast_range < 50:
        warnings.append('LOW_CONTRAST')
    if focus_variance < 18:
        warnings.append('POSSIBLE_BLUR')
    if orientation not in (1,):
        warnings.append('REVIEW_ORIENTATION')
    return {
        'pixelWidth': width,
        'pixelHeight': height,
        'flags': warnings,
        'diagnostic': 'ADVISORY_ONLY',
        'pageEdgesConfirmed': False,
        'ownershipVerified': False,
    }
