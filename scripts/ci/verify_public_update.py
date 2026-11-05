"""Verify the public manifest and streamed APK after publication, without accepting redirects."""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import build_opener, HTTPRedirectHandler


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def verify(directory, origin):
    url = urlsplit(origin)
    if url.scheme != 'https' or not url.hostname or url.username or url.password or url.query or url.fragment or url.path not in ('', '/'):
        raise ValueError('UPDATE_ORIGIN must be an HTTPS origin')
    expected = json.loads((directory / 'latest.json').read_text(encoding='utf-8'))
    opener = build_opener(NoRedirect())
    with opener.open(origin.rstrip('/') + '/updates/android/latest.json', timeout=30) as response:
        raw = response.read(65537)
        if len(raw) > 65536:
            raise ValueError('Public manifest is too large')
        actual = json.loads(raw)
    if actual != expected:
        raise ValueError('Public manifest differs from the package just published')
    digest, size = hashlib.sha256(), 0
    with opener.open(origin.rstrip('/') + expected['apk'], timeout=60) as response:
        while chunk := response.read(65536):
            size += len(chunk)
            if size > expected['size']:
                raise ValueError('Public APK is larger than expected')
            digest.update(chunk)
    if size != expected['size'] or digest.hexdigest() != expected['sha256']:
        raise ValueError('Public APK checksum or size mismatch')
    print(f'Public manifest and APK verified: {expected["versionName"]} ({expected["versionCode"]})')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--origin', required=True)
    args = parser.parse_args()
    verify(args.directory, args.origin)
