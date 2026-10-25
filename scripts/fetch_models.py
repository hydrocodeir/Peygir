from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import urllib.request, hashlib, json

root = Path(__file__).resolve().parents[1]
files = {
 'app/libs/tesseract4android-4.9.0.aar': 'https://jitpack.io/cz/adaptech/tesseract4android/tesseract4android/4.9.0/tesseract4android-4.9.0.aar',
 'app/src/main/assets/tessdata/fas.traineddata': 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/fas.traineddata',
 'app/src/main/assets/tessdata/eng.traineddata': 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/eng.traineddata',
 'app/src/main/assets/vosk-model-small-fa-0.42.zip': 'https://alphacephei.com/vosk/models/vosk-model-small-fa-0.42.zip',
 'app/src/main/assets/licenses/Tesseract4Android-Apache-2.0.txt': 'https://raw.githubusercontent.com/adaptech-cz/Tesseract4Android/master/LICENSE',
 'app/src/main/assets/licenses/Tesseract-models-Apache-2.0.txt': 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/LICENSE',
 'app/src/main/assets/licenses/Vosk-Apache-2.0.txt': 'https://raw.githubusercontent.com/alphacep/vosk-api/master/COPYING',
 'app/src/main/assets/licenses/JNA-Apache-2.0.txt': 'https://raw.githubusercontent.com/java-native-access/jna/5.18.1/LICENSE',
 'app/src/main/assets/licenses/Leptonica-BSD.txt': 'https://raw.githubusercontent.com/DanBloomberg/leptonica/1.85.0/leptonica-license.txt',
 'app/src/main/assets/licenses/libpng-LICENSE.txt': 'https://raw.githubusercontent.com/pnggroup/libpng/v1.6.48/LICENSE',
}
def fetch(item):
    name, url = item
    dest = root / name
    if not dest.exists():
        print('Downloading ' + name, flush=True)
        dest.parent.mkdir(parents=True, exist_ok=True)
        req = urllib.request.Request(url, headers={'User-Agent':'Peygir-build/1.0'})
        with urllib.request.urlopen(req, timeout=180) as response:
            data = response.read()
        dest.write_bytes(data)
    data = dest.read_bytes()
    print(name + ': ' + str(len(data)), flush=True)
    return name, dict(url=url, bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=5) as pool:
        entries = dict(pool.map(fetch, files.items()))
    (root/'verification/model-sources.json').write_text(json.dumps(entries, indent=2), encoding='utf-8')
