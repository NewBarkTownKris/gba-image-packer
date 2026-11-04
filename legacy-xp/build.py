#!/usr/bin/env python3
"""Build a native 32-bit Windows XP application with the XP-specific LLVM-MinGW toolchain."""
import argparse, hashlib, json, os, re, shutil, struct, subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parent
REPO=ROOT.parent
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--toolchain', type=Path, default=os.environ.get('XP_TOOLCHAIN'))
a=p.parse_args()
if not a.toolchain:p.error('Supply --toolchain /path/to/llvm-mingw-xp or set XP_TOOLCHAIN')
toolchain=a.toolchain.resolve()
compiler=toolchain/'bin/i686-w64-mingw32-clang++'
if not compiler.is_file():p.error('The toolchain must include i686-w64-mingw32-clang++')
target=ROOT/'target';dist=ROOT/'dist';target.mkdir(exist_ok=True);dist.mkdir(exist_ok=True)
viewer=(REPO/'assets/viewer.bin').read_bytes()
source=(REPO/'src/model.rs').read_text()
match=re.search(r'let logo: \[u8; 156\] = \[(.*?)\];',source,re.S)
if not match:raise SystemExit('Cannot locate standard GBA validation field')
logo=bytes(int(v,16) for v in re.findall(r'0x([0-9a-fA-F]+)',match.group(1)))
assert len(logo)==156
with (target/'viewer_blob.h').open('w') as f:
    for name,data in [('viewerBlob',viewer),('gbaLogo',logo)]:
        f.write('static const unsigned char '+name+'[] = {'+','.join(str(v) for v in data)+'};\n')
output=dist/'gba-image-packer-xp.exe'
command=[str(compiler),'-std=c++11','-Os','-Wall','-Wextra','-Wno-missing-field-initializers','-D_WIN32_WINNT=0x0501','-DWINVER=0x0501',
    '-march=i686','-mno-sse','-mno-sse2','-mfpmath=387','-fno-exceptions','-fno-rtti','-static','-municode','-mwindows',
    '-Wl,--major-os-version,5,--minor-os-version,1,--major-subsystem-version,5,--minor-subsystem-version,1',
    '-I'+str(target),str(ROOT/'src/main.cpp'),'-o',str(output),'-lgdiplus','-lcomdlg32','-lole32','-luuid','-lshell32','-lgdi32','-luser32','-lkernel32']
subprocess.run(command,check=True)
reader=toolchain/'bin/llvm-readobj'
report=subprocess.check_output([str(reader),'--file-headers','--coff-imports',str(output)],text=True)
report=re.sub(r'(?m)^File: .*$', 'File: gba-image-packer-xp.exe', report)
(target/'pe-audit.txt').write_text(report)
# Enforce the intended PE identity and explicit XP subsystem, not just a compiler flag.
b=output.read_bytes();offset=struct.unpack_from('<I',b,0x3c)[0];optional=offset+24
assert b[:2]==b'MZ' and struct.unpack_from('<H',b,offset+4)[0]==0x14c
assert struct.unpack_from('<H',b,optional)[0]==0x10b
assert struct.unpack_from('<HH',b,optional+48)==(5,1)
for forbidden in ['api-ms-win-','ucrtbase.dll','vcruntime','msvcp','libc++','libwinpthread']:
    assert forbidden not in report.lower(),forbidden
sha=hashlib.sha256(b).hexdigest()
(output.with_suffix('.exe.sha256')).write_text(sha+'  '+output.name+'\n')
print(output)
print('XP PE32/i386, subsystem 5.1, no UCRT/VC++ runtime imports; see target/pe-audit.txt')
