"""Package only public server build inputs and deployment tools; exclude private files and links."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile

ROOT=Path(__file__).resolve().parents[2]
EXCLUDED={'.git','.agents','.device-private','.signing','node_modules','__pycache__','test','test-results','playwright-report'}


def server_version(root):
    version=json.loads((root/'vps/package.json').read_text(encoding='utf-8'))['version']
    if not re.fullmatch(r'\d+\.\d+\.\d+',version):
        raise ValueError('Invalid server version')
    return version


def build_package(root,destination,commit,tag=None):
    if not re.fullmatch(r'[a-f0-9]{40}',commit):
        raise ValueError('Expected a full Git commit SHA')
    version=server_version(root)
    if tag is not None and (not re.fullmatch(r'v\d+\.\d+\.\d+',tag) or tuple(map(int,tag[1:].split('.')))<tuple(map(int,version.split('.')))):
        raise ValueError('Release tag cannot be older than the server version')
    paths=list((root/'vps').rglob('*'))+list((root/'cloudflare/src').glob('*.mjs'))+list((root/'cloudflare/migrations').glob('*.sql'))
    paths += [root/'.dockerignore',root/'.gitattributes',root/'.gitignore',
              root/'app/src/main/res/font/vazirmatn_regular.ttf',root/'app/src/main/assets/licenses/Vazirmatn-OFL.txt',
              root/'scripts/ci/remote_server_deploy.sh']
    entries={}
    for path in sorted(set(paths)):
        rel=path.relative_to(root)
        if any(part in EXCLUDED for part in rel.parts) or path.name.startswith(('.env','.dev.vars')) or path.suffix in {'.key','.pem','.jks','.keystore','.log'}:
            continue
        if path.is_symlink():
            raise ValueError('Symlinks are not allowed in server packages')
        if path.is_file():
            name=rel.as_posix()
            entries[name]=path.read_bytes()
    required=['vps/Dockerfile','vps/package.json','vps/package-lock.json','vps/src/app.mjs',
              'vps/scripts/verify-upgrade.mjs','vps/scripts/peygir-nginx.sh','scripts/ci/remote_server_deploy.sh']
    if any(name not in entries for name in required):
        raise ValueError('Required server build input is missing')
    manifest={'format':1,'version':version,'commit':commit,'files':{name:hashlib.sha256(data).hexdigest() for name,data in entries.items()}}
    manifest['digest']=hashlib.sha256(json.dumps(manifest['files'],sort_keys=True,separators=(',',':')).encode()).hexdigest()
    destination.parent.mkdir(parents=True,exist_ok=True)
    with tarfile.open(destination,'w:gz') as archive:
        for name,data in [*entries.items(),('deployment.json',(json.dumps(manifest,indent=2)+'\n').encode())]:
            info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o644;info.mtime=0
            archive.addfile(info,io.BytesIO(data))
    return manifest


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',required=True,type=Path)
    parser.add_argument('--commit',required=True)
    parser.add_argument('--tag')
    args=parser.parse_args()
    value=build_package(ROOT,args.output.resolve(),args.commit,args.tag)
    print(f'Server package ready: {value["version"]}, {len(value["files"])} files')
