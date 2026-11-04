# GBA Image Packer

Create Game Boy Advance ROMs that display your pictures individually or as a slideshow. The desktop app accepts JPEG, PNG and BMP files, previews the GBA's 240 × 160 display, and builds a self-contained cartridge ROM for a flashcart or emulator.

Download a package for Linux x86_64, macOS Apple Silicon, or Windows x86_64 from [Releases](https://github.com/NewBarkTownKris/gba-image-packer/releases). Every package includes `example.gba`, a ready-to-play example album.

## Run

Extract the archive and launch `gba-image-packer` on Linux, `GBA Image Packer.app` on macOS, or `gba-image-packer.exe` on Windows. Linux requires an X11/XWayland display, OpenGL, ALSA libraries, and Zenity or KDialog for file dialogs. On Ubuntu/Debian, install `libx11-6 libxi6 libgl1 libasound2 zenity`. The macOS app requires macOS 11 or later and is ad-hoc signed, without Apple notarization.

## Build an album

1. Click **Add files**, drag images onto the window, or pass file paths on the command line.
2. Select each image to adjust resize mode, resampling, JPEG quality, rotation, brightness, contrast, gamma and sharpening. **Apply to all** copies its settings to the collection.
3. Use **Up/Down** to order images. **Remove/Delete** removes the selected image; **Clear list** removes all images.
4. Open **ROM options** to set a cartridge title (up to 12 characters) and slideshow interval (1–60 seconds).
5. Click **Build GBA ROM** and copy the resulting `.GBA` file to a compatible flashcart.

**Fit** adds black borders while preserving proportions. **Fill** crops the center to fill the display. **Stretch** fills the display without preserving proportions. The destination preview includes JPEG compression and RGB555 color reduction.

**Save/Open project** preserves source paths, conversion settings, title and interval. Keep the source images available at those paths to reopen a project. Conversion leaves source files untouched.

## GBA controls

The viewer starts on the controls screen. Press **A** to open image selection.

| Action | Controls |
| --- | --- |
| Choose image in menu | D-pad, then A |
| Start slideshow in menu | Start |
| Previous / next image | L / R |
| Skip 10 images | Hold A + L / R |
| Skip 100 images | Hold B + L / R |
| Pseudo-random image | A + B |
| Start slideshow while viewing | Start + R |
| Pause / resume slideshow | A |
| Return to image selection | B, or Start + L |
| Show controls | Select |

Each image is stored as a 76,800-byte RGB555 frame plus its compressed JPEG. Albums must fit the 32 MiB cartridge limit. Timing follows the GBA frame clock at approximately 60 frames per interval second. The viewer displays images at screen size and does not support zoom or audio.

## Build from source

Install stable Rust and a C compiler, then run:

```sh
cargo build --release --locked
cargo run --release -- path/to/picture.jpg
```

Linux builds also need the development packages for X11, Xi, OpenGL and ALSA (Ubuntu/Debian: `libx11-dev libxi-dev libgl1-mesa-dev libasound2-dev`). Windows builds use the MSVC toolchain and Visual Studio C++ build tools. macOS builds use the Xcode command line tools.

The GBA firmware binary is embedded in the executable, as are the UI font and assets. Desktop builds need no GBA cross compiler. To rebuild the firmware after editing it, install GNU Arm Embedded tools and run `sh firmware/build.sh`.

The release workflow builds on all three native operating systems and packages the executable, documentation, dependency licenses and example ROM. To package a local build:

```sh
cargo fetch --locked
python3 scripts/package.py --target x86_64-unknown-linux-gnu --binary target/release/gba-image-packer
```

Development checks:

```sh
cargo fmt --check
cargo test --locked
mkdir -p target/verification
xvfb-run -a target/release/gba-image-packer --self-test target/verification/self-test.json --screenshot target/verification/app.png
cc firmware/test_rom.c -o target/verification/test-rom -lmgba
cd target/verification
./test-rom self-test.GBA
```

The emulator harness requires mGBA development headers and checks boot, pixel output, browsing, skip controls, slideshow and pause. Physical hardware and platform GUI validation should be reported separately from automated build checks.

## License

MIT. See [LICENSE](LICENSE) and [CREDITS.md](CREDITS.md). Font licenses are in `assets/`; release packages include dependency license texts in `licenses/`.
