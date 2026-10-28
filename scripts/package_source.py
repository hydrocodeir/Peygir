from pathlib import Path
import zipfile, hashlib, re, argparse

parser=argparse.ArgumentParser()
parser.add_argument("--app-only",action="store_true",help="Package an Android-only update; keep the existing server distribution.")
args=parser.parse_args()

root=Path(__file__).resolve().parents[1]
version=re.search(r'versionName = "([^"]+)"',(root/'app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
dest=root/f'delivery/Peygir-{version}-source.zip'
excluded={'.agents','.git','.gradle','.kotlin','.local-build','.signing','.device-private','build','delivery','__pycache__','node_modules','.wrangler'}
with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for file in sorted(root.rglob('*')):
        rel=file.relative_to(root)
        if file.is_file() and not any(p in excluded for p in rel.parts) and file.name!='local.properties' and not file.name.startswith(('.env','.dev.vars')) and file.suffix not in {'.log','.jks','.keystore'}:
            z.write(file,Path('Peygir')/rel)
with zipfile.ZipFile(dest) as z:
    assert z.testzip() is None
    assert not any('.signing' in n or '.device-private' in n or n.endswith(('.jks','.keystore')) for n in z.namelist())
server=None
if not args.app_only:
    server=root/f'delivery/Peygir-{version}-vps.zip'
    files=list((root/'vps').rglob('*'))+list((root/'cloudflare/src').glob('*.mjs'))+list((root/'cloudflare/migrations').glob('*.sql'))
    files += [root/'.dockerignore',root/'.gitignore',root/'app/src/main/res/font/vazirmatn_regular.ttf',root/'app/src/main/assets/licenses/Vazirmatn-OFL.txt']
    with zipfile.ZipFile(server,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for f in sorted(files):
            rel=f.relative_to(root)
            if f.is_file() and not any(p in excluded for p in rel.parts) and not f.name.startswith(('.env','.dev.vars')) and f.suffix not in {'.log','.jks','.keystore'}:
                z.write(f,Path('Peygir-VPS')/rel)
        z.write(root/'vps/README-fa.md','Peygir-VPS/README-fa.md')
    with zipfile.ZipFile(server) as z:
        assert z.testzip() is None
apk=root/f'delivery/Peygir-{version}-debug.apk'
(root/f'delivery/SHA256SUMS-{version}.txt').write_text('\n'.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name for f in [apk,dest]+([server] if server else []))+'\n',encoding='utf-8')
print(dest, dest.stat().st_size)
if server: print(server, server.stat().st_size)
