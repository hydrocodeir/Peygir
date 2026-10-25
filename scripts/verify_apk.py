from pathlib import Path
import hashlib, json, struct, zipfile

root = Path(__file__).resolve().parents[1]
apk = root/'app/build/outputs/apk/debug/app-debug.apk'

def elf_alignment(data):
    assert data[:4] == b'\x7fELF'
    bits, endian = data[4], '<' if data[5] == 1 else '>'
    if bits == 2:
        phoff = struct.unpack_from(endian+'Q',data,32)[0]
        phsize, phnum = struct.unpack_from(endian+'HH',data,54)
        return [struct.unpack_from(endian+'Q',data,phoff+i*phsize+48)[0] for i in range(phnum) if struct.unpack_from(endian+'I',data,phoff+i*phsize)[0] == 1]
    phoff = struct.unpack_from(endian+'I',data,28)[0]
    phsize, phnum = struct.unpack_from(endian+'HH',data,42)
    return [struct.unpack_from(endian+'I',data,phoff+i*phsize+28)[0] for i in range(phnum) if struct.unpack_from(endian+'I',data,phoff+i*phsize)[0] == 1]

with zipfile.ZipFile(apk) as z:
    assert z.testzip() is None
    names=z.namelist()
    assert not any(b'peygirbuild/' in z.read(n) for n in names if n.endswith('.dex')), 'Local build helper must not be packaged in the Android app'
    required=['assets/tessdata/fas.traineddata','assets/tessdata/eng.traineddata','assets/vosk-model-small-fa-0.42.zip']
    assert all(n in names for n in required)
    assets={n:dict(bytes=len(z.read(n)),sha256=hashlib.sha256(z.read(n)).hexdigest()) for n in required}
    native={n:elf_alignment(z.read(n)) for n in names if n.endswith('.so')}
    for abi in ['arm64-v8a','armeabi-v7a','x86','x86_64']:
        for lib in ['libtesseract.so','libvosk.so','libjnidispatch.so']:
            assert f'lib/{abi}/{lib}' in names, f'Missing {abi}/{lib}'
    bad64={n:a for n,a in native.items() if ('arm64-v8a' in n or 'x86_64' in n) and min(a)<16384}
    result=dict(apkBytes=apk.stat().st_size,sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),localBuildHelperPackaged=False,bundledAssets=assets,nativeElfLoadAlignment=native,unaligned64bitLibraries=bad64)
    (root/'verification/apk-inspection.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
    print(json.dumps(dict(bytes=result['apkBytes'],sha256=result['sha256'],nativeLibraries=len(native),unaligned64bitLibraries=bad64),indent=2))
    assert not bad64, '64-bit native libraries need 16 KB ELF alignment'
