#!/usr/bin/env python3
"""Package a native build, example cartridge, documentation and dependency licenses."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--target', required=True, choices=[
    'x86_64-unknown-linux-gnu', 'aarch64-apple-darwin', 'x86_64-pc-windows-msvc'])
parser.add_argument('--binary', required=True, type=Path)
args = parser.parse_args()
os.chdir(ROOT)
metadata = json.loads(subprocess.check_output([
    'cargo', 'metadata', '--locked', '--offline', '--format-version', '1']))
version = next(p['version'] for p in metadata['packages'] if p['name'] == 'gba-image-packer')
name = f'gba-image-packer-{version}-{args.target}'
dist = ROOT / 'dist'
dist.mkdir(exist_ok=True)
stage = dist / name
if stage.exists():
    shutil.rmtree(stage)
stage.mkdir()
for doc in ['README.md', 'CREDITS.md', 'LICENSE']:
    shutil.copy2(ROOT / doc, stage / doc)
shutil.copy2(ROOT / 'examples/example.gba', stage / 'example.gba')
licenses = stage / 'licenses'
licenses.mkdir()
for font in ['Noto-LICENSE.txt', 'DejaVu-LICENSE.txt']:
    shutil.copy2(ROOT / 'assets' / font, licenses / font)
notices = []
for package in sorted(metadata['packages'], key=lambda p: (p['name'], p['version'])):
    if package['source'] is None:
        continue
    dep_name = f"{package['name']}-{package['version']}"
    notices.append(f"{dep_name}: {package.get('license') or 'see license files'}")
    source = Path(package['manifest_path']).parent
    for path in source.iterdir():
        if path.name.lower().startswith(('license', 'licence', 'copying', 'notice')):
            dest = licenses / dep_name / path.name
            dest.parent.mkdir(exist_ok=True)
            if path.is_dir():
                shutil.copytree(path, dest)
            else:
                shutil.copy2(path, dest)
(licenses / 'DEPENDENCIES.txt').write_text('\n'.join(notices) + '\n', encoding='utf-8')
binary = args.binary.resolve()
if not binary.is_file():
    raise SystemExit(f'Binary missing: {binary}')
if args.target == 'aarch64-apple-darwin':
    app = stage / 'GBA Image Packer.app' / 'Contents'
    (app / 'MacOS').mkdir(parents=True)
    executable = app / 'MacOS' / 'gba-image-packer'
    shutil.copy2(binary, executable)
    executable.chmod(0o755)
    with (app / 'Info.plist').open('wb') as f:
        plistlib.dump({
            'CFBundleExecutable': 'gba-image-packer',
            'CFBundleIdentifier': 'io.github.NewBarkTownKris.gba-image-packer',
            'CFBundleName': 'GBA Image Packer',
            'CFBundlePackageType': 'APPL',
            'CFBundleShortVersionString': version,
            'CFBundleVersion': version,
            'LSMinimumSystemVersion': '11.0',
            'NSHighResolutionCapable': True,
        }, f)
    subprocess.run(['codesign', '--force', '--sign', '-', str(app.parent)], check=True)
    subprocess.run(['codesign', '--verify', '--deep', '--strict', str(app.parent)], check=True)
else:
    executable = stage / ('gba-image-packer.exe' if 'windows' in args.target else 'gba-image-packer')
    shutil.copy2(binary, executable)
    if 'linux' in args.target:
        executable.chmod(0o755)
manifest = {str(p.relative_to(stage)).replace(os.sep, '/'): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(stage.rglob('*')) if p.is_file()}
(stage / 'SHA256SUMS.txt').write_text(''.join(f'{value}  {key}\n' for key, value in manifest.items()), encoding='utf-8')
if 'linux' in args.target:
    archive = dist / (name + '.tar.gz')
    with tarfile.open(archive, 'w:gz') as f:
        f.add(stage, arcname=name)
else:
    archive = dist / (name + '.zip')
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as f:
        for path in sorted(stage.rglob('*')):
            if path.is_file():
                f.write(path, arcname=str(path.relative_to(dist)))
print(archive)
