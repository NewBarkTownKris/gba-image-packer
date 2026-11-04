#!/usr/bin/env python3
"""Build and package the native Windows XP alpha without any Windows system DLLs."""
import argparse,hashlib,shutil,subprocess,zipfile
from pathlib import Path
root=Path(__file__).resolve().parent
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--toolchain',required=True,type=Path);a=p.parse_args()
subprocess.run(['python3',str(root/'build.py'),'--toolchain',str(a.toolchain)],check=True)
name='gba-image-packer-1.0.0-alpha1-windows-xp-x86'
stage=root/'target'/name
if stage.exists():shutil.rmtree(stage)
stage.mkdir()
for source,dest in [(root/'dist/gba-image-packer-xp.exe','gba-image-packer-xp.exe'),(root.parent/'LICENSE','LICENSE'),(root.parent/'examples/example.gba','example.gba'),(root/'target/pe-audit.txt','PE-IMPORT-AUDIT.txt')]:shutil.copy2(source,stage/dest)
# XP Notepad recognizes UTF-8 reliably with a BOM; .txt needs no Markdown association.
(stage/'README.txt').write_text((root/'README.md').read_text(),encoding='utf-8-sig')
shutil.copytree(root/'licenses',stage/'licenses')
files=sorted(path for path in stage.rglob('*') if path.is_file())
(stage/'SHA256SUMS.txt').write_text(''.join(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+str(path.relative_to(stage)).replace('\\','/')+'\n' for path in files))
archive=root/'dist'/(name+'.zip')
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,strict_timestamps=False) as z:
    for path in sorted(stage.rglob('*')):
        if path.is_file():z.write(path,arcname=str(path.relative_to(stage.parent)))
assert not any(path.suffix.lower()=='.dll' for path in stage.rglob('*'))
(archive.with_suffix('.zip.sha256')).write_text(hashlib.sha256(archive.read_bytes()).hexdigest()+'  '+archive.name+'\n')
print(archive)
