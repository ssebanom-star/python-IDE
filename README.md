# Coding IDE for Android

A full offline coding IDE that runs on the Samsung Galaxy S25 Ultra (and any
arm64 device with Android 8.0+). Ships with CPython 3.13 embedded via
[Chaquopy](https://chaquo.com/chaquopy/), plus on-device JavaScript and Lua.

## Features

- **Three languages, run on-device:** Python (`.py`, CPython 3.13),
  JavaScript (`.js`, Mozilla Rhino, ES6) and Lua (`.lua`, LuaJ). The language
  is chosen by file extension.
- **Live error checking** as you type — syntax errors and lint warnings
  (undefined names, unused imports via pyflakes for Python; parser errors for
  JS/Lua) shown as wavy underlines, coloured gutter numbers, and a tappable
  problems bar that jumps to the error.
- **Python virtual environments:** create multiple named environments, each
  with its own isolated set of pip packages, switch between them, and delete
  them (menu → *Python environments*). Scripts and pip use the active one.
- **Code editor** with Python syntax highlighting, line numbers, auto-indent,
  adjustable text size, and a keyboard that never resizes the editor.
- **Run scripts on device** with live console output, full tracebacks, a Stop
  button, and a collapsible console.
- **Interactive `input()`** — programs that ask for input get an input bar.
- **matplotlib plots pop up automatically** when a script calls `plt.show()`.
- **Pre-installed libraries:** numpy, pandas, matplotlib, pillow, requests
  (compiled arm64 wheels bundled in the APK).
- **On-device pip + library picker:** install pure-Python packages from PyPI
  into the active environment (menu → *Install libraries* / *Pip packages*).
- **File manager:** create, rename, and delete `.py`/`.js`/`.lua` files.
- Example scripts for every language included on first launch.

## A note on "installing other languages"

Desktop IDEs can download and run any language toolchain. On an **unrooted
Android device this is not possible**: since Android 10 (enforced on Android
14+) apps may not execute binaries from writable storage (W^X / SELinux), so
there is no way to fetch and run a Node/Ruby/Go/C toolchain from inside a
sandboxed app. This IDE therefore embeds interpreters that run *inside* the
app process (Python, JavaScript, Lua) and gives Python real virtual
environments — the closest achievable equivalent of "install a language/
packages and run".

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
