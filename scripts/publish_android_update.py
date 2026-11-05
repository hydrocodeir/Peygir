"""Stage an APK and publish its manifest last. Upload the resulting files to /data/updates/android."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile


def publish(apk, output, aapt, notes):
    badging = subprocess.check_output([str(aapt), 'dump', 'badging', str(apk)], text=True, encoding='utf-8')
    package = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
    sdk = re.search(r"sdkVersion:'(\d+)'", badging)
    if not package or not sdk or package[1] != 'ir.peygir.app':
        raise ValueError('Expected a Peygir APK with valid metadata')
    code, version, min_sdk = int(package[2]), package[3], int(sdk[1])
    size = apk.stat().st_size
    if not (1 <= code <= 2147483647 and re.fullmatch(r'\d+\.\d+\.\d+', version) and 26 <= min_sdk <= 100 and 0 < size <= 1073741824 and len(notes) <= 12000):
        raise ValueError('Invalid APK metadata or release notes')
    with apk.open('rb') as stream:
        sha = hashlib.file_digest(stream, 'sha256').hexdigest()
    output.mkdir(parents=True, exist_ok=True)
    filename = f'peygir-{code}.apk'
    target = output / filename
    if target.exists():
        with target.open('rb') as stream:
            if hashlib.file_digest(stream, 'sha256').hexdigest() != sha:
                raise ValueError('This versionCode already has a different APK; increase versionCode')
    else:
        temporary = output / (filename + '.part')
        shutil.copyfile(apk, temporary)
        os.replace(temporary, target)
    manifest = dict(packageName=package[1], versionCode=code, versionName=version, minSdk=min_sdk,
                    size=size, sha256=sha, apk=f'/updates/android/{filename}', notes=notes)
    temporary = output / 'latest.json.part'
    temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.replace(temporary, output / 'latest.json')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--aapt', required=True, type=Path)
    parser.add_argument('--notes-file', required=True, type=Path)
    parser.add_argument('--zip', type=Path, help='Optional distribution ZIP (APK plus manifest)')
    args = parser.parse_args()
    result = publish(args.apk.resolve(), args.output.resolve(), args.aapt.resolve(), args.notes_file.read_text(encoding='utf-8'))
    if args.zip:
        args.zip.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(args.zip, 'w', zipfile.ZIP_DEFLATED, compresslevel=1) as archive:
            for name in [f'peygir-{result["versionCode"]}.apk', 'latest.json']:
                archive.write(args.output / name, name)
    print(json.dumps(result, ensure_ascii=True, indent=2))
