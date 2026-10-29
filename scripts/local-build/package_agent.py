from pathlib import Path
import zipfile
import argparse
root=Path(__file__).resolve().parents[2]
parser=argparse.ArgumentParser()
parser.add_argument('--asm',type=Path,required=True)
asm=parser.parse_args().asm
out=root/'.local-build/gradle-udp-agent.jar'
with zipfile.ZipFile(root/'.local-build/gradle-udp-helper.jar','w',zipfile.ZIP_DEFLATED) as helper:
    helper.write(root/'.local-build/classes/peygirbuild/LocalUdpPort.class','peygirbuild/LocalUdpPort.class')
with zipfile.ZipFile(out,'w',zipfile.ZIP_DEFLATED) as result:
    result.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nPremain-Class: peygirbuild.GradleUdpAgent\n\n')
    for file in (root/'.local-build/classes').rglob('*.class'):
        result.write(file,file.relative_to(root/'.local-build/classes').as_posix())
    with zipfile.ZipFile(asm) as source:
        for name in source.namelist():
            if name.endswith('.class'):
                result.writestr(name,source.read(name))
print(out)
