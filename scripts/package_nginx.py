"""Build the server-only Nginx distribution without altering previous release archives."""
from pathlib import Path
import hashlib
import re
import zipfile

root = Path(__file__).resolve().parents[1]
version = re.search(r'versionName = "([^"]+)"', (root / 'app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
dest = root / f'delivery/Peygir-{version}-nginx-vps.zip'
excluded = {'.git', '.agents', '.device-private', '.signing', 'node_modules', '__pycache__', '.wrangler'}
files = list((root / 'vps').rglob('*')) + list((root / 'cloudflare/src').glob('*.mjs')) + list((root / 'cloudflare/migrations').glob('*.sql'))
files += [root / '.dockerignore', root / '.gitignore', root / '.gitattributes',
          root / 'app/src/main/res/font/vazirmatn_regular.ttf', root / 'app/src/main/assets/licenses/Vazirmatn-OFL.txt']
with zipfile.ZipFile(dest, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for file in sorted(files):
        rel = file.relative_to(root)
        if file.is_file() and not any(p in excluded for p in rel.parts) and not file.name.startswith(('.env', '.dev.vars')) and file.suffix not in {'.log', '.pem', '.key', '.jks', '.keystore'}:
            z.write(file, Path('Peygir-VPS') / rel)
    z.write(root / 'vps/NGINX-fa.md', 'Peygir-VPS/START-HERE-fa.md')
    z.write(root / 'vps/NGINX-fa.md', 'Peygir-VPS/NGINX-fa.md')
    z.write(root / 'vps/README-fa.md', 'Peygir-VPS/README-fa.md')
with zipfile.ZipFile(dest) as z:
    assert z.testzip() is None
    assert 'Peygir-VPS/vps/compose.nginx.yaml' in z.namelist()
    assert not any(p in excluded for n in z.namelist() for p in Path(n).parts)
checksum = root / f'delivery/SHA256SUMS-{version}-nginx.txt'
checksum.write_text(hashlib.sha256(dest.read_bytes()).hexdigest() + '  ' + dest.name + '\n', encoding='utf-8')
print(dest.name, dest.stat().st_size)
