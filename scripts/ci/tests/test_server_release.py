import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('server_release',Path(__file__).resolve().parents[1]/'server_release.py')
release=importlib.util.module_from_spec(spec);spec.loader.exec_module(release)


class ServerPackageTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        files={'vps/Dockerfile':'FROM node:24','vps/package.json':json.dumps({'version':'4.11.0'}),
               'vps/package-lock.json':'{}','vps/src/app.mjs':'export const ok=true;',
               'vps/scripts/verify-upgrade.mjs':'export {};','vps/scripts/peygir-nginx.sh':'echo test',
               'scripts/ci/remote_server_deploy.sh':'echo deploy', '.dockerignore':'**',
               'cloudflare/src/worker.mjs':'export {};',
               '.device-private/vps/secret.json':'PRIVATE', 'vps/node_modules/private.txt':'PRIVATE',
               'vps/.env':'PRIVATE','vps/server.key':'PRIVATE','vps/test/synthetic.txt':'TEST ONLY'}
        for name,value in files.items():
            path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(value)

    def test_package_uses_allowlist_and_records_every_file_checksum(self):
        output=self.root/'package.tar.gz'
        manifest=release.build_package(self.root,output,'a'*40,'v4.11.0')
        with tarfile.open(output) as archive:
            names=archive.getnames()
            self.assertNotIn('vps/.env',names)
            self.assertNotIn('vps/server.key',names)
            self.assertNotIn('vps/node_modules/private.txt',names)
            self.assertNotIn('vps/test/synthetic.txt',names)
            self.assertFalse(any('.device-private' in name for name in names))
            for name,digest in manifest['files'].items():
                self.assertEqual(hashlib.sha256(archive.extractfile(name).read()).hexdigest(),digest)
            self.assertEqual(json.load(archive.extractfile('deployment.json')),manifest)

    def test_server_digest_ignores_android_only_changes_and_commit_identifier(self):
        one=release.build_package(self.root,self.root/'one.tgz','a'*40,'v4.11.0')
        (self.root/'app').mkdir();(self.root/'app/build.gradle.kts').write_text('Android-only change')
        two=release.build_package(self.root,self.root/'two.tgz','b'*40,'v4.11.1')
        self.assertEqual(one['digest'],two['digest'])
        (self.root/'vps/src/app.mjs').write_text('export const ok=false;')
        three=release.build_package(self.root,self.root/'three.tgz','c'*40,'v4.11.1')
        self.assertNotEqual(two['digest'],three['digest'])

    def test_invalid_tags_and_missing_runtime_inputs_are_rejected(self):
        for commit,tag in [('bad','v4.11.0'),('a'*40,'v4.10.0'),('a'*40,'main')]:
            with self.assertRaises(ValueError):release.build_package(self.root,self.root/'x.tgz',commit,tag)
        (self.root/'vps/Dockerfile').unlink()
        with self.assertRaises(ValueError):release.build_package(self.root,self.root/'x.tgz','a'*40)


if __name__=='__main__':unittest.main()
