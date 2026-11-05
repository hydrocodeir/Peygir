"""Validate release metadata, sign an APK without printing secrets, and check its package."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
# Existing installed Peygir certificate. Changing this requires a deliberate signing migration.
CERTIFICATE = '81573fa6a77199b83ef74beda1aa664d20333c0744ac975853c0787b55de81d5'


def validate_release(root, tag):
    if not re.fullmatch(r'v\d+\.\d+\.\d+', tag):
        raise ValueError('Release tags must have the form v4.10.1')
    gradle = (root / 'app/build.gradle.kts').read_text(encoding='utf-8')
    version = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', gradle, re.M).group(1)
    code = int(re.search(r'^\s*versionCode\s*=\s*(\d+)', gradle, re.M).group(1))
    if tag != 'v' + version or not 1 <= code <= 2147483647:
        raise ValueError('Tag and app versionName must match, with a valid versionCode')
    notes = root / f'release-notes/android/{version}-fa.txt'
    text = notes.read_text(encoding='utf-8')
    if not text.strip() or len(text) > 12000:
        raise ValueError('Release notes must contain 1..12000 characters')
    return {'version': version, 'code': str(code), 'notes': notes.relative_to(root).as_posix()}


def validate_package(directory, version=None):
    metadata = directory / 'latest.json'
    if metadata.stat().st_size > 65536:
        raise ValueError('Manifest is too large')
    manifest = json.loads(metadata.read_text(encoding='utf-8'))
    code, size = manifest.get('versionCode'), manifest.get('size')
    if type(code) is not int or not 1 <= code <= 2147483647 or type(size) is not int or not 1 <= size <= 1073741824:
        raise ValueError('Invalid version code or APK size')
    if manifest.get('packageName') != 'ir.peygir.app' or manifest.get('apk') != f'/updates/android/peygir-{code}.apk':
        raise ValueError('Invalid application or APK path')
    if not isinstance(manifest.get('versionName'), str) or not re.fullmatch(r'\d+\.\d+\.\d+', manifest['versionName']):
        raise ValueError('Invalid version name')
    if version is not None and manifest['versionName'] != version:
        raise ValueError('Built APK version does not match release tag')
    if type(manifest.get('minSdk')) is not int or not 26 <= manifest['minSdk'] <= 100:
        raise ValueError('Invalid minimum SDK')
    if not isinstance(manifest.get('notes'), str) or not manifest['notes'].strip() or len(manifest['notes']) > 12000:
        raise ValueError('Invalid release notes')
    apk = directory / f'peygir-{code}.apk'
    if apk.is_symlink() or not apk.is_file() or apk.stat().st_size != size:
        raise ValueError('APK is missing, incomplete or a symlink')
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    if manifest.get('sha256') != digest:
        raise ValueError('APK checksum mismatch')
    return manifest


def certificate_matches(output):
    values = re.findall(r'^Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]+)\s*$', output, re.M)
    return len(values) == 1 and values[0].lower() == CERTIFICATE


def sign_apk(unsigned, output, tools):
    names = ['ANDROID_KEYSTORE_BASE64', 'ANDROID_KEYSTORE_PASSWORD', 'ANDROID_KEY_ALIAS', 'ANDROID_KEY_PASSWORD']
    if any(not os.environ.get(name) for name in names):
        raise ValueError('All four ANDROID signing secrets must be configured')
    key = base64.b64decode(''.join(os.environ['ANDROID_KEYSTORE_BASE64'].split()), validate=True)
    with tempfile.TemporaryDirectory(prefix='peygir-sign-', dir=os.environ.get('RUNNER_TEMP')) as directory:
        keystore = Path(directory) / 'signing.keystore'
        keystore.write_bytes(key)
        keystore.chmod(0o600)
        output.parent.mkdir(parents=True, exist_ok=True)
        try:
            subprocess.run([str(tools / 'apksigner'), 'sign', '--ks', str(keystore),
                            '--ks-key-alias', os.environ['ANDROID_KEY_ALIAS'],
                            '--ks-pass', 'env:ANDROID_KEYSTORE_PASSWORD', '--key-pass', 'env:ANDROID_KEY_PASSWORD',
                            '--out', str(output), str(unsigned)], check=True)
            result = subprocess.run([str(tools / 'apksigner'), 'verify', '--print-certs', str(output)],
                                    check=True, capture_output=True, text=True)
            if not certificate_matches(result.stdout):
                raise ValueError('Signing certificate differs from the existing installed Peygir APK')
            subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(output)], check=True)
        except Exception:
            output.unlink(missing_ok=True)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    validate = commands.add_parser('validate')
    validate.add_argument('--tag', required=True)
    validate.add_argument('--github-output', type=Path)
    sign = commands.add_parser('sign')
    sign.add_argument('--unsigned', type=Path, required=True)
    sign.add_argument('--output', type=Path, required=True)
    sign.add_argument('--tools', type=Path, required=True)
    package = commands.add_parser('package')
    package.add_argument('--directory', type=Path, required=True)
    package.add_argument('--version')
    args = parser.parse_args()
    if args.command == 'validate':
        value = validate_release(ROOT, args.tag)
        if args.github_output:
            with args.github_output.open('a', encoding='utf-8') as stream:
                for name, text in value.items():
                    stream.write(f'{name}={text}\n')
        print(json.dumps(value))
    elif args.command == 'sign':
        sign_apk(args.unsigned.resolve(), args.output.resolve(), args.tools.resolve())
        print('APK signature and 16 KB ZIP alignment verified against the existing Peygir certificate')
    else:
        value = validate_package(args.directory.resolve(), args.version)
        print(f'Package verified: {value["versionName"]} ({value["versionCode"]})')


if __name__ == '__main__':
    main()
