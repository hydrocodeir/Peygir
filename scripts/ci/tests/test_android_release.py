import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


def module(name):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / (name + '.py'))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


release = module('android_release')
public = module('verify_public_update')


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def package(self):
        apk = b'synthetic signed APK content'
        value = dict(packageName='ir.peygir.app', versionCode=40, versionName='4.10.1', minSdk=26,
                     size=len(apk), sha256=hashlib.sha256(apk).hexdigest(), apk='/updates/android/peygir-40.apk', notes='Release notes')
        (self.root / 'peygir-40.apk').write_bytes(apk)
        (self.root / 'latest.json').write_text(json.dumps(value), encoding='utf-8')
        return value, apk

    def test_version_tag_and_notes_must_match(self):
        (self.root / 'app').mkdir()
        (self.root / 'app/build.gradle.kts').write_text('versionCode = 40\nversionName = "4.10.1"\n')
        notes = self.root / 'release-notes/android/4.10.1-fa.txt'
        notes.parent.mkdir(parents=True)
        notes.write_text('Notes', encoding='utf-8')
        self.assertEqual(release.validate_release(self.root, 'v4.10.1')['code'], '40')
        for tag in ['main', 'v4.10', 'v4.10.0', 'v4.10.1;echo invalid']:
            with self.assertRaises(ValueError):
                release.validate_release(self.root, tag)
        notes.write_text('', encoding='utf-8')
        with self.assertRaises(ValueError):
            release.validate_release(self.root, 'v4.10.1')

    def test_package_rejects_tampering_wrong_version_and_unsafe_paths(self):
        manifest, apk = self.package()
        self.assertEqual(release.validate_package(self.root, '4.10.1'), manifest)
        with self.assertRaises(ValueError):
            release.validate_package(self.root, '4.10.2')
        (self.root / 'peygir-40.apk').write_bytes(b'x' * len(apk))
        with self.assertRaisesRegex(ValueError, 'checksum'):
            release.validate_package(self.root)
        (self.root / 'peygir-40.apk').write_bytes(apk)
        for change in [dict(apk='/secret.apk'), dict(versionCode=True), dict(size=0), dict(minSdk=1), dict(packageName='other.app')]:
            (self.root / 'latest.json').write_text(json.dumps({**manifest, **change}), encoding='utf-8')
            with self.assertRaises(ValueError):
                release.validate_package(self.root)

    def test_only_existing_single_signer_is_accepted(self):
        correct = 'Signer #1 certificate SHA-256 digest: ' + release.CERTIFICATE + '\n'
        self.assertTrue(release.certificate_matches(correct))
        self.assertFalse(release.certificate_matches(correct.replace(release.CERTIFICATE, 'a' * 64)))
        self.assertFalse(release.certificate_matches(correct + correct.replace('#1', '#2')))

    def test_signing_passwords_use_env_and_temporary_key_is_removed(self):
        output = self.root / 'signed.apk'
        temporary_keys = []
        def run(args, **kwargs):
            if 'sign' in args:
                key = Path(args[args.index('--ks') + 1]);temporary_keys.append(key)
                self.assertEqual(key.read_bytes(), b'synthetic keystore')
                self.assertNotIn('synthetic-password', args)
                output.write_bytes(b'signed')
                self.assertIn('env:ANDROID_KEYSTORE_PASSWORD', args)
            return subprocess.CompletedProcess(args, 0, stdout='Signer #1 certificate SHA-256 digest: ' + release.CERTIFICATE)
        env = dict(ANDROID_KEYSTORE_BASE64=base64.b64encode(b'synthetic keystore').decode(),
                   ANDROID_KEYSTORE_PASSWORD='synthetic-password', ANDROID_KEY_ALIAS='synthetic-alias',
                   ANDROID_KEY_PASSWORD='synthetic-password', RUNNER_TEMP=str(self.root))
        with patch.dict(os.environ, env), patch.object(release.subprocess, 'run', side_effect=run):
            release.sign_apk(self.root / 'unsigned.apk', output, self.root)
        self.assertTrue(output.is_file())
        self.assertTrue(all(not path.exists() for path in temporary_keys))

    def test_signing_failure_never_leaves_a_distributable_apk(self):
        output = self.root / 'signed.apk'
        output.write_bytes(b'partial')
        env = dict(ANDROID_KEYSTORE_BASE64=base64.b64encode(b'fake').decode(), ANDROID_KEYSTORE_PASSWORD='x',
                   ANDROID_KEY_ALIAS='alias', ANDROID_KEY_PASSWORD='x', RUNNER_TEMP=str(self.root))
        def run(args, **kwargs):
            return subprocess.CompletedProcess(args, 0, stdout='Signer #1 certificate SHA-256 digest: ' + '0' * 64)
        with patch.dict(os.environ, env), patch.object(release.subprocess, 'run', side_effect=run):
            with self.assertRaises(ValueError):
                release.sign_apk(self.root / 'unsigned.apk', output, self.root)
        self.assertFalse(output.exists())

    def test_public_verification_checks_manifest_and_streamed_apk(self):
        manifest, apk = self.package()
        class Opener:
            def open(self, url, **kwargs):
                return io.BytesIO(json.dumps(manifest).encode() if url.endswith('.json') else apk)
        with patch.object(public, 'build_opener', return_value=Opener()):
            public.verify(self.root, 'https://peygir.test')
        with patch.object(public, 'build_opener', return_value=Opener()):
            apk = b'corrupted bytes'
            with self.assertRaises(ValueError):
                public.verify(self.root, 'https://peygir.test')

    def test_public_verification_rejects_untrusted_url_components(self):
        self.package()
        for origin in ['http://peygir.test', 'https://user:pass@peygir.test', 'https://peygir.test/app', 'https://peygir.test?secret=x']:
            with self.assertRaises(ValueError):
                public.verify(self.root, origin)
        self.assertIsNone(public.NoRedirect().redirect_request(None, None, 302, '', {}, 'https://other.test'))


if __name__ == '__main__':
    unittest.main()
