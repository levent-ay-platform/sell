"""Resolve a Pilot parcel through the currently approved V2 public projection."""
import json
import os
import re
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

UUID = re.compile(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}')
MIME_TYPES = {'application/pdf', 'image/png', 'image/jpeg'}
MAX_RENDITION = 16 * 1024 * 1024


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class PilotParcelBridge:
    def __init__(self, origin, token):
        url = urlsplit(origin)
        if (url.scheme != 'https' or not url.hostname or url.username or url.password
                or url.path not in ('','/') or url.query or url.fragment
                or not re.fullmatch(r'[A-Za-z0-9_-]{43}',token)):
            raise ValueError('Invalid Pilot parcel bridge configuration')
        self.origin = origin.rstrip('/')
        self.token = token

    @classmethod
    def configured(cls):
        origin = os.environ.get('MALAK_PILOT_PARCEL_ORIGIN','')
        token = os.environ.get('MALAK_PILOT_PARCEL_TOKEN','')
        return cls(origin,token) if origin or token else None

    def _read(self, path, limit, body=None):
        headers = {}
        if body is not None:
            headers = {'Authorization':'Bearer '+self.token,'Content-Type':'application/json'}
        try:
            with build_opener(NoRedirect).open(Request(self.origin+path,body,headers),timeout=5) as response:
                raw = response.read(limit+1)
                if len(raw) > limit:
                    return None
                return raw,response.headers.get_content_type()
        except (HTTPError,URLError,OSError):
            return None

    def projection(self, v2_id, match):
        if not isinstance(v2_id,str) or not UUID.fullmatch(v2_id):
            return None
        result = self._read('/api/v1/atlas/pilot-parcel',1_048_576,
                            json.dumps({**match,'listingId':v2_id}).encode())
        if not result or result[1] != 'application/json':
            return None
        try:
            parsed=json.loads(result[0])
            return parsed if parsed.get('verifiedParcel') is True and parsed.get('boundaries') else None
        except (ValueError,AttributeError):
            return None

    def document(self,v2_id,document_id):
        if not UUID.fullmatch(v2_id) or not UUID.fullmatch(document_id):
            return None
        result=self._read(f'/api/v1/listings/{v2_id}/documents/{document_id}',MAX_RENDITION)
        return result if result and result[1] in MIME_TYPES else None
