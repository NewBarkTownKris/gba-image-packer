# Android app

The Android app uses a native, scrollable phone interface and the same Rust conversion and cartridge-export code as the desktop application. It runs offline, requests no broad storage permission, and supports Android 8.0 (API 26) or later on ARM64 devices. The APK also contains x86_64 code for emulators and compatible Android devices; 32-bit ARM is not included.

## Use

1. Install `gba-image-packer-1.0.0-android.apk`. If prompted, allow your browser or file manager to install this downloaded app.
2. Tap **Add images** and select one or multiple photos using Android's document picker.
3. Choose a photo from the selected-image list. Adjust its conversion settings, then tap **Apply settings and update preview**. You can also apply its settings to every photo.
4. Arrange the album with **Move image earlier/later**. Set the cartridge title and slideshow interval.
5. Tap **Build and save GBA ROM** and choose a destination in Android's save picker.

The album is saved automatically in the app's private storage. Imports are downsampled to a maximum dimension of 2048 pixels, with photo orientation applied; original files remain untouched. The app processes one photo at a time and retains prepared frames on disk. Albums support at most 400 pictures and must fit the 32 MiB cartridge limit. Saved ROMs remain at their chosen locations when the app is uninstalled; the private editable album is removed.

**Save included example ROM** exports the repository's ready-to-play sample cartridge. **Controls and licenses** contains GBA control instructions and dependency license texts.

## Dual-screen edition

A separate, co-installable [dual-screen alpha](dual-screen/README.md) shows an equal-width source/GBA-result comparison on a second Android display. It includes display selection and an inline fallback. Build it with `python3 android/build.py --dual-screen`; the ordinary APK remains a separate app.

## Build on Linux

The build uses Android SDK tools directly, plus a small Java interface and a Rust JNI library. It does not require Gradle or Android Studio. Install Python 3, Rust, a C compiler, JDK 17 or later, and the [Android command-line tools](https://developer.android.com/studio#command-tools).

Set `ANDROID_HOME` to your Android SDK directory and `JAVA_HOME` to your JDK directory. Make the SDK's `cmdline-tools/latest/bin` and the JDK's `bin` available on your PATH. Accept the SDK licenses with `sdkmanager --licenses`, then install:

```sh
sdkmanager 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358'
rustup target add aarch64-linux-android x86_64-linux-android
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
python3 android/build.py
```

Alternatively, pass the locations explicitly:

```sh
python3 android/build.py --sdk /path/to/sdk --ndk /path/to/android-ndk-r28c --java-home /path/to/jdk
```

The signed APK and its SHA-256 checksum are written to `dist/android/`. Both native architectures use 16 KiB load-segment alignment.

The first local build creates a private signing key and password file in `android/.signing/`, which Git ignores. Keep these files private and back them up: future updates to an installed app must use the same signing key. To use your own key, set `ANDROID_KEYSTORE` and `ANDROID_KEYSTORE_PASSWORD_FILE`; the key alias must be `gba-packer` and its password must match the keystore password. These signing files are never included in the APK or published source.

## Verification

The Java/JNI host test exercises conversion, all exported frame pixels, cartridge headers, Unicode source paths, unchanged input images and error handling:

```sh
cargo build --release --locked --manifest-path android/rust/Cargo.toml
mkdir -p target/android-smoke/classes
javac -d target/android-smoke/classes android/java/io/github/newbarktownkris/gbaimagepacker/Native.java android/test/CoreSmokeTest.java
java -Djava.library.path=android/rust/target/release -cp target/android-smoke/classes CoreSmokeTest target/android-smoke
```

The Android workflow additionally boots an Android 15 emulator, installs the APK, and checks image URI import, conversion controls, previews, reordering, document URI export and album restoration. Its instrumentation supplies document-picker results to exercise the Activity's actual result handlers. It also plays the exported ROM through the mGBA verification harness. Screenshots, logs and the generated cartridge are retained as workflow artifacts.

To run the instrumentation on your own Android 10+ device or emulator, build with `python3 android/build.py --test`, then:

```sh
adb install -r dist/android/gba-image-packer-1.0.0-android.apk
adb install -r dist/android/android-instrumentation.apk
adb shell am instrument -w io.github.newbarktownkris.gbaimagepacker.test/.SmokeInstrumentation
```

Use a fresh app installation for this test: it expects an empty editable album and imports three generated test images. The test APK is a development tool and is not part of the public app download.
