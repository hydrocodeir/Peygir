from pathlib import Path
import zipfile, hashlib

root=Path(__file__).resolve().parents[1]
dest=root/'delivery/Peygir-2.0.0-source.zip'
excluded={'.git','.gradle','.kotlin','.local-build','.signing','.device-private','build','delivery','__pycache__','node_modules','.wrangler'}
with zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
    for file in sorted(root.rglob('*')):
        rel=file.relative_to(root)
        if file.is_file() and not any(p in excluded for p in rel.parts) and file.name!='local.properties' and not file.name.startswith(('.env','.dev.vars')) and file.suffix not in {'.log','.jks','.keystore'}:
            z.write(file,Path('Peygir')/rel)
with zipfile.ZipFile(dest) as z:
    assert z.testzip() is None
    assert not any('.signing' in n or '.device-private' in n or n.endswith(('.jks','.keystore')) for n in z.namelist())
apk=root/'delivery/Peygir-2.0.0-debug.apk'
(root/'delivery/SHA256SUMS-2.0.0.txt').write_text('\n'.join(hashlib.sha256(f.read_bytes()).hexdigest()+'  '+f.name for f in [apk,dest])+'\n',encoding='utf-8')
print(dest, dest.stat().st_size)
