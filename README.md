# Python IDE for Android

A full offline Python IDE that runs on Android (built for the Samsung Galaxy
S25 Ultra, works on any arm64 device with Android 8.0+). Ships with CPython
3.12 embedded via [Chaquopy](https://chaquo.com/chaquopy/).

## Features

- **Code editor** with Python syntax highlighting, line numbers, auto-indent,
  and adjustable text size.
- **Run scripts on device** with live console output, full tracebacks, and a
  Stop button.
- **Interactive `input()`** — programs that ask for input get an input bar in
  the console.
- **matplotlib plots pop up automatically** when a script calls `plt.show()`.
- **Pre-installed libraries:** numpy, pandas, matplotlib, pillow, requests
  (compiled arm64 wheels bundled in the APK).
- **On-device pip:** install any pure-Python package from PyPI straight from
  the app (menu → *Pip packages*), and uninstall them again.
- **File manager:** create, rename, and delete multiple `.py` files; scripts
  can read/write files in their working directory.
- Example scripts included on first launch.

## Install

Download [`release/python-ide.apk`](release/python-ide.apk) (tap it on
GitHub, then use the download button), copy it to the phone, and open it.
Android will ask to allow installs from unknown sources — allow it.
Alternatively every push to this repo builds the APK in GitHub Actions; download
it from the *Build APK* workflow's artifacts.

## Build from source

Requirements: JDK 17+, Android SDK (platform 34), internet access.

```bash
./gradlew assembleRelease
# APK at app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the checked-in development keystore
(`keystore/pythonide.jks`, passwords `pythonide`) so it installs directly.
Replace it with your own keystore before distributing publicly.

## Notes and limits

- Packages with **compiled/native code** (e.g. scipy, torch) can't be
  installed by pip at runtime — Android apps can't compile C. The common
  native libraries are pre-bundled; more can be added in `app/build.gradle`
  under `chaquopy { pip { ... } }` (Chaquopy provides prebuilt Android wheels
  for many, e.g. scipy, scikit-learn, opencv).
- Scripts and files live in the app's private storage
  (`/data/data/com.ssebanom.pythonide/files/scripts`).
