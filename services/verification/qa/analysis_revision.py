"""Version the document result cache separately from the OCR service.
An explicit revision is set only by a verified deployment, never a request.
"""
import os
import re

def document_engine_revision():
    explicit=os.environ.get("MALAK_DOCUMENT_ENGINE_REVISION")
    if explicit is None:
        return os.environ.get("MALAK_SOURCE_SHA","development")
    if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}",explicit):
        raise ValueError("INVALID_DOCUMENT_ENGINE_REVISION")
    return explicit

def analysis_cache_revision():
    source=os.environ.get("MALAK_SOURCE_SHA","development")
    if "MALAK_DOCUMENT_ENGINE_REVISION" not in os.environ:
        return source
    return source+":document:"+document_engine_revision()
