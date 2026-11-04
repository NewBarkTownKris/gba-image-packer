#!/usr/bin/env python3
"""Build and sign a standalone Android APK using Rust, the Android SDK and NDK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT'))
p.add_argument('--ndk', type=Path, default=os.environ.get('ANDROID_NDK_HOME') or os.environ.get('ANDROID_NDK_ROOT'))
p.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
p.add_argument('--test', action='store_true', help='Also build the emulator instrumentation APK')
args = p.parse_args()
if not args.sdk or not args.ndk:
    p.error('Set ANDROID_HOME and ANDROID_NDK_HOME, or supply --sdk and --ndk')
sdk, ndk = args.sdk.resolve(), args.ndk.resolve()
java = args.java_home / 'bin' if args.java_home else None

def tool(name):
    return str(java / name) if java else name

def run(*command, **kwargs):
    subprocess.run([str(v) for v in command], check=True, **kwargs)

build_tools = sdk / 'build-tools' / '36.0.0'
android_jar = sdk / 'platforms' / 'android-36' / 'android.jar'
ndk_bin = ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
if not android_jar.is_file() or not (build_tools / 'aapt2').is_file() or not ndk_bin.is_dir():
    p.error('Requires Linux, Android platform 36, build-tools 36.0.0 and the Linux NDK')
build = ROOT / 'target/android-build'
dist = ROOT / 'dist/android'
for directory in [build, dist]: directory.mkdir(parents=True, exist_ok=True)
assets = build / 'assets'
assets.mkdir(exist_ok=True)
shutil.copy2(ROOT / 'examples/example.gba', assets / 'example.gba')
manifest = ROOT / 'android/rust/Cargo.toml'
run('cargo', 'fetch', '--locked', '--manifest-path', manifest)
metadata = json.loads(subprocess.check_output(['cargo', 'metadata', '--locked', '--offline', '--format-version', '1', '--manifest-path', str(manifest)]))
licenses = [(ROOT / 'LICENSE').read_text()]
for package in sorted(metadata['packages'], key=lambda x: (x['name'], x['version'])):
    if package['source'] is None: continue
    licenses.append(f"\n\n{package['name']} {package['version']} — {package.get('license') or 'See below'}\n")
    for path in sorted(Path(package['manifest_path']).parent.iterdir()):
        if path.name.lower().startswith(('license', 'licence', 'copying', 'notice')):
            files = [path] if path.is_file() else sorted(p for p in path.rglob('*') if p.is_file())
            for item in files: licenses.append(item.read_text(errors='replace'))
licenses.append((ROOT / 'assets/DejaVu-LICENSE.txt').read_text())
(assets / 'licenses.txt').write_text('\n'.join(licenses), encoding='utf-8')

libraries = []
for target, abi, clang in [
    ('aarch64-linux-android', 'arm64-v8a', 'aarch64-linux-android26-clang'),
    ('x86_64-linux-android', 'x86_64', 'x86_64-linux-android26-clang'),
]:
    env = dict(os.environ)
    env['CARGO_TARGET_' + target.upper().replace('-', '_') + '_LINKER'] = str(ndk_bin / clang)
    env['RUSTFLAGS'] = '-C link-arg=-Wl,-z,max-page-size=16384'
    run('cargo', 'build', '--release', '--locked', '--manifest-path', manifest, '--target', target, env=env)
    libraries.append((ROOT / f'android/rust/target/{target}/release/libgba_packer.so', f'lib/{abi}/libgba_packer.so'))

signing = ROOT / 'android/.signing'
signing.mkdir(exist_ok=True, mode=0o700)
keystore = Path(os.environ.get('ANDROID_KEYSTORE', str(signing / 'release.p12'))).resolve()
password = Path(os.environ.get('ANDROID_KEYSTORE_PASSWORD_FILE', str(signing / 'password'))).resolve()
if not keystore.exists():
    if 'ANDROID_KEYSTORE' in os.environ: p.error('The configured signing keystore does not exist')
    password.write_text(secrets.token_urlsafe(32), encoding='utf-8'); password.chmod(0o600)
    run(tool('keytool'), '-genkeypair', '-keystore', keystore, '-storetype', 'PKCS12', '-alias', 'gba-packer',
        '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=GBA Image Packer',
        '-storepass:file', password, '-keypass:file', password)
    keystore.chmod(0o600)

def package(manifest_file, sources, name, extra_assets=None, native_libraries=()):
    folder = build / name
    if folder.exists(): shutil.rmtree(folder)
    classes, generated, dex = folder / 'classes', folder / 'generated', folder / 'dex'
    for d in [classes, generated, dex]: d.mkdir(parents=True)
    resources = folder / 'resources.zip'
    run(build_tools / 'aapt2', 'compile', '--dir', ROOT / 'android/res', '-o', resources)
    unsigned = folder / 'unsigned.apk'
    command = [build_tools / 'aapt2', 'link', '-o', unsigned, '-I', android_jar,
        '--manifest', manifest_file, '--java', generated, resources]
    if extra_assets: command.extend(['-A', extra_assets])
    run(*command)
    java_sources = list(sources) + list(generated.rglob('*.java'))
    run(tool('javac'), '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-classpath', android_jar,
        '-d', classes, *java_sources)
    env = dict(os.environ)
    if args.java_home:
        env['JAVA_HOME'] = str(args.java_home)
        env['PATH'] = str(args.java_home / 'bin') + os.pathsep + env.get('PATH', '')
    run(build_tools / 'd8', '--release', '--min-api', '26', '--lib', android_jar, '--output', dex,
        *sorted(classes.rglob('*.class')), env=env)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED, strict_timestamps=False) as apk:
        for path in sorted(dex.glob('*.dex')): apk.write(path, path.name)
        for path, dest in native_libraries: apk.write(path, dest)
    aligned = folder / 'aligned.apk'
    run(build_tools / 'zipalign', '-f', '-P', '16', '4', unsigned, aligned)
    output = dist / (name + '.apk')
    run(build_tools / 'apksigner', 'sign', '--ks', keystore, '--ks-key-alias', 'gba-packer',
        '--ks-pass', 'file:' + str(password), '--out', output, aligned, env=env)
    run(build_tools / 'apksigner', 'verify', '--verbose', output, env=env)
    run(build_tools / 'zipalign', '-c', '-P', '16', '4', output)
    return output

output = package(ROOT / 'android/AndroidManifest.xml', (ROOT / 'android/java').rglob('*.java'),
    'gba-image-packer-1.0.0-android', assets, libraries)
if args.test:
    package(ROOT / 'android/test/AndroidManifest.xml', (ROOT / 'android/test/java').rglob('*.java'), 'android-instrumentation')
checksum = hashlib.sha256(output.read_bytes()).hexdigest()
(output.with_suffix('.apk.sha256')).write_text(f'{checksum}  {output.name}\n', encoding='utf-8')
print(f'APK: {output}')
print(f'SHA256: {checksum}')
print(f'Keep your signing keystore and password file private and backed up: {signing}')
