#!/usr/bin/env python3
"""Independently check the legacy self-test cartridge structure using Python's standard library."""
import argparse,hashlib,json,struct
from pathlib import Path
root=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('rom',type=Path);a=p.parse_args()
b=a.rom.read_bytes();firmware=(root/'assets/viewer.bin').read_bytes();base=(len(firmware)+3)&~3
assert b[192:len(firmware)]==firmware[192:]
assert len(b)>=256*1024 and len(b)<=32*1024*1024 and len(b)&(len(b)-1)==0
assert b[178]==0x96 and sum(b[160:190])%256==0xe7
assert b[160:172].rstrip()==b'XP SELF TEST'
magic,count,interval,reserved=struct.unpack_from('<4I',b,base)
assert (magic,count,interval,reserved)==(0x50494353,3,5,0)
expected_names=[b'sample-1.png',b'sample-2.jpg',b'sample-3.bmp']
frames=[]
for i,name in enumerate(expected_names):
    entry=base+16+i*36;offset=struct.unpack_from('<I',b,entry)[0]
    assert offset==16+3*36+i*76800
    assert b[entry+4:entry+36].split(b'\0')[0]==name
    frame=b[base+offset:base+offset+76800];assert len(frame)==76800
    assert all(value<32768 for value in struct.unpack('<38400H',frame))
    frames.append(hashlib.sha256(frame).hexdigest())
assert len(set(frames))==3
position=base+16+3*36+3*76800
for i in range(3):
    length=struct.unpack_from('<I',b,position)[0];position+=4;jpeg=b[position:position+length];position+=length
    assert jpeg[:2]==b'\xff\xd8' and jpeg[-2:]==b'\xff\xd9'
    found=False;cursor=2
    while cursor<len(jpeg)-2:
        assert jpeg[cursor]==255;marker=jpeg[cursor+1];cursor+=2
        if marker in [0xd8,0xd9]:continue
        length=struct.unpack_from('>H',jpeg,cursor)[0]
        if marker==0xc0:
            precision,height,width=struct.unpack_from('>BHH',jpeg,cursor+2)
            assert (precision,width,height)==(8,240,160);found=True;break
        if marker==0xda:break
        cursor+=length
    assert found,'Expected baseline 240x160 JPEG'
assert all(value==255 for value in b[position:])
print(json.dumps({'passed':True,'images':count,'interval':interval,'bytes':len(b),'frame_sha256':frames}))
