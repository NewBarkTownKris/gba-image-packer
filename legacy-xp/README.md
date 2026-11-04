# GBA Image Packer — Windows XP alpha

A separate native C++/Win32 edition for Windows XP. It uses Windows' built-in GDI+ codecs and native file dialogs, together with the project's existing ARM7TDMI cartridge firmware. The generated cartridge uses the same album structure and controls as the Rust and Android editions.

## Status and requirements

This is a working first alpha, **targeted at Windows XP SP3 32-bit**. The executable is PE32/i386 with OS and GUI subsystem versions 5.1. It imports only XP-era Windows system DLLs and uses the built-in `msvcrt.dll`; it does not require Rust, .NET, OpenGL, the Universal CRT, or a Visual C++ redistributable.

Use an SSE2-capable x86 processor (Pentium 4, Athlon 64 or newer), a 1024 × 768 or larger desktop, and preferably at least 512 MiB of RAM. The compiler's static runtime contains SSE2 instructions even though application source is compiled without SSE2. Earlier non-SSE2 CPUs are not supported by this initial binary. The initial window is sized to the available desktop work area and centered so the controls fit a normal 1024 × 768 XP desktop.

The app has been compiled, inspected and run in Wine configured as Windows XP. Its generated ROM passes the project's mGBA playback harness. **It has not yet been tested on an original Windows XP installation or XP virtual machine.** Wine verification and an import audit are evidence for compatibility, not a substitute for that final test.

## Use

1. Extract the Windows XP package and open `gba-image-packer-xp.exe`.
2. Choose **Add images** and select one or multiple JPEG, PNG or BMP files. You can also pass image paths on the command line.
3. Select an image in the list. Choose Fit, Stretch or Fill; choose Nearest, Bilinear or Bicubic resampling; set JPEG quality from 5 to 100; optionally rotate clockwise.
4. Click **Update preview**, or **Apply settings to all**. The left preview shows the source; the right shows the decoded JPEG reduced to the GBA's RGB555 colors.
5. Arrange images with **Move up/down**, remove an image or clear the collection.
6. Set the 12-character cartridge title and slideshow interval (1–60 seconds), then choose **Build GBA ROM** and a destination.

Original image files are read only. Cartridge creation uses a temporary file and replaces the requested destination only after writing succeeds. Existing destinations require confirmation in the save dialog. Albums support at most 400 images and the 32 MiB cartridge limit. Paths must fit the traditional Windows `MAX_PATH` limit; each input image is limited to 64 million pixels.

The package includes the same ready-to-play `example.gba` cartridge as the other editions. A compatible flashcart is needed for physical GBA hardware. Cartridge controls are shown when the ROM starts: A opens selection, D-pad selects, A views, Start starts the slideshow; L/R browse; A+L/R skip 10; B+L/R skip 100; Select shows help.

## Differences from the Rust edition

The XP edition uses GDI+ resizing and JPEG encoding. Its output has the same cartridge format and hardware color depth, but its resampling and JPEG pixels are **not bit-identical** to the Rust image library.

This alpha provides the complete image-to-cartridge pipeline, source/result previews and per-image settings listed above. It does not yet include brightness, contrast, gamma, sharpening, Lanczos filtering, project save/open, or desktop slideshow preview. The editable collection is held in memory and is lost when the app closes. Conversion is synchronous, so a large album can temporarily stop the interface responding.

## Build on Linux

Use the [XP-specific LLVM-MinGW toolchain](https://github.com/mon/llvm-mingw-xp), release `llvm-mingw-xp-22.1.7`, with its MSVCRT x86_64 Linux-hosted archive. An ordinary modern UCRT-based MinGW toolchain is not an equivalent substitute.

```sh
python3 legacy-xp/build.py --toolchain /path/to/llvm-mingw-xp-22.1.7-msvcrt-ubuntu-22.04-x86_64
```

The build embeds `assets/viewer.bin` and the standard boot validation field used by `src/model.rs`. No GBA cross compiler is needed. It writes the executable to `legacy-xp/dist/` and a PE header/import report to `legacy-xp/target/pe-audit.txt`. The build rejects the wrong CPU/subsystem and imports from UCRT, Visual C++ runtime DLLs, libc++ or winpthreads DLLs.

`python3 legacy-xp/package.py --toolchain /path/to/toolchain` builds a ZIP package containing the executable, example ROM, instructions and license notices. Windows system DLLs are deliberately excluded and must come from Windows itself.

## Verification

The executable has a headless conversion/export check:

```text
gba-image-packer-xp.exe --self-test C:\path\to\output-folder
```

This creates three PNG/JPEG/BMP test images, converts them using Fit/Stretch/Fill and different JPEG qualities, and writes `xp-self-test.gba` plus `report.json`. It only operates on its generated files in the chosen test folder.

For the same check under Wine, first create an isolated prefix and configure its Windows version as XP:

```sh
mkdir -p /tmp/gba-xp-wine
WINEPREFIX=/tmp/gba-xp-wine wine reg add 'HKCU\Software\Wine' /v Version /d winxp /f
WINEPREFIX=/tmp/gba-xp-wine xvfb-run -a -s '-screen 0 1280x1024x24' wine legacy-xp/dist/gba-image-packer-xp.exe --self-test 'Z:\path\to\test-output'
python3 legacy-xp/tools/verify_rom.py legacy-xp/target/wine-output/xp-self-test.gba
cc firmware/test_rom.c -o legacy-xp/target/test-rom -lmgba
cd legacy-xp/target/wine-output
../test-rom xp-self-test.gba
```

The mGBA harness checks boot, exact framebuffer contents, image browsing, 10/100 skips, slideshow progression and pause. The GUI capture helper also opens a real three-image collection through the command line:

```sh
xvfb-run -a -s '-screen 0 1280x1024x24' python3 legacy-xp/tools/gui_smoke.py
```

It expects the test images in `legacy-xp/target/wine-output/` and saves `legacy-xp/target/gui.png`. Developer verification additionally requires Wine, Xvfb, xdotool, ImageMagick and the mGBA development package. None are part of the distributed Windows app.

## Why a separate engine works

Microsoft documents [GDI+ as introduced in Windows XP](https://learn.microsoft.com/en-us/windows/win32/gdiplus/-gdiplus-gdi-start) and the [GDI+ startup API as supporting XP](https://learn.microsoft.com/en-us/windows/win32/api/gdiplusinit/nf-gdiplusinit-gdiplusstartup). This provides the image codecs and drawing without introducing a modern graphics runtime. The small cartridge data format and embedded firmware are independent of the host UI language.

## Licenses

The application and cartridge firmware follow the repository's MIT license. `licenses/` contains the DejaVu bitmap-font license and the LLVM/MinGW runtime notices used by the native build. The package contains no Microsoft DLLs and no compiler executable. GDI+, MSVCRT and the other Windows DLLs are loaded from the user's Windows installation.
