#!/usr/bin/env python3
"""Run under xvfb-run to capture the legacy GUI with three actual source files loaded."""
import os, subprocess, time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
env=dict(os.environ,WINEPREFIX='/tmp/gba-xp-wine',WINEDEBUG='-all',WINEDLLOVERRIDES='mscoree,mshtml=')
files=[root/'target/wine-output'/name for name in ['sample-1.png','sample-2.jpg','sample-3.bmp']]
args=['wine',str(root/'dist/gba-image-packer-xp.exe')]+['Z:'+str(f).replace('/','\\') for f in files]
process=subprocess.Popen(args,env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
try:
    for attempt in range(100):
        found=subprocess.run(['xdotool','search','--name','GBA Image Packer - Windows XP edition'],capture_output=True,text=True)
        if found.returncode==0:
            wid=found.stdout.splitlines()[-1];break
        if process.poll() is not None:raise SystemExit('Application exited before creating its window')
        time.sleep(.1)
    else:raise SystemExit('GUI window did not appear')
    subprocess.run(["xdotool","windowmove",wid,"0","0"],check=True)
    time.sleep(.5)
    subprocess.run(['import','-window',wid,str(root/'target/gui.png')],check=True)
    subprocess.run(['xdotool','windowfocus',wid],check=True)
    subprocess.run(['xdotool','key','--window',wid,'alt+F4'],check=False,stderr=subprocess.DEVNULL)
    if process.wait(timeout=10)!=0:raise SystemExit("Application did not close cleanly")
    print(root/'target/gui.png')
finally:
    if process.poll() is None:process.terminate()
